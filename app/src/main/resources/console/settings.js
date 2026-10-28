// Settings, built from the finished drawing (Splice-Animator/design/console at d3338fa). A rail of the jobs a person
// comes with, and the open job beside it. Every value on this page is read from the daemon (GET /api/config through
// knobs.js, GET /api/history, GET /api/kept/*) and every change goes back to it; a figure splice does not keep is not
// drawn, and a store with no route to count or delete it says so on its own row instead of offering a Delete that
// would do nothing. Jobs whose settings the daemon reads only at start, and the jobs that need their own routes
// (the week's turns, MCP servers, the version), join as each one is true end to end.
CONSOLE.page("settings.html", (scope) => {
  "use strict";
  const { document, window, API, setTimeout, clearTimeout, setInterval, clearInterval, requestAnimationFrame, cancelAnimationFrame, addEventListener, removeEventListener, KNOBS } = scope;

const { fmt, KNOB, st: ks, val, word, rowHtml, ctlHtml, saidHtml, cmdChip, needsRestart, dur } = KNOBS;
const SV = {
  file: G('<path d="M14 3H7a2 2 0 0 0-2 2v14a2 2 0 0 0 2 2h10a2 2 0 0 0 2-2V8z"/><path d="M14 3v5h5"/>'),
  open: G('<path d="M7 17L17 7M9 7h8v8"/>'),
  // a server that lives at a web address, and one nothing runs now (a socket with no lamp lit)
  web: G('<circle cx="12" cy="12" r="9"/><path d="M3 12h18M12 3a14 14 0 0 1 0 18M12 3a14 14 0 0 0 0 18"/>'),
  off: G('<circle cx="12" cy="12" r="6.5"/>'),
  busy: G('<path d="M4 6h16M4 12h16M4 18h9"/><circle cx="18" cy="18" r="1.6"/>'),
  silent: G('<circle cx="12" cy="13" r="7"/><path d="M12 9v4l2.5 2M9 3h6"/>'),
  reasoning: G('<path d="M9 18h6M10 21h4M12 3a6 6 0 0 0-4 10.5c.7.7 1 1.4 1 2.5h6c0-1.1.3-1.8 1-2.5A6 6 0 0 0 12 3z"/>'),
  plan: G('<path d="M4 17a8 8 0 1 1 16 0"/><path d="M12 17l4-5"/>'),
  mcp: G('<path d="M9 3v5M15 3v5M7 8h10v4a5 5 0 0 1-10 0z"/><path d="M12 17v4"/>'),
  projects: G('<path d="M3 7a2 2 0 0 1 2-2h4l2 2.5h8a2 2 0 0 1 2 2V17a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2z"/>'),
  plus: G('<path d="M12 5v14M5 12h14"/>'),  // the kit's own PLUS lives inside its closure
  version: G('<path d="M12 19V5M6 11l6-6 6 6"/>'),
  trash: ICON.trash,   // the kit's own glyph
  data: G('<ellipse cx="12" cy="6" rx="7" ry="3"/><path d="M5 6v12c0 1.7 3.1 3 7 3s7-1.3 7-3V6M5 12c0 1.7 3.1 3 7 3s7-1.3 7-3"/>'),
};
const MiB = 1048576;
const DAY = 864e5;
// WHAT LANDED LAST TIME SIZES THE FIRST FRAME'S GHOST (as Accounts keeps how many doors a login had): the rows of a list are
// read from the daemon, so before they are read the page cannot know how many there will be, and a ghost of three where
// nine land moves everything under it. How many the last real read held is kept in this browser and shapes the next first
// frame; it is a count of shapes to draw and never a figure drawn.
const SHAPE_KEY = "splice-settings-shape";
const shapeKept = (() => { try { return JSON.parse(localStorage.getItem(SHAPE_KEY) || "{}") || {}; } catch { return {}; } })();
const shapeOf = (name, dflt) => (Number.isInteger(shapeKept[name]) && shapeKept[name] >= 0 ? shapeKept[name] : dflt);
function keepShape(name, n) {
  if (shapeKept[name] === n) return;
  shapeKept[name] = n;
  try { localStorage.setItem(SHAPE_KEY, JSON.stringify(shapeKept)); } catch { /* a full store keeps the last shape */ }
}

// ---------- the jobs ----------
// Only the jobs people come to it with (Marlin, Oct 10): a job draws the settings the daemon takes live, in knobs.js's
// order, and a job none of whose settings is live is not on the rail until one is. The names are the
// mock's, with no description line under them (fin).
const TOPICS = [
  { id: "silent", name: "Stalled answers", also: "stall stuck wedged hang quiet resume timeout" },
  { id: "busy", name: "Many agents at once", keys: ["maxInflight", "maxQueued"], also: "overloaded busy queue waiting 429 failed errors" },
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
  .filter((k) => (k.home || k.adv ? TOPIC[k.home || k.adv] : TOPICS.some((x) => x.keys && x.keys.includes(k.key))));
// A setting that does nothing right now still shows, read-only, with why (v0.4.0 section 9: every knob shows; the lead's
// ruling, Oct 13): a value that does nothing would read as working if it took changes (fin). The poll interval while
// reading plan limits is off; the resume wait while no command has a stall gate; "Run at most" while the hub serves,
// which stops servers when idle and leaves their memory to the host's MCP slice (v0.4.0 section 8, as built under the hub).
// The poll interval's reason is another setting on the same pane, so it names it, and pressing the name brings that row
// into view (knobs.js inertHtml, "seerow" below).
const inertWhy = (key) => key === "quotaPollIntervalMs" && val("quotaPoll") === "off" ? { text: "Off with", see: "quotaPoll" }
  : key === "stallReanchorMs" && !KNOB.stallReanchorMs.only.length ? "No command resumes stalled answers"
  : key === "mcpMaxServers" && ui.hubOn ? "Unlimited under the MCP hub" : null;
/** The jobs on the rail: Your data always, the others once one of their settings is live. */
const jobs = () => TOPICS.filter((x) => x.id === "data" || x.id === "version" || liveKnobs().some((k) => topicOf(k.key) === x.id));
/** A job's live settings, in the order knobs.js lists them (the first two of Many agents at once are its own section). */
const keysOf = (id, skip = []) => liveKnobs().filter((k) => topicOf(k.key) === id && !k.adv && !skip.includes(k.key)).map((k) => k.key);
// A job's Advanced disclosure (v0.4.0 section 9, Resolved: advanced settings sit behind a per-section "Advanced"): the
// settings a person rarely needs, every knob without a row of its own among them (knobs.js ADV_HOME), closed on every
// load until pressed, and open while a search finds one of them. Every section ends in one.
const advKeys = (id) => liveKnobs().filter((k) => k.adv === id).map((k) => k.key);
/** Open when pressed, and while a search finds one of its settings by its own words. */
const advOpen = (id) => Boolean(ui.adv[id]) || advKeys(id).some((key) => hits([KNOB[key].label, key, KNOB[key].also || ""].join(" ")));
function advancedHtml(id) {
  const keys = advKeys(id);
  // a topic whose settings grid holds its Advanced has already drawn it there (krowsHtml [adv])
  if (ADV_IN.has(id) && ui.advIn === id) return "";
  // how many settings stood behind the button is what the next first frame draws on it, since the daemon names them
  if (ks.cfg) keepShape(`adv:${id}`, keys.length);
  if (!keys.length) return "";
  const open = advOpen(id), t = turn(`adv:${id}`, open);
  // the count is on the button: how much is behind it is the one thing he cannot see while it is closed. Its rows
  // unfold once, when it opens, and stand still through every later drawing
  return `<section class="psec adv">${advBar(id, keys, open)}`
    + `<div class="advin${open ? t.cls : ""}"${open ? t.style : " hidden"}>${open ? advRows(id, keys) : ""}</div></section>`;
}
/** The button an Advanced is closed behind, with how many settings are behind it, and the marks they carry once it is open. */
const advBar = (id, keys, open) => `<div class="sechead"><button class="act quiet advbtn" data-act="adv" data-id="${id}" aria-expanded="${open}">Advanced<b>${fmt(keys.length)}</b>${ICON.caret}</button>${open ? paneLegend(keys) : ""}</div>`;
/** The topics whose Advanced is the last block of their settings grid, in the column beside what the settings act on. */
const ADV_IN = new Set(["silent", "busy", "reasoning", "plan"]);
// VERSION'S ADVANCED IS SPLIT BY WHAT EACH SETTING BELONGS TO (Oct 27): twenty-odd settings in one grid mixed the ChatGPT
// and Grok commands' addresses with Code mode's switches and splice's own ports, so finding one meant reading them all.
// Each group is named once over its rows; a setting no group claims (one the daemon added, or one it still carries from
// an older release) stands under Other by its own name.
const ADV_GROUPS = [
  ["ChatGPT and Grok", (k) => /^(chatgpt|xai|codex|grok)/.test(k) || k === "pinnedModel" || k === "contextWindowOverride"],
  ["Code mode", (k) => k.startsWith("codeMode")],
  ["splice itself", (k) => ["port", "controlPort", "supervisorUnit"].includes(k) || k.startsWith("ws")],
];
function advRows(id, keys) {
  if (id !== "version") return krowsHtml(keys);
  const left = new Set(keys);
  const groups = ADV_GROUPS.map(([word, of]) => { const xs = keys.filter((k) => of(k)); xs.forEach((k) => left.delete(k)); return [word, xs]; });
  groups.push(["Other", [...left]]);
  return groups.filter(([, xs]) => xs.length).map(([word, xs]) => `<div class="advg"><h4 class="advk">${esc(word)}</h4>${groupRows(xs)}</div>`).join("");
}
/** A group's rows. Two or more of its settings that are lengths of time stand on one log axis under the rest, as the MCP
 *  timers do (the websocket's idle close and its age limit): "10 min" and "35 min" read as two lengths, not two numbers. */
function groupRows(xs) {
  const timed = xs.filter((k) => KNOB[k]?.c?.k === "dur");
  if (timed.length < 2) return krowsHtml(xs);
  return krowsHtml(xs.filter((k) => !timed.includes(k)))
    + krowsHtml(timed, { ax: durAxis(msOf(timed), "How long"), draw: Object.fromEntries(timed.map((k) => [k, durDraw])) });
}

// A figure not read yet is undefined and draws its own shape, striped; one asked for and not answered is null and draws "Not read", its reason warned to the console.
const ui = { adv: {}, said: {}, capture: {}, week: {}, ver: undefined, run: null, headRows: [], saving: null, held: {}, open: "busy", shown: false, q: "", armed: null, bad: {}, ask: null, heads: [], hist: undefined, stores: undefined, kept: {}, peaks: undefined, mcp: undefined, mtest: {}, mtestAt: {}, topo: undefined, mmem: undefined, shareSaving: false, shareSaid: null, exSaid: {}, accts: undefined, projects: undefined, addPath: null, checking: false, checkSaid: null, restarting: false, restartSaid: null, restartWait: 0, mmore: {}, pmore: {}, grown: new Set(), shook: null, err: null, legendSaid: new Set(), gates: {}, chosen: false, dzero: false };
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
/** A REFUSAL SHAKES ONCE (console.css .failed, .act.shook): the drawing right after it carries the mark, and the next one is still. */
const shake = (id) => { ui.shook = id; render(); ui.shook = null; };

// ---------- a change of state that fades ----------
// THE PANE IS DRAWN WHOLE ON EVERY CHANGE (render), so a CSS transition never runs here: each drawing makes new nodes
// that are already in their new state, and a card the search quieted snapped to it. A card, a row or a disclosure whose
// state changed fades from its old look by an animation instead, and every drawing in the next moment (a read landing
// while he types) draws it at the same point of that fade through --turn-at, a negative delay, so the fade is one
// movement and never restarts or snaps. The first drawing of a thing never fades: it had no state to come from.
const turns = new Map();
let snapMs = null;
/** [id] in state [now]: the class and the style that carry it from the state it had into this one, empty once there. */
function turn(id, now) {
  snapMs ??= parseFloat(getComputedStyle(document.documentElement).getPropertyValue("--t-snap")) || 0;
  const t = performance.now(), was = turns.get(id);
  if (!was || was.v !== now) turns.set(id, { v: now, at: was ? t : -Infinity });
  const gone = t - turns.get(id).at;
  return gone < snapMs ? { cls: " turning", style: ` style="--turn-at:${-Math.round(gone)}ms"` } : { cls: "", style: "" };
}

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
  // whether the MCP hub serves this machine (McpHubGate, decided at boot; /health's mcpHub): its holders have no count
  // ceiling, so "Run at most" binds only the servers the daemon runs itself (v0.4.0 section 8, as built under the hub)
  get("/health", (r) => { ui.hubOn = r.ok && r.body?.mcpHub === "on"; });
  // the sharing choice ([daemon] mcp_share) and what every copy on this machine holds against one copy each
  get("/api/topology", (r) => { ui.topo = r.ok ? r.body : null; colourCommands(); });
  get("/api/mcp/memory", (r) => { ui.mmem = r.ok ? r.body : null; nameHolders(); });
  // the plan windows Accounts draws, read here for the highest of each against the warn line these settings set
  get("/api/accounts", (r) => { ui.accts = r.ok ? (r.body?.accounts ?? []) : null; if (r.ok) keepShape("logins", planLogins().length); });
  // the projects the saved folders actually make, and whether each folder is still on disk (ProjectsRoutes.kt): the
  // row under each path is the daemon's own answer, never a listing this page takes itself
  get("/api/projects", (r) => { ui.projects = r.ok ? r.body : null; if (r.ok) keepShape("projects", (r.body?.projects || []).filter(projectName).length); });
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
  ui.gates = Object.fromEntries((heads.body?.heads || []).map((h) => [h.key, h.gate || null]));
  ui.headRows = (heads.body?.heads || []).map((h) => ({ key: h.key, command: h.label || h.key }));
  // the commands whose provider splice resumes mid-answer report a finite resume tier on their gate
  KNOB.stallReanchorMs.only = (heads.body?.heads || []).filter((h) => typeof h.gate?.stall_reanchor_ms === "number").map((h) => h.key);
  ks.cmds = ui.heads;
  ks.commandOf = Object.fromEntries(ui.headRows.map((h) => [h.key, h.command]));
  // a command's colour is its provider's FAMILY: the models route names a provider in its module's own word ("codex",
  // "openai"), which the colour table does not, and a chip drawn from the module's word came out in no colour at all
  ui.modelRows = models.body?.heads || [];
  colourCommands();
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
// A TIME CARRIES ITS ZONE (Oct 26), as today's peak does: today's is its clock, any other day's leads with the date
const whenWord = (ms) => { const d = new Date(ms); return `${d.toDateString() === new Date().toDateString() ? "" : `${dateWord(d)}, `}${clock(d)}${ZONE_WORD ? ` ${ZONE_WORD}` : ""}`; };

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
/** How many cards to a row, so the rows come out even: six read as two rows of three, never four and then two with
 *  half a row empty. Four or fewer stand on one row; above that, the fewest rows of at most four, shared evenly. */
const perRow = (n) => (n <= 4 ? Math.max(1, n) : Math.ceil(n / Math.ceil(n / 4)));
/** A provider as the colour table names it (kit.js COLORS), from the word its module uses ("codex", "anthropic") or, for a
 *  provider he named himself that answers on this computer's loopback, "local" (kit.js providerFamilyOf). */
const familyOf = providerFamilyOf;
/** Every command in its provider's colour: the models route's provider for each, read as its family. Drawn again when the
 *  topology lands, since a provider he named is known to be local only by the address it answers on. */
function colourCommands() {
  const bases = ui.topo?.topology?.providers || {};
  ks.providerOf = Object.fromEntries((ui.modelRows || []).map((r) => [r.head, familyOf(r.provider, bases)]));
}

// ---------- what a setting's row draws ----------
// AN ELEMENT, NEVER A SENTENCE, AND ONLY WHERE THE FACT IS ON THE WIRE (Marlin's ruling, Oct 12). Each of these is a
// fact this page already reads: when the plans were last read, where every login stands against the warn line, the
// logins reporting no window, how many servers run now. The waiting line's own text lives in TurnProgressLine.kt and
// reaches no route, and nothing counts fold continuations, so those rows draw no figure of either.
const seenAt = () => Math.max(0, ...(ui.accts || []).map((x) => x.observed_at_epoch_seconds || 0));
// Accounts' own rule for a login it shows as Signed out (accounts.js): its last reading is from before, so this pane draws
// no window of it, where "100% Highest this week" stood on a signed-out login (splice-usage's walk, Oct 13)
const signedOut = (x) => !x.token_renews && (!x.credential_present || x.auth_exclusion_reason === "credential_missing"
  || (!!x.refusal && !(x.five_hour_used_percent >= 100 || x.seven_day_used_percent >= 100)));
// A login by its command, and by its own name as well where that command has more than one login: several logins can
// share one name, so a bare name never said which, and a command with one login already names it. The whole of it is
// on the login's title.
const loginCmd = (x) => (x.heads || [])[0];
const loginFull = (x) => [...new Set([ks.commandOf?.[loginCmd(x)], x.display_name || x.provider].filter(Boolean))].join(" · ");
function loginWord(x) {
  const cmd = ks.commandOf?.[loginCmd(x)];
  const several = (ui.accts || []).filter((y) => loginCmd(y) === loginCmd(x)).length > 1;
  return cmd && !several ? cmd : loginFull(x);
}
const loginChip = (x) => `<span class="chip" style="--c:${colorOf(familyOf(x.provider))}" title="${esc(loginFull(x))}">${esc(loginWord(x))}</span>`;
/** The logins reporting no plan window at all: the ones the 5-hour stand-in covers. */
const quietLogins = () => (ui.accts || []).filter((x) => !signedOut(x) && x.five_hour_used_percent == null && x.seven_day_used_percent == null);
/** What a row draws when its section gives it no drawing of its own. */
function elOf(key) {
  // the last read, and how soon the next one is due as the reset ring Accounts draws beside a window: full just after
  // a read, empty once the next is due
  if (key === "quotaPollIntervalMs") {
    const seen = seenAt(), every = Number(val("quotaPollIntervalMs")) || 0;
    const left = every ? Math.max(0, 1 - (Date.now() - seen * 1000) / every) : 0;
    return seen ? `<span class="kfact kread">${every && val("quotaPoll") !== "off" ? DRAW.ring(left) : ""}Read <b>${esc(whenWord(seen * 1000))}</b></span>` : "";
  }
  if (key === "maxInflight") return inflightDraw();
  if (key === "maxQueued") return queueDraw();
  if (key === "maxRequestBytes") return sizeDraw();
  // the two switches under Your data that are no store: the door to where those conversations are read and deleted, and, while reading is off, what that costs
  if (key === "transcriptView") return `<div class="kfacts"><a class="link" href="sessions.html">${SV.open}Sessions</a>${costHtml(KNOB.transcriptView.offCost, !val(key))}</div>`;
  if (key === "replayReasoning") return `<a class="act quiet small" href="sessions.html">Delete in Sessions</a>`;
  if (key === "usageWarnPct") return warnDraw();
  // the logins this stand-in covers, as their chips in rows under the words, the first four whole and the rest a count
  if (key === "usageWarnTokens5h") {
    const quiet = quietLogins(), rest = quiet.slice(4);
    return quiet.length ? `<div class="kfacts"><span class="kfact">No limit reported</span><span class="kchips">${quiet.slice(0, 4).map(loginChip).join("")}`
      + `${rest.length ? `<span class="omore" title="${esc(rest.map(loginFull).join(", "))}">+${fmt(rest.length)}</span>` : ""}</span></div>` : "";
  }
  // how many run now against the most it may, as a meter on that ceiling: full at the limit, in the limit red
  if (key === "mcpMaxServers") {
    if (!ui.mcp) return "";
    const up = Object.values(ui.mcp.servers || {}).filter((s) => s.hosted).length, most = Number(val("mcpMaxServers")) || 0;
    // under the hub the ceiling does nothing, so there is nothing to draw it against: how many run is the one fact
    return most && !ui.hubOn ? `<div class="kmeter">${DRAW.meter((up / most) * 100, up >= most ? "full" : "")}<span><b>${fmt(up)}</b> running</span></div>`
      : `<span class="kfact"><b>${fmt(up)}</b> running</span>`;
  }
  return "";
}

// A SETTING A DRAWING NAMES WEARS ITS NUMBER (Oct 28): the terminal on Reasoning draws four lines that four settings decide, and
// the line says "2" where the setting stands as "2", so no name is written twice on the screen.
const TIE_NO = { showReasoning: 1, summary: 2, progressLine: 3, verbosity: 4 };
// ---------- a section's settings, on one grid ----------
// A SETTING IS A ROW, AND WHAT IT DOES IS DRAWN ON IT (Oct 27, from Marcos's "show don't tell"): its name, its control
// and, where this page reads the fact, the drawing of what the value does, each in a column of its own, so the names,
// the controls and the drawings start at one x down a section. A section whose rows draw nothing is two columns.
// [draw] gives a row its own drawing by key. [ax] puts the drawings on one time axis, its ticks said once under the
// rows. [mark] is a figure this page read on that axis (the longest stall this week): one upright line through every
// row, with its door above them, so each wait is read against what happened.
function krowsHtml(keys, { ax = null, draw = {}, mark = null, adv = null } = {}) {
  if (!keys.length) return "";
  // [adv] names the topic whose Advanced is the last block of this grid: its settings stand in the section's own columns,
  // under the rest, so a name, a control and a drawing keep one x from the first setting to the last
  const behind = adv ? advKeys(adv) : [], all = [...keys, ...behind];
  const drawn = all.map((key) => (draw[key] && ax ? draw[key](key, ax) : elOf(key)));
  const flat = !ax && drawn.every((d) => !d);
  const at = ax && mark ? ax.at(mark.ms) : null;
  const line = at == null ? "" : `<i class="dline" style="left:${at.toFixed(2)}%" aria-hidden="true"></i>`;
  const rowOf = (key, i) => {
    const k = KNOB[key], hay = knobHay(k);
    // a search keeps every row in its place: the ones it found keep their ink, the rest fade to quiet
    const found = words().length ? (hits(hay) ? " hit" : " miss") : "", t = turn(`row:${key}`, found === " miss");
    const el = flat ? "" : `<div class="kdraw">${drawn[i] ? `${drawn[i]}${draw[key] ? line : ""}` : ""}</div>`;
    return `<div class="krow${found}${t.cls}" data-key="${key}"${t.style}>${rowHtml(k, { hl, lit: lit(hay), el, inert: inertWhy(key), tag: TIE_NO[key] ? `<b class="knum">${TIE_NO[key]}</b>` : "" })}</div>`;
  };
  const rows = keys.map(rowOf);
  const head = at == null ? "" : `<div class="khead"><span></span><span></span><span class="kmk">`
    + `<a class="dmark${at > 55 ? " end" : ""}" href="${mark.href}" style="left:${at.toFixed(2)}%">${mark.inner}</a></span></div>`;
  const ticks = ax ? ax.ticks.map((v) => {
    const x = ax.at(v), edge = x < 4 ? " pd-t-first" : x > 96 ? " pd-t-last" : "";
    return `<span class="pd-tk${edge}" style="left:${x.toFixed(2)}%">${esc(dur(v))}</span>`;
  }).join("") : "";
  // the axis's word sits in the control column, at its right edge, so it ends where the axis starts and reads as its caption
  const foot = ax ? `<div class="kaxis" aria-hidden="true"><span></span><span class="kaw">${esc(ax.word)}</span><span class="pd-ax">${ticks}</span></div>` : "";
  // a section with no drawing stands in columns, never more of them than it has rows (settings.css .krows.flat)
  const cols = flat ? ` flat${keys.length >= 3 ? " n3" : keys.length === 2 ? " n2" : ""}` : "";
  // the settings behind the Advanced button: what each was before it was shown stays as it was, and the button keeps its place
  // under the rest whether it is closed or open; opened, its rows unfold once and a second axis closes the waits among them
  let more = "";
  if (behind.length) {
    // how many settings stood behind the button is what the next first frame draws on it, since the daemon names them
    if (ks.cfg) keepShape(`adv:${adv}`, behind.length);
    ui.advIn = adv;
    const open = advOpen(adv), t = turn(`adv:${adv}`, open);
    more =`<section class="adv in">${advBar(adv, behind, open)}<div class="advin${open ? t.cls : ""}"${open ? t.style : " hidden"}>`
      + `${open ? behind.map((key, i) => rowOf(key, keys.length + i)).join("") + (ax && behind.some((key) => draw[key]) ? foot : "") : ""}</div></section>`;
  }
  return `<div class="krows${cols}">${head}${rows.join("")}${foot}${more}</div>`;
}
// THE MARKS ARE NAMED ONCE A PANE (Marcos, Oct 25: "show don't tell"): the first section carrying a mark names it on its
// kicker line, a later one naming only the marks above it did not. The knob kit owns the rule (KNOBS.legendHtml's
// [said] set, which every view shares): that includes the ring-and-plus slot where a value for one command is set, so
// no row says "One command" and no topic repeats a scope sentence.
function paneLegend(keys) {
  return KNOBS.legendHtml(keys, { said: ui.legendSaid });
}
/** A section of settings: its kicker, what its rows' marks mean, and the rows. */
const knobSec = (kicker, keys, opts = {}) => {
  if (!keys.length) return "";
  const aside = `${opts.legend || ""}${paneLegend(keys)}`;
  return DRAW.section(kicker, krowsHtml(keys, opts), { aside: aside && `<span class="kside">${aside}</span>` });
};

// the per-command settings splice reads at start and the page only shows (restart-only, set in [heads.KEY.overrides])
const tapOf = (head) => Number(ks.cfg?.layers?.perHead?.[head]?.wireTap) || 0;
// whether a command is saving prompts and answers NOW: its capture switch (GET /api/heads/{head}/capture), which the daemon
// applies on the command's next request; the setting it booted with is only the fallback when that route did not answer
const traceOn = (head) => ui.capture[head] ?? ks.cfg?.layers?.perHead?.[head]?.trace !== false;

// ---------- Your data ----------
// Each store, what it holds, how long, and its Delete now. A store with no route to count or delete it says so.
const DATA_ROWS = [
  { id: "trace", g: "text", name: "Prompts and answers", unit: "requests", door: ["requests.html", "Requests"] },
  // the stores a person's prompts sit in that no feature runs without: counted and cleared by /api/kept/stores, each naming the
  // feature that needs it in place of a switch, with the lifetime splice gives it (the history window, or its own)
  { id: "originals", g: "text", name: "Transcript copies", unit: "files", store: "transcript_copies", knob: "keepTranscriptCopies", off: "When off, a session moved with Continue on cannot move back" },
  { id: "recordings", g: "text", name: "Compaction summaries", unit: "files", store: "compaction_summaries", knob: "keepCompactionSummaries", off: "When off, retried compactions cost again", life: "2 hours", days: 2 / 24 },
  { id: "journals", g: "text", name: "Code mode work", unit: "files", store: "code_mode", knob: "keepCodeModeWork", off: "When off, a restart ends running scripts", life: "1 day unused", days: 1 },
  { id: "hist", g: "records", name: "Usage and request history", unit: "requests", door: ["usage.html", "Usage"] },
  // its own days and its own switch, both live (ConsoleWiring.activityStores reads them per sweep and per write); it never
  // followed the history window, which this row used to claim (v0.4.0 section 9: every knob drawn with its value)
  { id: "edges", g: "records", name: "Messages between agents", unit: "messages", door: ["teams.html", "Teams"], knob: "messageEdges", off: "When off, new messages between agents are not saved" },
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
/** A store whose count has not come back yet. */
const stillReading = (r) => heldOf(r) === undefined;
// REMOVAL ARMS IN PLACE: Delete now turns into what it would delete, by count, and the way out, on the store's own row
const delBtn = (id, n, unit) => (!n ? "" : ui.armed === id
  ? `<span class="armed"><button class="act danger small" data-act="delete" data-id="${id}">Delete ${fmt(n)} ${unit}</button><button class="act quiet small" data-act="disarm">Cancel</button></span>`
  : `<button class="act quiet small del" data-act="arm" data-id="${id}" aria-label="Delete now" title="Delete now">${SV.trash}</button>`);
const ALWAYS = `<span class="fixed quiet">Always saved</span>`; // a store whose daemon has no off switch
// WHAT OFF COSTS IS SAID WHILE IT IS IN FORCE (Oct 26): under a switch that is On the line told him, on every card, what
// a press he is not making would cost. It is the Off button's own title while On, and the line under the switch once Off,
// where it is the state he is in. Each part wraps at a dot, never inside a part (fin).
const costHtml = (off, isOff) => (off && isOff ? `<p class="offcost now">${off.split(" · ").map((t) => `<span class="whole">${esc(t)}</span>`).join(" · ")}</p>` : "");
const offTitles = (off) => (off ? { titles: { false: off.replace(/ · /g, ". ") } } : {});
/** ONE STORE IS ONE ROW OF ITS GROUP'S TABLE (Oct 28), the way a command is one row of Usage's results: its colour, name and
 *  door, then what it holds and how fast it grows, each a bar on its column's one scale, then its Save and Keep for, with the
 *  length it keeps drawn on one log axis, and last its Delete, on the row it deletes. [under] is what opens beneath the row:
 *  the per-command list, a shorter window's question, the usage chart. The columns are named once, over the table. */
const drow = ({ id = "", cls = "", name, hay = name, door, meta = "", size = "", rate = "", sw = "", keep = "", track = "", del = "", off = "", offShown = false, under = "", hue = "", lamp = "" }) => ({
  name,
  html: `<article class="drow${cls}${lit(hay)}" data-store="${esc(id)}"${hue ? ` style="${hue}"` : ""}>`
    + `<div class="dn"><div class="dnm">${hue ? `<i class="dsw" aria-hidden="true"></i>` : ""}<span class="nm">${hl(name)}</span>${lamp}${door ? `<a class="link" href="${door[0]}">${SV.open}${door[1]}</a>` : ""}</div>${meta ? `<span class="dmeta">${meta}</span>` : ""}</div>`
    + `<div class="dz" data-l="Size">${size}</div><div class="dr" data-l="A day">${rate}</div><div class="ds" data-l="Save">${sw}</div>`
    + `<div class="dk" data-l="Keep for"><span class="dkc">${keep}</span>${track}</div><div class="dx">${del}</div>`
    + `${costHtml(off, offShown)}${under}</article>`,
});
/** The columns of a group's table, named once. */
const DHEAD = `<div class="dhead" role="row"><span>Store</span><span>Size</span><span>A day</span><span>Save</span><span>Keep for</span><span class="dcant">Cannot be undone</span></div>`;
// HOW LONG A STORE KEEPS WHAT IT HOLDS IS A LENGTH, SO IT IS DRAWN AS ONE (Oct 28): every store's Keep for is a track on one log
// axis of days, from an hour to two years, so "2 hours", "30 days" and "Forever" read as lengths side by side before a word
// is read. A store that keeps nothing past the conversation has no length and draws no track.
const KEEP_LO = 1 / 24, KEEP_HI = 730;
const KEEP_TICKS = [[1 / 24, "1 h"], [1, "1 d"], [7, "1 wk"], [30, "1 mo"], [365, "1 yr"]];
const keepAt = (days) => Math.max(0, Math.min(100, (Math.log(Math.max(days, KEEP_LO) / KEEP_LO) / Math.log(KEEP_HI / KEEP_LO)) * 100));
const keepWord = (days) => (days === Infinity ? "No end" : days === 0 ? "Today only" : days < 1 ? `${Math.round(days * 24)} hours` : `${+days.toFixed(1)} ${days === 1 ? "day" : "days"}`);
function keepTrack(days, said = "") {
  if (days == null || Number.isNaN(days)) return `<span class="dtrk keep" aria-hidden="true"></span>`;
  const open = days === Infinity;
  return `<span class="dtrk keep${open ? " open" : ""}" role="img" aria-label="${esc(said || `Kept for ${keepWord(days)}`)}">${days > 0 ? `<i class="dfill" data-tw="" data-w="${open ? 100 : keepAt(days).toFixed(2)}" style="width:${open ? 100 : keepAt(days).toFixed(2)}%"></i>` : ""}</span>`;
}
const dfoot = () => `<div class="dfoot" aria-hidden="true"><span></span><span></span><span></span><span></span><span class="dk"><span class="dkc">How long</span><span class="pd-ax">${KEEP_TICKS.map(([d, w], i) => {
  const x = keepAt(d), edge = x < 4 ? " pd-t-first" : x > 96 ? " pd-t-last" : "";
  return `<span class="pd-tk${edge}" style="left:${x.toFixed(2)}%">${w}</span>`;
}).join("")}</span></span><span></span></div>`;
/** Everything splice keeps, in bytes: the figure the pane leads with. */
const allBytes = () => DATA_ROWS.reduce((s, r) => s + (heldOf(r)?.bytes ?? 0), 0);
/** How fast a store grows, in bytes a day, or null where nothing on the wire says. Usage history is the one store the daemon
 *  measures (rate_bytes_per_month, over its own days); for every other store it is the size over the days since the oldest
 *  thing kept, and a store that reports no oldest day (prompts and answers) has no rate to draw. */
function rateOf(r) {
  const x = heldOf(r);
  if (!x) return null;
  if (r.id === "hist") return ui.hist?.rate_bytes_per_month ? ui.hist.rate_bytes_per_month / 30 : null;
  if (!x.since || !x.bytes) return null;
  return x.bytes / Math.max(1, Math.round((Date.now() - x.since) / DAY));
}
/** The one scale each of the table's two bar columns stands on: the largest store, and the fastest growing. */
const dscale = () => ({
  size: Math.max(1, ...DATA_ROWS.map((r) => heldOf(r)?.bytes ?? 0)),
  rate: Math.max(1, ...DATA_ROWS.map((r) => rateOf(r) ?? 0)),
});
/** A figure on its column's scale: the bar to its share of the largest, the figure at the column's right edge. */
const dbar = (v, top, key) => DRAW.barCell({ w: v > 0 ? Math.max(1.2, (v / top) * 100) : 0, text: esc(mbWord(v)), key });
/** Everything that holds bytes, biggest first: the order the split bar, the cards' swatches and Free up space all take
 *  their colour from, so one store is one colour wherever it appears on the pane. */
const bySize = () => DATA_ROWS.map((r) => ({ r, x: heldOf(r) })).filter((p) => p.x && p.x.bytes > 0).sort((a, b) => b.x.bytes - a.x.bytes);
const hueAt = (i) => `--c:${storeInk(i)}`;
const hueOf = (r) => { const i = bySize().findIndex((p) => p.r.id === r.id); return i < 0 ? "" : hueAt(i); };
/** Usage history, day by day, as the daemon measured it (its own per-day bytes): the first day kept and today at the
 *  ends, and the biggest day named on its own column. No other store has a series, so no other card draws one. */
function daysChart(h, hue = "") {
  if (!h || !h.days || h.days.length < 2) return "";
  const sizes = h.days.map((d) => d.bytes), top = Math.max(...sizes) || 1, at = sizes.indexOf(top), n = sizes.length;
  // the last column is today, so a column's day is counted back from it
  const dayOf = (i) => dateWord(new Date(startOfToday() - (n - 1 - i) * DAY));
  const bars = sizes.map((b, i) => `<i style="--h:${Math.max(2, (b / top) * 100).toFixed(1)}%"${i === at ? ' class="top"' : ""} title="${esc(`${dayOf(i)} · ${mbWord(b)}`)}"></i>`).join("");
  // the biggest day's label stands over its own column, starting there near the left end and ending there near the right
  const x = (at + 0.5) / n, side = x < 0.3 ? "start" : x > 0.7 ? "end" : "mid";
  const peak = `<span class="dpeak ${side}" style="--at:${(x * 100).toFixed(2)}%"><span><b>${esc(mbWord(top))}</b> ${esc(dayOf(at))}</span></span>`;
  return `<div class="dchart"${hue ? ` style="${hue}"` : ""}>${peak}<div class="dbars" role="img" aria-label="${n} days, the biggest ${esc(mbWord(top))} on ${esc(dayOf(at))}">${bars}</div>`
    + `<div class="dcends"><span>${esc(dateWord(new Date(h.held.oldest_epoch_ms)))}</span><span>Today</span></div></div>`;
}
/** What a store holds, under its name: how many, and since when. A store holding nothing says nothing: its size is an empty track. */
const metaOf = (x, r) => (x.n || x.bytes ? `${fmt(x.n)} ${esc(x.n === 1 ? r.unit.replace(/s$/, "") : r.unit)}${x.since ? ` · since ${esc(dateWord(new Date(x.since)))}` : ""}` : "");
/** The two bar cells of a store that has been read, on the table's scales. */
const cellsOf = (r, x, scale) => {
  const rate = rateOf(r);
  return { meta: metaOf(x, r), size: dbar(x.bytes, scale.size, `size:${r.id}`), rate: rate == null ? `<span class="barcell none">${DRAW.dash}</span>` : dbar(rate, scale.rate, `rate:${r.id}`) };
};
// what a store's cells will be, while it is read: the bars' tracks and figures, striped, so the row stands as tall as it will
const CELLS_GHOST = { meta: GHOST.bar(8), size: GHOST.cell(), rate: GHOST.cell() };
// a store that could not be read keeps its row: dashed tracks and the dash on each column's edge, and Not read said once, under its name
const CELLS_UNREAD = { meta: `<span class="state limit">Not read</span>`, size: DRAW.unread.cell(), rate: DRAW.unread.cell() };
// A store splice could not read says so on its own figure line, in the limit colour as every unread figure on this page
// does; why goes to the console, where the read that failed is named (fin, Oct 11)
const unreadable = `<span class="state limit">Not read</span>`;
/** The reason a read failed, in the words every page warns with. */
const whyNot = (path, r) => console.warn(`${path}: ${r.status === 0 ? "splice is not answering" : r.body?.error || `splice answered ${r.status}`}`);

// The usage and request history. A shorter window waits for the yes in a band under the row: the held days as one bar,
// the ones that go hatched, the first day kept as a tick. The yes sends the moment it showed (PUT /api/history).
function histBar(h) {
  const total = h.held.bytes || 1, at = (h.cut.bytes / total) * 100, w = h.cut.cutoff_epoch_ms;
  const days = h.days.map((d) => `<i style="--mb:${d.bytes}"></i>`).join("");
  const tick = `<span class="hcut" style="--at:${at}%">${h.window.nothing ? "" : `<em>${atWord(w)}</em>`}</span>`;
  return `<div class="hbar"><span class="hend">${atWord(h.held.oldest_epoch_ms)}</span><div class="hdays" role="img" aria-label="${mbWord(h.cut.bytes)} will be deleted; kept from ${h.window.nothing ? "today" : atWord(w)}">`
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
function histRow(r, scale) {
  const hue = hueOf(r), base = { id: r.id, name: r.name, door: r.door, cls: " hist", keep: `${ctlHtml(KNOB.historyRetentionDays, ui.ask ? ui.ask.v : keptDays())}${saidHtml("historyRetentionDays")}` };
  if (ui.hist === undefined) return drow({ ...base, ...CELLS_GHOST, keep: GHOST.bar(7, "chip"), sw: GHOST.bar(6.5, "chip"), track: GHOST.track() });
  if (!ui.hist) return drow({ ...base, ...CELLS_UNREAD, sw: ALWAYS });
  const a = ui.ask, x = heldOf(r), id = "del:hist", days = a ? (a.v === null ? Infinity : Number(a.v)) : keptDays() === "forever" ? Infinity : Number(keptDays());
  return drow({
    ...base, ...cellsOf(r, x, scale), hay: `${r.name} ${KNOB.historyRetentionDays.also}`, hue,
    sw: ALWAYS, // the daemon has no switch for turn statistics: only how far back they go
    track: keepTrack(days),
    del: `${ui.bad[id] ? `<span class="said limit">${esc(ui.bad[id])}</span>` : ""}${delBtn(id, x.n, r.unit)}`,
    // the days kept, as the daemon measured them, under the row; a shorter window's question opens under that, holding the yes
    under: `${daysChart(ui.hist, hue)}${a && a.h ? `<div class="dmore hask">${histBar(a.h)}<div class="hacts"><button class="act danger small" data-act="shorten">Delete ${fmt(a.h.cut.turns)} requests</button><button class="act quiet small" data-act="unask">Cancel</button></div></div>`
      : a ? `<div class="dmore hask"><div class="hbar reading"><span class="hend"></span><div class="hdays"></div><span class="hend">Today</span></div><div class="hacts"><button class="act danger small" disabled>Delete</button><button class="act quiet small" data-act="unask">Cancel</button></div></div>` : ""}`,
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
/** Since when prompts and answers have not saved, from each command's trace/kept answer: the first stop among the
 *  commands saving, the reason in words, and the commands it holds for. Null while every one saves whole. Settings said
 *  "25.2 GB counted" while nothing had been saved since 10:44 AM, the disk at splice's free-space floor (walk on d0ec523b5). */
function traceStopped() {
  const stops = ui.heads.map((h, i) => ({ h, b: ui.kept.trace?.[i] })).filter(({ h, b }) => traceOn(h) && b?.saving_stopped_at_epoch_ms);
  if (!stops.length) return null;
  const at = Math.min(...stops.map(({ b }) => b.saving_stopped_at_epoch_ms));
  const why = String(stops.find(({ b }) => b.saving_stopped_at_epoch_ms === at).b.saving_stopped_reason || "");
  // the cause in his words; the daemon's own sentence goes to the console, where it can be read in full. Each known
  // cause is one that deleting what is kept clears, so it carries the way to Free up space; an unknown one does not.
  if (why) console.warn(`trace: saving stopped: ${why}`);
  const known = /free-space floor/.test(why) ? "The disk is down to the 64 GB splice leaves free"
    : /index budget/.test(why) ? "The 16 GB for request records is full"
    : /body budget|evicted/.test(why) ? "The 32 GB for prompts and answers is full" : "";
  const heads = stops.length < ui.heads.filter(traceOn).length ? stops.map(({ h }) => h) : [];
  return { when: whenWord(at), words: known || shortWhy(why), known: !!known, heads };
}
/** How long prompts and answers stay: the longest any command keeps them, and the caps that can end it sooner. */
function traceDays() {
  const w = ui.hist.window, windowDays = w.forever ? Infinity : w.nothing ? 0 : w.days;
  const days = ui.heads.map((h, i) => {
    const body = ui.kept.trace?.[i];
    // the route's kept_days (227e4e5bb): null keeps every day; a body without the field is an older daemon
    if (body && "kept_days" in body) return body.kept_days === null ? Infinity : body.kept_days;
    return traceSetDays(h) ?? windowDays;
  });
  return days.length ? Math.max(...days) : windowDays;
}
function traceKeep() {
  const n = traceDays();
  return n === Infinity ? "Until its size caps" : n === 0 ? "Today only" : `Up to ${n} ${n === 1 ? "day" : "days"}`;
}
// the size caps that can end a keep sooner (the oldest whole day goes first), said on the track's own title and never as a footnote
const TRACE_CAPS = "sooner past its size caps, the oldest day first";
/** Prompts and answers, one command at a time: its switch, what its change did, and what it holds on one shared scale. */
function traceCommands() {
  const sizes = ui.heads.map((h, i) => ({ h, b: ui.kept.trace?.[i]?.bytes ?? null }));
  const top = Math.max(1, ...sizes.map((s) => s.b ?? 0));
  // the commands holding nothing are one count under the others, and open in place: the switch of each is still one press away
  const empty = sizes.filter((s) => s.b === 0), shown = ui.dzero || empty.length < 2 ? sizes : sizes.filter((s) => s.b !== 0);
  const line = ({ h, b }) => {
    const said = ui.said[`capture@${h}`] ? `<span class="said ${ui.bad[`capture@${h}`] ? "limit" : "ok"}">${esc(ui.said[`capture@${h}`])}</span>` : "";
    return `<div class="cs${traceOn(h) ? "" : " off"}">${cmdChip(h)}${b === null ? DRAW.unread.cell() : DRAW.barCell({ w: (b / top) * 100, text: esc(mbWord(b)) })}`
      + `<span class="switch"><button data-act="capture" data-head="${esc(h)}" data-v="true" aria-pressed="${traceOn(h)}">On</button><button data-act="capture" data-head="${esc(h)}" data-v="false" aria-pressed="${!traceOn(h)}">Off</button></span>${said}</div>`;
  };
  const rows = [...shown].sort((a, b) => (b.b ?? -1) - (a.b ?? -1)).map(line).join("");
  const more = empty.length > 1 ? DRAW.more(empty.length, { act: "dzero", pill: !ui.dzero, open: ui.dzero }) : "";
  return `<div class="dmore"><div class="cmdsw">${rows}</div>${more}</div>`;
}
/** A store's Keep for: its control or its words, and the length it draws on the axis (days, Infinity for none, null for no length). */
function keepOf(r) {
  // the copies keep their own days (the President, Oct 12), and the messages theirs, so each row sets them in its Keep for
  if (r.id === "originals") return { keep: `${ctlHtml(KNOB.transcriptCopyDays, val("transcriptCopyDays"))}${saidHtml("transcriptCopyDays")}`, days: Number(val("transcriptCopyDays")) };
  if (r.id === "trace") return ui.hist ? { keep: `<span class="fixed quiet">${esc(traceKeep())}</span>`, days: traceDays(), said: `${traceKeep()}, ${TRACE_CAPS}` } : { keep: "", days: null };
  if (r.id === "edges") return { keep: `${ctlHtml(KNOB.activityRetentionDays, val("activityRetentionDays"))}${saidHtml("activityRetentionDays")}`, days: Number(val("activityRetentionDays")) };
  if (r.id === "labels") return { keep: `<span class="fixed quiet">Today and yesterday</span>`, days: 2 };
  return r.life ? { keep: `<span class="fixed quiet">${esc(r.life)}</span>`, days: r.days } : { keep: "", days: null };
}
function dataRow(r, scale) {
  if (r.id === "hist") return histRow(r, scale);
  const x = heldOf(r), id = `del:${r.id}`, base = { id: r.id, name: r.name, door: r.door };
  // the row that lands with its switch and how long it keeps holds the place of both while its size is read, so the table
  // is as tall before the read as after it
  if (x === undefined) return drow({ ...base, ...CELLS_GHOST, sw: GHOST.bar(6.5, "chip"), keep: GHOST.bar(7, "chip"), track: GHOST.track(), del: GHOST.bar(5.5, "chip") });
  if (!x) return drow({ ...base, ...CELLS_UNREAD });
  const on = r.id === "trace" ? ui.heads.filter(traceOn) : null;
  const { keep, days, said: keepSaid } = keepOf(r);
  const said = r.knob ? saidHtml(r.knob) : "";
  // every command saving is a dot in its provider's colour, hollow where it is off, so "9 of 11" is seen before it is read
  const dots = on ? `<span class="cdots" aria-hidden="true">${ui.heads.map((h) => `<i class="${traceOn(h) ? "" : "off"}" style="--c:${colorOf(ks.providerOf[h])}"></i>`).join("")}</span>` : "";
  return drow({
    ...base, ...cellsOf(r, x, scale), keep, track: keepTrack(days, keepSaid), hue: hueOf(r), cls: said || r.knob ? " once" : "", off: r.off || "", offShown: !!r.knob && !val(r.knob),
    lamp: r.id === "trace" && traceStopped() ? DRAW.lamp("stalled") : "",
    sw: r.knob ? ctlHtml(KNOB[r.knob], val(r.knob), null, offTitles(r.off)) + said
      : on ? `<button class="count" data-act="saving" aria-expanded="${ui.saving === r.id}">${dots}${on.length} of ${ui.heads.length} commands${ICON.caret}</button>` : "",
    under: on && ui.saving === r.id ? traceCommands() : "",
    del: `${ui.bad[id] ? `<span class="said limit">${esc(ui.bad[id])}</span>` : ""}${delBtn(id, x.n, r.unit)}`,
  });
}
// THE SPLIT BAR IS THE KIT'S (DRAW.stack): one proportional bar over a whole, each part labelled inside itself where
// it is wide enough and in one row under the bar where it is not. The kit now takes each part's own figure and its own
// colour, and a part that is the REST of a whole ("rest": the servers not shared, the repos no folder holds) keeps its
// own word instead of being gathered under an outcome's phrase — so this page carries no copy of the bar. A bar grows
// in from nothing the first time it is drawn with data, once.
const SPLIT_REM = 60; // about how wide the pane's bar is at the narrowest side-by-side width, in rem
// six store colours; a seventh part would take the first one's colour again and read as that store, so every part past
// the sixth wears the one neutral ink, its name on it
const storeInk = (i) => (i < 6 ? `var(--store-${i + 1})` : "var(--text-mute)");
function splitBar(parts, total, { id = "", tail = "" } = {}) {
  const grow = id && !ui.grown.has(id) && total > 0;
  if (grow) ui.grown.add(id);
  const label = parts.filter((p) => p.n > 0).map((p) => `${p.word} ${p.text}`).join(", ");
  return DRAW.stack(parts, total, { grow, label, tail, bw: SPLIT_REM });
}
/** What splice keeps, all of it, as the topic's one figure and one bar split by store, biggest first; and the moment
 *  saving stopped, with the way to the space that brings it back. */
function keptHtml() {
  const reading = DATA_ROWS.some(stillReading), unread = DATA_ROWS.some((r) => heldOf(r) === null);
  const held = bySize(), all = allBytes();
  // a total over a store that was not read is not read either: "0 B" over unread stores is a figure that is not true
  const hero = reading ? heroGhost()
    : unread ? `<div class="pd-top"><b class="pd-rate">${DRAW.dash}</b><span class="pd-failed"><span>kept</span></span>${unreadable}</div>`
    : DRAW.hero({ figure: mbWord(all), of: "kept" });
  const stop = reading ? null : traceStopped();
  // SAVING STOPPED IS A STATE ON THE FIGURE IT STOPS (Oct 26): the stalled lamp, since when, the commands it holds for,
  // the cause, and the one way back, which scrolls to the stores' deletes and lights them once
  const stopped = stop ? `<div class="dstop" role="status">${DRAW.lamp("stalled")}<b>Saving stopped ${esc(stop.when)}</b>`
    + `${stop.heads.length ? `<span class="dwho">${stop.heads.map((h) => cmdChip(h)).join("")}</span>` : ""}${stop.words ? `<span class="dwhy">${esc(stop.words)}</span>` : ""}`
    + (stop.known ? `<button class="act quiet small" data-act="tofree">Free up space</button><span class="dthen">Saves again on the next request</span>` : "") + `</div>` : "";
  const bar = reading ? GHOST.stack() : all ? splitBar(held.map((p, i) => ({ n: p.x.bytes, text: mbWord(p.x.bytes), word: p.r.name, ink: storeInk(i) })), all, { id: "data" }) : "";
  return DRAW.section("On this computer", `${hero}${stopped}${bar}`, { cls: "tally" });
}
// the knobs each group's rows carry, so the marks on their values are named once on the group's own line
const GROUP_KNOBS = {
  text: ["keepTranscriptCopies", "transcriptCopyDays", "keepCompactionSummaries", "keepCodeModeWork"],
  records: ["historyRetentionDays", "messageEdges", "activityRetentionDays"],
};
// the tap is set per command in splice.toml and read at start, so its row carries the lock and restart marks, and the
// group's legend names them only while that row is drawn
const groupKnobs = (g, tapped) => (g === "text" && tapped ? [...GROUP_KNOBS.text, "wireTap"] : GROUP_KNOBS[g]);
/** A group's table: its columns named once, a row to each store (the biggest first, then the ones holding nothing yet in the
 *  order they are listed), and under them the axis the Keep for tracks stand on. */
const dtable = (g, label, scale, extra = "") => {
  // the biggest first; a store still being read holds the top, where the biggest of them usually lands, so no row jumps when its read comes in
  const order = (r) => -(heldOf(r)?.bytes ?? (stillReading(r) ? Infinity : -1));
  return `<div class="dwrap"><div class="dtab" role="table" aria-label="${esc(label)}">${DHEAD}${DATA_ROWS.filter((r) => r.g === g).sort((a, b) => order(a) - order(b)).map((r) => dataRow(r, scale).html).join("")}${extra}${dfoot()}</div></div>`;
};
function dataHtml() {
  // what each tapped command holds in memory right now: counted, never shown, and no Delete since the daemon has none
  const taps = ui.heads.filter((h) => tapOf(h) > 0), heldN = taps.reduce((n, h) => n + (ui.held[h] ?? 0), 0);
  const scale = dscale();
  // The row draws when the fact exists: a command taps its requests or bodies are held. With neither it holds nothing and
  // offers nothing (the tap is set per command in splice.toml and read at start), so it is not drawn at all (fin, Oct 11).
  const memory = taps.length || heldN ? drow({
    id: "memory", name: "Last requests, in memory",
    size: taps.some((h) => ui.held[h] === undefined) ? GHOST.cell() : taps.some((h) => ui.held[h] === null) ? DRAW.unread.cell()
      : `<span class="dheld"><b>${fmt(heldN)}</b>${heldN === 1 ? "request" : "requests"} held</span>`,
    keep: `<span class="taps">${taps.map((h) => `<span class="tap">${cmdChip(h)}<span class="fixed">Last ${tapOf(h)}</span></span>`).join("")}</span>`,
    sw: `<span class="count">${taps.length} of ${ui.heads.length} commands</span><span class="marks">${KNOBS.markHtml("lock", "Set per command in splice.toml")}${KNOBS.markHtml("restart")}</span>`,
  }) : "";
  const reading = DATA_ROWS.some(stillReading), all = allBytes();
  const bytes = (g) => DATA_ROWS.filter((r) => r.g === g).reduce((s, r) => s + (heldOf(r)?.bytes ?? 0), 0);
  const unreadIn = (g) => DATA_ROWS.some((r) => r.g === g && heldOf(r) === null);
  // a group's size, and what share of everything kept it is, so the groups add up to the figure the topic leads with
  const sum = (g) => (DATA_ROWS.some((r) => r.g === g && stillReading(r)) ? GHOST.fig(4) : unreadIn(g) ? unreadable
    : `<span class="hn"><b>${esc(mbWord(bytes(g)))}</b></span>${!reading && all && !DATA_ROWS.some((r) => heldOf(r) === null) ? `<span class="gof">of ${esc(mbWord(all))}</span>` : ""}`);
  const legend = (g) => paneLegend(groupKnobs(g, !!memory));
  // conversation text is his prompts word for word, which is said once, on the group, instead of once per row
  const text = DRAW.section("Conversation text", dtable("text", "Conversation text", scale, memory ? memory.html : ""), { aside: `<span class="gaside"><span class="yours">Word for word</span>${sum("text")}${legend("text")}</span>` });
  const records = DRAW.section("Records", dtable("records", "Records", scale), { aside: `<span class="gaside">${sum("records")}${legend("records")}</span>`, cls: "drec" });
  // the two switches that are not a store: reading his conversations, and sending their reasoning back, each beside the door to where it is read
  return keptHtml() + text + records + knobSec("Claude Code conversations", ["transcriptView", "replayReasoning"]);
}

// ---------- what happened this week ----------
// Counted from the requests Usage and Requests read (GET /api/perf/turns, one read per command and word), never kept
// here: a count is the daemon's `count` for the filter a door opens on Requests, so the figure and the page it opens
// agree. Six words are tested on the daemon over every row (TurnsTest.kt), never over the newest 2,000 the route would send.
const ZONE = Intl.DateTimeFormat().resolvedOptions().timeZone;
// A TIME CARRIES ITS ZONE (Oct 26): he reads this page beside a terminal whose log lines are in UTC, so a bare "4:10 PM"
// on today's peak is a time he has to place. The browser's own short name for the zone it is in, said on the time.
const ZONE_WORD = new Intl.DateTimeFormat("en-US", { timeZoneName: "short" }).formatToParts(new Date()).find((p) => p.type === "timeZoneName")?.value ?? "";
const SEC = 1000, MIN = 60000;
const startOfToday = () => { const d = new Date(); d.setHours(0, 0, 0, 0); return +d; };
const reqUrl = (f) => `requests.html?${new URLSearchParams(f)}`;
const rowId = (head, row) => `${head}:${row.turn || row.turn_id || row.ts}`;
/** One figure's question of the daemon: the filter its word means and its floor in time. The daemon counts; no row is sent back
 *  but the newest, so a busy command's figure is its true count and never the size of a read. */
function askWeek(head, why, from) {
  const ask = why === "failed" ? { outcome: "failed" } : { ...WHY_ASKS[why] }, tier = idleTierOf(ks.cfg, head.key);
  if (ROW_TEST[why]) ask.test = ROW_TEST[why];
  if (why === "waited") { if (!Number.isFinite(tier)) return { head, why, err: "no stall setting answered" }; ask.silence_ms = String(Math.round(tier)); }
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
  // "allweek" and "allday" are asks with no filter at all: the requests of the window, which is the denominator every
  // share on this page is read against ("1,635 failed of 2,887 requests"). The daemon counts them in the same read.
  const plan = [["overloaded", day], ["cutoff", day], ["queued", day], ["allday", day], ["restarted", week], ["gaveup", week], ["waited", week], ["silentresume", week], ["silentover", week], ["allweek", week]];
  Promise.all(ui.headRows.map(readPeak)).then((peaks) => { ui.peaks = peaks; later(); });
  Promise.all(ui.headRows.map((h) => countsWeek(h, plan, week))).then((perHead) => {
    plan.forEach(([why]) => { ui.week[why] = perHead.map((m) => m.get(why)); });
    later();
  });
}
const weekReading = (...words) => words.some((why) => ui.week[why] === undefined);
// ---------- the one figure a topic opens on ----------
// A section answers with ONE figure, its denominator on the same baseline, and a door to exactly those requests where
// Requests can list exactly them (the Usage drill-down's hero). Every part is read from the daemon: while a part is
// still being read it is drawn as the ghost of its own shape, and where the daemon answers nothing the figure is not
// drawn at all. No stand-in figure.
const heroGhost = () => GHOST.hero();
/** A word's figure across every command: the total, or null when any command's read did not answer. */
const weekTotal = (why) => { const per = ui.week[why]; return per && per.every((p) => !p.err) ? per.reduce((s, p) => s + p.n, 0) : null; };
/** A command's own limit on requests at once, or the one every command uses; 0 is no limit. */
const limitOf = (head) => Number(ks.cfg?.layers?.perHead?.[head]?.maxInflight ?? val("maxInflight")) || 0;
/** How loud a share is: the limit red from [bad], the key light from [watch], the text's own ink below. */
const levelOf = (share, watch, bad) => (share >= bad ? "bad" : share >= watch ? "watch" : "ok");
/** Two parts of a topic side by side once the plate holds both whole, one over the other below that. The layout is the
 *  kit's balanced topic (DRAW.topic: console.css .topic): the figures keep the left column and the settings that act on
 *  them the right, and a section that belongs to neither goes to the shorter side. "lead" gives the first column the
 *  larger share, for a table beside a short list of settings. */
const pair = (left, right, cls = "") => DRAW.topic([{ html: left, col: 1 }, { html: right, col: 2 }], { lead: cls === "lead" });
const today = () => String(startOfToday());
/** One request's own row on Requests, in the week it lies in. */
const rowUrl = (head, row) => `requests.html?win=7d#${encodeURIComponent(rowId(head, row))}`;
/** A time in a column whose header says the zone once: the clock today, the date and the clock before today. */
const whenShort = (ms) => { const d = new Date(ms); return `${d.toDateString() === new Date().toDateString() ? "" : `${dateWord(d)}, `}${clock(d)}`; };
const zoneHead = (w) => `${w}${ZONE_WORD ? `, ${ZONE_WORD}` : ""}`;

// ---------- waits on one time axis ----------
// A section whose settings are waits draws them on ONE log axis, so "90 s" and "15 min" read as lengths on one line and
// the order they act in is seen rather than worked out: each row's track runs to the value every command uses, and a
// command holding its own value is a dot in its provider's colour at that value.
const HOUR = 60 * MIN;
const STOPS = [10, 30, 100, 300, SEC, 3 * SEC, 10 * SEC, 30 * SEC, MIN, 2 * MIN, 5 * MIN, 10 * MIN, 15 * MIN, 30 * MIN, HOUR, 2 * HOUR, 4 * HOUR, 8 * HOUR];
/** Every value [keys] hold in ms: the one every command uses and each command's own. */
const msOf = (keys) => keys.flatMap((key) => [Number(val(key)), ...KNOBS.ownersOf(key).map((c) => Number(KNOBS.ownValue(key, c)))]).filter((x) => Number.isFinite(x) && x > 0);
/** The axis from the round figure under the smallest value to the one past the largest. At most five labels, never
 *  closer than a fifth of the axis to each other, so no two touch on the narrowest pane; the last one always. */
function durAxis(values, word) {
  const xs = values.filter((x) => Number.isFinite(x) && x > 0);
  if (!xs.length) return null;
  const min = Math.min(...xs), max = Math.max(...xs);
  const lo = [...STOPS].reverse().find((s) => s < min) ?? STOPS[0];
  const hi = STOPS.find((s) => s >= max * 1.15) ?? STOPS.at(-1);
  const span = Math.log(hi) - Math.log(lo) || 1;
  const at = (x) => Math.max(0, Math.min(100, ((Math.log(Math.max(x, lo)) - Math.log(lo)) / span) * 100));
  const all = STOPS.filter((s) => s >= lo && s <= hi), step = Math.ceil(all.length / 5), ticks = [];
  for (let i = all.length - 1; i >= 0; i -= step) if (!ticks.length || at(ticks[0]) - at(all[i]) >= 18) ticks.unshift(all[i]);
  return { at, ticks, word };
}
/** One wait on the axis: the track to the value every command uses, and a dot for each command holding its own. */
function durDraw(key, ax) {
  const k = KNOB[key], v = Number(val(key));
  const own = KNOBS.ownersOf(key).map((c) => [c, Number(KNOBS.ownValue(key, c))]).filter(([, x]) => Number.isFinite(x) && x > 0);
  const name = (c) => ks.commandOf[c] || c;
  const said = `${word(k, v)}${own.length ? `; ${own.map(([c, x]) => `${name(c)} ${word(k, x)}`).join(", ")}` : ""}`;
  return `<span class="dtrk" role="img" aria-label="${esc(said)}">${v > 0 ? `<i class="dfill" style="width:${ax.at(v).toFixed(2)}%"></i>` : ""}`
    + own.map(([c, x]) => `<i class="dtk" style="left:${ax.at(x).toFixed(2)}%;--c:${colorOf(ks.providerOf[c])}" title="${esc(`${name(c)} · ${word(k, x)}`)}"></i>`).join("") + `</span>`;
}

// ---------- Stalled answers ----------
// WHAT SPLICE DID THIS WEEK WHEN A MODEL WENT QUIET, AS ONE TABLE ON ONE SCALE (Oct 27): the two ways a request ended
// early and the three ways it kept going, each a bar as long as its count against the largest and split by the
// commands it happened on, its count a door to exactly those requests, and its newest one a door to that request.
// Beside it, the waits that decide which of them happens, on one time axis, with the longest stall this week across them.
const STALL_GROUPS = [
  ["Ended early", [["restarted", WHY.restarted.word], ["gaveup", WHY.gaveup.word]]],
  ["Kept going", [["waited", WHY.waited.word], ["silentresume", "Resumed"], ["silentover", "Started over"]]],
];
const STALL_WHYS = STALL_GROUPS.flatMap(([, rows]) => rows.map(([why]) => why));
const otabHead = () => `<div class="orow oh" role="row"><span role="columnheader">Outcome</span><span role="columnheader">By command</span>`
  + `<span role="columnheader" class="r">Requests</span><span role="columnheader">Most on</span><span role="columnheader">${esc(zoneHead("Latest"))}</span></div>`;
const ogroup = (name) => `<div class="orow og" role="row"><span role="rowheader">${esc(name)}</span></div>`;
function silentHero() {
  if (weekReading("restarted", "gaveup", "allweek")) return heroGhost();
  const early = ["restarted", "gaveup"].map(weekTotal);
  if (early.includes(null)) return "";
  const n = early[0] + early[1], all = weekTotal("allweek");
  // the week is the section's own kicker, so the caption names the requests and not the window again
  if (all === 0) return DRAW.hero({ figure: "0", of: "requests" });
  return DRAW.hero(all
    ? { figure: DRAW.pct(n / all), level: levelOf(n / all, 0.02, 0.1), count: n, word: "ended early", of: `of ${DRAW.n(all)} requests` }
    : { figure: DRAW.n(n), level: n ? "bad" : "ok", of: "ended early" });
}
function stallRow(why, what, top, early) {
  const per = ui.week[why] ?? [], total = weekTotal(why), ask = { win: "7d", why };
  const cells = (cls, bar, count, cmds, last) => `<div class="orow${cls}" role="row"><span class="ow" role="cell">${esc(what)}</span>`
    + `<span class="obar" role="cell">${bar}</span><span class="on r" role="cell">${count}</span><span class="ocmds" role="cell">${cmds}</span><span class="olast" role="cell">${last}</span></div>`;
  if (total === null) {
    console.warn(`${what}: ${per.find((p) => p.err)?.err ?? "splice did not answer"}`);
    return cells(" unread", "", `<span class="state limit">Not read</span>`, "", "");
  }
  // an outcome that did not happen this week keeps its row as the empty track it is, with no door, where a sentence
  // under the table once named it
  if (total === 0) return cells(" none", "", `<span class="nz">0</span>`, "", "");
  const shares = per.filter((p) => !p.err && p.n).sort((a, b) => b.n - a.n), cmd = (p) => p.head.command;
  const segs = shares.map((p) => `<a class="oseg" href="${reqUrl({ ...ask, cmd: cmd(p) })}" style="flex-grow:${p.n};--c:${colorOf(ks.providerOf[p.head.key])}" title="${esc(`${cmd(p)} · ${fmt(p.n)}`)}"></a>`).join("");
  const bar = `<span class="ofill" style="width:${Math.max(2, (total / top) * 100).toFixed(2)}%">${segs}</span>`;
  const count = `<a${early ? ' class="lv-bad"' : ""} href="${reqUrl(ask)}">${fmt(total)}</a>`;
  const rest = shares.slice(3);
  const cmds = shares.slice(0, 3).map((p) => `<a class="cdoor" href="${reqUrl({ ...ask, cmd: cmd(p) })}">${cmdChip(p.head.key)}<b>${fmt(p.n)}</b></a>`).join("")
    + (rest.length ? `<span class="omore" title="${esc(rest.map((p) => `${cmd(p)} ${fmt(p.n)}`).join(", "))}">+${fmt(rest.length)}</span>` : "");
  const newest = shares.filter((p) => p.newest).sort((a, b) => b.newest.ts - a.newest.ts)[0];
  const last = newest ? `<a href="${rowUrl(newest.head.key, newest.newest)}">${esc(whenShort(newest.newest.ts))}</a>` : "";
  return cells("", bar, count, cmds, last);
}
// EVERY OUTCOME KEEPS ITS ROW (Oct 28): the ones that did not happen are not dropped into a sentence under the table
// ("None this week" and their names), they stand as rows with an empty track and a quiet 0, so the table is the same five
// rows every week and a week with nothing to report reads as short tracks, not as a line of words. A count that did not
// answer keeps its row too, with Not read.
function stallTable() {
  if (weekReading(...STALL_WHYS)) return stallGhost();
  const top = Math.max(1, ...STALL_WHYS.map((why) => weekTotal(why) ?? 0));
  const body = STALL_GROUPS.map(([group, rows], g) => ogroup(group) + rows.map(([why, what]) => stallRow(why, what, top, g === 0)).join("")).join("");
  return `<div class="otab" role="table" aria-label="Stalled answers this week">${otabHead()}${body}</div>`;
}
/** The table as it will land: its words are this page's own, its figures the ghosts of figures. */
const stallGhost = () => `<div class="otab" aria-busy="true">${otabHead()}${STALL_GROUPS.map(([group, rows]) => ogroup(group)
  + rows.map(([, what], i) => `<div class="orow" aria-hidden="true"><span class="ow">${esc(what)}</span><span class="obar">${GHOST.bar([12, 7, 16][i] ?? 8)}</span>`
    + `<span class="on r">${GHOST.fig(2.2)}</span><span class="ocmds">${GHOST.chip()}</span><span class="olast">${GHOST.bar(4.6)}</span></div>`).join("")).join("")}</div>`;
function silentWaits() {
  const keys = keysOf("silent");
  const best = (ui.week.waited || []).filter((p) => !p.err && p.longest && p.silence > 0).sort((a, b) => b.silence - a.silence)[0];
  const mark = best ? { ms: best.silence, href: rowUrl(best.head.key, best.longest), inner: `Longest stall <b>${esc(DRAW.ms(best.silence))}</b>${cmdChip(best.head.key)}` } : null;
  // the waits behind Advanced stand on the same axis, so the axis is drawn from them too and does not move when it opens
  const timed = [...keys, ...advKeys("silent")].filter((key) => KNOB[key].c.k === "dur");
  const ax = durAxis([...msOf(timed), ...(mark ? [mark.ms] : [])], "Time without progress");
  const draw = Object.fromEntries(timed.map((key) => [key, durDraw]));
  return knobSec("How long splice waits", keys, { ax, draw, mark, adv: "silent" });
}
const silentHtml = () => pair(DRAW.section("This week", `${silentHero()}${stallTable()}`), silentWaits());

// ---------- Many agents at once ----------
// TODAY, ONE ROW PER COMMAND ON SHARED SCALES (Oct 27): the most requests it held at once against its own limit, when
// in the day that was, how many requests it sent and how many waited in line, each figure a door to those requests.
// The settings that make those limits stand beside the table. What splice did when a provider pushed back stands under
// it, with no setting beside it: since 0.4.0 splice decides how often and how soon a failed request goes again from
// what failed, and the daemon refuses the four retry settings it used to take.
const LIMITS = ["maxInflight", "maxQueued", "maxRequestBytes"];
/** Where a moment of today falls on the day's track, in percent. */
const dayAt = (ms) => Math.max(0, Math.min(100, ((ms - startOfToday()) / DAY) * 100));
const DAY_TICKS = [[0, "12 AM"], [25, "6 AM"], [50, "12 PM"], [75, "6 PM"]];
// the Waited in line column stands only on a day when something waited: a column of noughts said one fact once per row
// THE LIMIT IS SAID ONCE WHEN EVERY ROW SHARES IT (Oct 27): "of 100" stood on every row of a table whose meters all
// ran to 100, so the column says it once in its head; a day when commands hold limits of their own says each on its row
const limitHead = (one) => (one === undefined ? "At once, of its limit" : one ? `At once, of ${DRAW.n(one)}` : "At once, no limit set");
const peakHead = (lined = true, one = undefined) => `<div class="pk ph" role="row"><span role="columnheader">Command</span><span role="columnheader" class="plh">${esc(limitHead(one))}</span>`
  + `<span role="columnheader" class="pdh">${esc(zoneHead("Busiest at"))}</span><span role="columnheader" class="r">Requests</span>${lined ? `<span role="columnheader" class="r">Waited in line</span>` : ""}</div>`;
/** How many of a command's requests waited in line today: the week's count where it answered, else the peak's own. */
const queuedOf = (p) => { const q = (ui.week.queued || []).find((x) => x.head.key === p.head.key); return q && !q.err ? q.n : p.queued || 0; };
function busyHero() {
  if (ui.peaks === undefined) return heroGhost();
  const rows = (ui.peaks || []).filter((p) => !p.err && p.peak > 0);
  if (!rows.length) return "";
  const share = (p) => { const lim = limitOf(p.head.key); return lim ? p.peak / lim : 0; };
  const top = [...rows].sort((a, b) => share(b) - share(a) || b.peak - a.peak)[0];
  const lim = limitOf(top.head.key), href = reqUrl({ from: today(), cmd: top.head.command });
  const tail = `${cmdChip(top.head.key)}<span class="hat">at ${esc(clock(new Date(top.at)))}${ZONE_WORD ? ` ${esc(ZONE_WORD)}` : ""}</span>`;
  return DRAW.hero(lim
    ? { figure: DRAW.pct(top.peak / lim), level: levelOf(top.peak / lim, 0.8, 1), count: top.peak, word: "at once", of: `of ${DRAW.n(lim)} allowed`, href, tail }
    : { figure: DRAW.n(top.peak), of: "at once, no limit set", href, tail });
}
function peakRow(p, maxN, now, lined, one = undefined) {
  const key = p.head.key, lim = limitOf(key), full = lim > 0 && p.peak >= lim, queued = queuedOf(p);
  const meter = lim ? DRAW.meter(Math.min(100, (p.peak / lim) * 100), full ? "full" : "") : "";
  return `<div class="pk" role="row" data-grow="peak-${esc(key)}" style="--c:${colorOf(ks.providerOf[key])}">`
    + `<span role="cell"><a class="pcmd" href="${reqUrl({ from: today(), cmd: p.head.command })}">${cmdChip(key)}</a></span>`
    + `<span class="pm" role="cell">${meter}</span>`
    + `<span class="pmv${full ? " lv-bad" : ""}" role="cell"><b>${fmt(p.peak)}</b>${one === undefined ? `<i>${lim ? `of ${fmt(lim)}` : "no limit"}</i>` : ""}</span>`
    + `<span class="pclk" role="cell">${esc(clock(new Date(p.at)))}</span>`
    + `<span class="pday" aria-hidden="true"><i class="pfut" style="left:${now.toFixed(2)}%"></i><i class="pdot" style="left:${dayAt(p.at).toFixed(2)}%"></i></span>`
    + `<span role="cell">${DRAW.barCell({ w: ((p.n || 0) / maxN) * 100, text: fmt(p.n || 0) })}</span>`
    // a command none of whose requests waited leaves its cell empty: the column's other doors say what waited
    + (lined ? `<span class="pq r" role="cell">${queued ? `<a class="pd-count" href="${reqUrl({ from: today(), why: "queued", cmd: p.head.command })}"><b>${fmt(queued)}</b>${DRAW.go}</a>` : ""}</span>` : "") + `</div>`;
}
// EVERY COMMAND KEEPS ITS ROW (Oct 28): the ones that ran nothing today were a line of chips under the table, names in a
// sentence. They stand in the table as rows with the empty meter they are (nothing held of the limit), under one
// "No requests" said once, the first few in view and the rest behind a count; a command whose peak did not answer
// stands the same way under its own word, the dashed track for what was not read.
const IDLE_SHOWN = 3;
function quietRow(p, now, lined, unread) {
  const key = p.head.key, lim = limitOf(key);
  return `<div class="pk quiet" role="row" data-grow="peak-${esc(key)}" style="--c:${colorOf(ks.providerOf[key])}">`
    + `<span role="cell"><a class="pcmd" href="${reqUrl({ from: today(), cmd: p.head.command })}">${cmdChip(key)}</a></span>`
    + `<span class="pm" role="cell">${unread ? DRAW.unread.track() : lim ? DRAW.meter(0) : ""}</span><span class="pmv"></span><span class="pclk"></span>`
    + `<span class="pday" aria-hidden="true">${unread ? "" : `<i class="pfut" style="left:${now.toFixed(2)}%"></i>`}</span><span></span>${lined ? "<span></span>" : ""}</div>`;
}
const quietHead = (word) => `<div class="pk pg" role="row"><span role="rowheader">${word}</span></div>`;
function peaksTable() {
  if (ui.peaks === undefined) return peaksGhost();
  if (!ui.peaks.length) return "";
  const rows = [...ui.peaks].sort((a, b) => (b.peak ?? -1) - (a.peak ?? -1));
  const busy = rows.filter((p) => !p.err && p.peak > 0), idle = rows.filter((p) => !p.err && !p.peak), bad = rows.filter((p) => p.err);
  const maxN = Math.max(1, ...busy.map((p) => p.n || 0)), now = dayAt(Date.now()), lined = busy.some((p) => queuedOf(p) > 0);
  const lims = new Set(busy.map((p) => limitOf(p.head.key))), one = lims.size === 1 ? [...lims][0] : undefined;
  const ticks = DAY_TICKS.map(([x, w]) => `<span class="pd-tk${x ? "" : " pd-t-first"}" style="left:${x}%">${w}</span>`).join("");
  const open = !!ui.pmore.idle, shown = open ? idle : idle.slice(0, capOf(idle.length, IDLE_SHOWN));
  keepShape("peakrows", busy.length + shown.length + bad.length);
  const more = idle.length > IDLE_SHOWN + 1 ? `<div class="pk pm2" role="row">${DRAW.more(idle.length - IDLE_SHOWN, { word: "more commands", act: "pmore", data: ` data-id="idle"`, pill: true, keepWord: true, open })}</div>` : "";
  const errs = [...new Set(bad.map((p) => p.err))].map((err) => `<div class="pk pg" role="row"><span class="state limit" role="rowheader">${esc(err)}</span></div>${bad.filter((p) => p.err === err).map((p) => quietRow(p, now, lined, true)).join("")}`).join("");
  // the Requests column's figures all take the room of the widest, so its bars are one scale and end on one line
  const vw = Math.max(1, ...busy.map((p) => fmt(p.n || 0).length));
  return `<div class="peaks${lined ? "" : " noq"}" role="table" aria-label="Today's busiest moment per command" style="--v-w:${vw}ch">${peakHead(lined, one)}${busy.map((p) => peakRow(p, maxN, now, lined, one)).join("")}`
    + `${busy.length ? `<div class="pk pt" aria-hidden="true"><span class="pdax pd-ax">${ticks}</span></div>` : ""}`
    + `${idle.length ? quietHead("No requests") + shown.map((p) => quietRow(p, now, lined, false)).join("") + more : ""}${errs}</div>`;
}
const peaksGhost = () => `<div class="peaks" aria-busy="true">${peakHead()}${Array.from({ length: Math.max(1, shapeOf("peakrows", 6)) }).map(() => `<div class="pk" aria-hidden="true">`
  + `<span>${GHOST.chip()}</span><span class="pm">${GHOST.track()}</span><span class="pmv">${GHOST.fig(1.2)}</span><span class="pclk">${GHOST.bar(3.6)}</span><span class="pday"></span>`
  + `<span>${GHOST.cell()}</span><span class="pq r">${GHOST.fig(1.4)}</span></div>`).join("")}</div>`;
/** How many waited in line today, as a door to them, on the section's kicker line. */
function queuedAside() {
  if (weekReading("queued")) return "";
  // where the count's own ask did not answer, every command's peak read counts its day's waits too: their sum is the
  // same count, and it is the one the table under this line shows, so the line never says Not read over figures
  const peaks = ui.peaks || [], summed = peaks.length && peaks.every((p) => !p.err && Number.isFinite(p.queued)) ? peaks.reduce((s, p) => s + p.queued, 0) : null;
  if (weekTotal("queued") === null && ui.peaks === undefined) return "";
  const q = weekTotal("queued") ?? summed;
  if (q === null) return `<span class="pd-count"><span class="state limit">Not read</span> waited in line</span>`;
  return q ? DRAW.countDoor(q, "waited in line today", reqUrl({ from: today(), why: "queued" })) : `<span class="hn"><b>0</b> waited in line</span>`;
}
/** A kind of pushback that came back zero is a figure on Today's kicker line beside the line that waited, and its section
 *  is not drawn: a zero is not a section. */
const pushZeros = () => (weekReading("overloaded", "cutoff") ? "" : [["overloaded", "overloaded"], ["cutoff", "cut off"]].filter(([why]) => weekTotal(why) === 0)
  .map(([, what]) => `<span class="hn"><b>0</b> ${what}</span>`).join(""));
const todayAside = () => { const x = `${queuedAside()}${pushZeros()}`; return x && `<span class="kside">${x}</span>`; };
/** What the providers pushed back with today: each kind's count against the day's requests as a door to them, then
 *  each command's share of it on one scale. A kind that came back zero, or unread, is one quiet line. */
function pushback() {
  if (weekReading("overloaded", "cutoff", "allday")) return `${GHOST.focal(16)}${GHOST.row([GHOST.chip(), GHOST.cell()])}`;
  const all = weekTotal("allday"), parts = [], quiet = [];
  for (const [why, what] of [["overloaded", "overloaded"], ["cutoff", "cut off"]]) {
    const per = ui.week[why] ?? [], total = weekTotal(why);
    if (total === null) {
      console.warn(`${what} today: ${per.find((p) => p.err)?.err ?? "splice did not answer"}`);
      quiet.push(`<span class="zw"><span class="state limit">Not read</span> ${esc(what)}</span>`);
      continue;
    }
    // a kind that did not happen is a figure on Today's kicker line (pushZeros), never a section of its own
    if (!total) continue;
    const shares = per.filter((p) => !p.err && p.n).sort((a, b) => b.n - a.n), top = shares[0]?.n || 1;
    parts.push(`<div class="pb"><a class="pd-sub pbfig" href="${reqUrl({ from: today(), why })}"><b>${fmt(total)}</b>${esc(what)}${all ? ` of ${DRAW.n(all)} requests` : ""}${DRAW.go}</a>`
      + `<div class="pbrows">${shares.map((p) => `<a class="pbrow" href="${reqUrl({ from: today(), why, cmd: p.head.command })}">${cmdChip(p.head.key)}${DRAW.barCell({ w: (p.n / top) * 100, text: fmt(p.n) })}</a>`).join("")}</div></div>`);
  }
  return parts.join("") + (quiet.length ? `<p class="zeros">${quiet.join('<span class="dot">·</span>')}</p>` : "");
}
// WHAT HAPPENED TODAY STANDS IN ONE COLUMN, THE LIMITS THAT SHAPED IT IN THE OTHER (Oct 27): what the providers pushed
// back with is part of today, so it sits under today's table, and a full-width section of two bars no longer ran a
// 6-request bar 1,700 px long across the plate at 3822.
// WHAT THE PROVIDERS PUSHED BACK WITH belongs to neither side, so it goes under whichever of the two is shorter (the kit's
// balanced topic); the limits are pinned to the right, in the DOM before it, so they take their side before it takes its.
// what the fill and the dots on a limit's track are, said once on the kicker line
const LIMIT_KEY = DRAW.legend([{ mark: `<i class="lg-fill"></i>`, word: "Now" }, { mark: `<i class="lg-dot"></i>`, word: "Busiest today" }]);
const busyHtml = () => {
  const refused = pushback();
  // whether the section stood is what the next first frame draws, since a quiet day has none
  if (!weekReading("overloaded", "cutoff", "allday")) keepShape("refused", refused ? 1 : 0);
  return DRAW.topic([
    { html: DRAW.section("Today", `${busyHero()}${peaksTable()}`, { aside: todayAside() }), col: 1 },
    { html: knobSec("splice's limits", LIMITS.filter((key) => keysOf("busy").includes(key)), { legend: LIMIT_KEY, adv: "busy" }), col: 2 },
    refused && DRAW.section("Providers today", refused),
  ].filter(Boolean), { lead: true });
};

// ---------- Reasoning ----------
// REASONING HAS NO FIGURE, SO IT OPENS ON THE THING ITSELF (Oct 26): nothing on the wire counts what a reasoning setting
// did, so this topic leads with the terminal it changes, drawn from the live values as shapes, never as sample words or
// numbers (Marcos, Oct 25: "no made-up data"). Each line a setting decides is named in the terminal's margin by that
// setting's own name, and pointing at the line or at the setting lights the other (settings.css), so a choice is read
// as the line it adds, moves or takes away. A line a setting takes away keeps its place, dashed, with the value that
// took it.
const SHOWS = ["showReasoning", "summary", "progressLine"];
const flagOn = (key) => val(key) === true || val(key) === "true";
function terminal() {
  const where = val("showReasoning"), sum = val("summary"), prog = flagOn("progressLine"), ver = val("verbosity");
  const n = where === "off" || sum === "none" ? 0 : sum === "detailed" ? 2 : 1;
  // a line a setting decides carries that setting's number, which stands on its row; a line the setting took away says what
  // took it. Answer length is in Advanced, so its number stands on the reply only while that row is open.
  const tie = (key, gone) => `<span class="tie"><b class="knum">${TIE_NO[key]}</b>${gone ? `<em>${esc(gone)}</em>` : ""}</span>`;
  const line = (cls, body, key = "", gone = "") => `<div class="tl ${cls}${gone ? " gone" : ""}"${key ? ` data-tie="${key}"` : ""}>`
    + `<span class="tx">${gone ? "" : body}</span>${key ? tie(key, gone) : "<span></span>"}</div>`;
  const wire = (w, cls = "") => `<i class="wire${cls ? ` ${cls}` : ""}" style="width:${w}%"></i>`;
  // the bullet the terminal prints before a block of its own; named apart from the rows' own mark (knobs.css .mk)
  const dot = `<b class="bul">⏺</b>`;
  // THREE KINDS OF LINE, THREE LOOKS: the model's thinking is hatched and quiet, the waiting line is dots, and the reply
  // is solid with the bullet that opens it. Reasoning In the reply is the reply's own text (solid, bulleted); in the
  // thinking block it is hatched, indented under Thinking and ruled off at the left as a block, so the two read apart
  // before a word is read. The reply's length follows how long answers run.
  const reasoning = n
    ? [62, 44].slice(0, n).map((w, i) => line(`rs ${where === "thinking" ? "in" : "out"}`, `${where === "text" && !i ? dot : ""}${wire(w, where === "thinking" ? "think" : "")}`, i ? "" : "summary")).join("")
    : line("rs", "", "summary", where === "off" ? word(KNOB.showReasoning, where) : word(KNOB.summary, sum));
  const said = `${where === "off" ? "Reasoning hidden" : `Reasoning ${word(KNOB.summary, sum).toLowerCase()}, ${word(KNOB.showReasoning, where).toLowerCase()}`}; waiting line ${prog ? "on" : "off"}`;
  const replyW = { low: 38, medium: 62, high: 92 }[ver] ?? 70;
  return `<div class="term${where === "thinking" ? " blocked" : ""}" role="img" aria-label="${esc(said)}"><div class="tbar" aria-hidden="true"><i></i><i></i><i></i></div>`
    + line("you", `<b class="pr">$</b>claude`)
    + line("think", `${dot}Thinking…`, "showReasoning", where === "off" ? word(KNOB.showReasoning, where) : "")
    + reasoning
    // "prog", never "wait": console.css's .wait is the spinning ring, and a terminal line wearing it turned in circles
    // and its words are "short" wires, never ".s", which is the session card's padded class
    + line("prog", `<i class="spin"></i>${wire(8, "short")}<b class="sep">·</b>${wire(6, "short")}<b class="sep">·</b>${wire(7, "short")}`, "progressLine", prog ? "" : word(KNOB.progressLine, false))
    + line("reply", `${dot}${wire(replyW)}`, advOpen("reasoning") ? "verbosity" : "")
    + `</div>`;
}
const termGhost = () => `<div class="term" aria-busy="true"><div class="tbar" aria-hidden="true"><i></i><i></i><i></i></div>`
  + [24, 40, 62, 44, 30, 70].map((w) => `<div class="tl" aria-hidden="true"><span class="tx">${GHOST.bar(w / 4)}</span><span></span></div>`).join("") + `</div>`;
const reasoningHtml = () => pair(DRAW.section("In your terminal", terminal()),
  knobSec("How reasoning shows", SHOWS.filter((key) => keysOf("reasoning").includes(key)), { adv: "reasoning" }));

// ---------- Plan limits ----------
// WHERE EACH LOGIN STANDS, IN ACCOUNTS' OWN WINDOW ROWS (Oct 27): the meter for how much is used, the percent, and the
// ring for how soon it refills, read from /api/accounts as Accounts reads them, with the warn line these settings set
// as a tick on every meter. Beside them the warn line's own row carries every login as a dot at its highest window,
// so the setting is read as a line among the figures it acts on rather than as a number in a box.
const PLAN_LEN = { "5 hours": 300, Week: 10080, Month: 43200 };
const warnPct = () => Number(val("usageWarnPct")) || 80;
/** A window as Accounts draws it (accounts.js windowOf): no minutes left once its reset has gone by. */
function planWindow(pct, resetSec, lenSec, slot, current) {
  if (pct == null) return null;
  const label = slot === "short" ? "5 hours" : lenSec >= 28 * 86400 ? "Month" : "Week";
  const left = resetSec ? Math.round((resetSec * 1000 - Date.now()) / 60000) : null, due = left != null && left >= 0;
  return { label, used: Math.round(pct), len: PLAN_LEN[label], left: due ? left : null, resetMs: due ? resetSec * 1000 : null,
    rolledMs: resetSec && !due ? resetSec * 1000 : null, stale: current === false };
}
const windowsOf = (x) => [planWindow(x.five_hour_used_percent, x.five_hour_reset_epoch_seconds, x.five_hour_window_seconds, "short", x.five_hour_current),
  planWindow(x.seven_day_used_percent, x.seven_day_reset_epoch_seconds, x.seven_day_window_seconds, "long", x.seven_day_current)].filter(Boolean);
const lenWord = (w) => ({ "5 hours": "5-hour", Week: "weekly", Month: "monthly" })[w.label];
/** Each login with a window, with its highest window still running (an ended window's figure is the ended one's),
 *  the highest first. */
const planLogins = () => (ui.accts || []).filter((x) => !signedOut(x) && windowsOf(x).length)
  .map((x) => ({ x, top: windowsOf(x).filter((w) => w.rolledMs == null).sort((a, b) => b.used - a.used)[0] ?? null }))
  .sort((a, b) => (b.top?.used ?? -1) - (a.top?.used ?? -1));
function planTop() {
  const best = planLogins().find((l) => l.top);
  return best ? { x: best.x, w: best.top, p: best.top.used } : null;
}
function planHero() {
  if (ui.accts === undefined) return heroGhost();
  const top = planTop();
  if (!top) return "";
  // the zone is said here, on the pane's first time, and the reset times on the cards below read in it
  const warn = warnPct(), back = top.w.resetMs ? `Resets ${backWord(new Date(top.w.resetMs))}${ZONE_WORD ? ` ${ZONE_WORD}` : ""}` : "";
  return DRAW.hero({
    figure: `${top.p}%`, level: top.p >= 100 ? "bad" : top.p >= warn ? "watch" : "ok", of: `used of its ${lenWord(top.w)} limit`, href: "accounts.html",
    under: loginWord(top.x), tail: top.p >= 100 ? DRAW.lock(back ? `Limit reached · ${back}` : "Limit reached") : back ? `<span class="hat">${esc(back)}</span>` : "",
  });
}
function planAside() {
  const logins = planLogins().filter((l) => l.top), warn = warnPct();
  if (!logins.length) return "";
  const over = logins.filter((l) => l.top.used >= warn).length;
  return `<span class="hn"><b>${fmt(over)}</b> of ${fmt(logins.length)} ${logins.length === 1 ? "login" : "logins"} at ${warn}% or more</span>`;
}
// A WINDOW IS A LABEL LINE OVER A METER THE CARD'S WHOLE WIDTH (Oct 28): the label, "100% used" and the reset stood in
// four fixed columns that took 28rem of a card, so at his width the meter was a sliver beside its percent. The line
// says which window and what is used and when it refills; the meter under it runs the card's width, the warn line a tick on it.
function planWin(w, warn) {
  const line = (pct, when) => `<div class="pl"><span class="label">${w.label}</span><span class="pr">${pct}${when}</span></div>`;
  if (w.rolledMs != null) return `<div class="pw">${line(`<span class="pct">${DRAW.dash}</span>`, `<span class="when rolled">Ended ${esc(backWord(new Date(w.rolledMs)))}</span>`)}${DRAW.meter(null, "gone")}</div>`;
  const full = w.used >= 100;
  return `<div class="pw">${line(`<span class="pct${full ? " limit" : ""}">${w.used}%<span class="used">used</span></span>`,
    w.left != null ? `<span class="when">${DRAW.ring(w.left / w.len)}Resets ${esc(backWord(new Date(w.resetMs)))}</span>` : "")}`
    + `<span class="wmeter">${DRAW.meter(Math.min(100, w.used), `${full ? "full" : ""}${w.stale ? " stale" : ""}`)}<i class="wmk" style="left:${warn}%"></i></span></div>`;
}
// A LOGIN'S CARD IS A DOOR TO ITS CARD ON ACCOUNTS (Oct 27): the act on a login at its limit is there (sign in again, move
// to another login), so each card carries the way in at its header's right end, one mark in the same place on every card.
// Accounts opens the card named by ?card=, and the kit spells that key once for the board and for every door to it
// (kit.js acctCardKey).
// six is a whole number of rows at two columns and at three, so the last row of cards is never one alone
const PLAN_SHOWN = 6;
function planCard(x, warn) {
  return `<article class="card plog" style="--c:${colorOf(familyOf(x.provider))}" data-grow="plan">`
    + `<header title="${esc(loginFull(x))}"><span class="blot"></span><span class="pln">${esc(loginWord(x))}</span>`
    + `<a class="icon plogdoor" href="accounts.html?card=${encodeURIComponent(acctCardKey(x))}" aria-label="${esc(`${loginWord(x)} on Accounts`)}" title="Accounts">${SV.open}</a></header>`
    + `<div class="windows">${windowsOf(x).map((w) => planWin(w, warn)).join("")}</div></article>`;
}
function planCards() {
  const a = ui.accts;
  if (a === undefined) return planGhost();
  if (!a) return `<p class="pidle"><span class="state limit">Not read</span></p>`;
  const warn = warnPct(), out = a.filter(signedOut);
  // THE LOGINS NEAREST THEIR LIMIT STAND AT FULL SIZE, THE REST BEHIND ONE COUNT (Oct 27): nine cards were two screens, and
  // the plans far from their limit are not what this topic is read for. They open in place, and the warn line's track beside
  // them still has every login as a dot.
  const logins = planLogins(), open = !!ui.pmore.plan, shown = open ? logins : logins.slice(0, capOf(logins.length, PLAN_SHOWN));
  const more = logins.length > PLAN_SHOWN + 1 ? DRAW.more(logins.length - PLAN_SHOWN, { word: "more logins", act: "pmore", data: ` data-id="plan"`, pill: true, keepWord: true, open }) : "";
  const cards = shown.map(({ x }) => planCard(x, warn));
  // a signed-out login's last reading is from before, so it stands as its chip under the state, never as windows
  // a signed-out login keeps the act it needs: its way to its card on Accounts, where it signs in
  const signed = out.length ? `<div class="psigned"><span class="state">Signed out</span>${out.map((x) => `<span class="psi">${loginChip(x)}<a class="act quiet small" href="accounts.html?card=${encodeURIComponent(acctCardKey(x))}">Sign in</a></span>`).join("")}</div>` : "";
  if (!cards.length) {
    // "No limits reported" is the providers answering with none; a login splice has never read is Not read, since this
    // page asks for no reading of its own (marlin, Oct 12)
    const never = a.some((x) => !x.refusal && x.credential_present !== false && !x.observed_at_epoch_seconds && !x.no_usage_at_epoch_seconds);
    return `<p class="pidle"><span class="state${never ? " limit" : " mute"}">${never ? "Not read" : "No limits reported"}</span></p>${signed}`;
  }
  return DRAW.cols(cards, PLOG_MIN, "plogs") + (more ? `<div class="pmoreline">${more}</div>` : "") + signed;
}
// A LOGIN'S CARD IS WIDE ENOUGH FOR ITS METER TO READ (Oct 27): the label, "100% used" and the reset take about 28rem
// of a card (settings.css .plog .windows), so a card of 35rem left its meter 7rem, a sliver beside its percent, wherever
// two cards stood in a row of the left half (his 2548 at 150%, and 1440). At 41rem the meter has 13rem at the least:
// two cards share a row where the half holds 84rem (3822), and a half narrower than that holds one card a row, its
// meter running most of the card's width.
const PLOG_MIN = 30;
const planGhost = () => DRAW.cols(Array.from({ length: Math.max(1, Math.min(PLAN_SHOWN, shapeOf("logins", 3))) }, (_, i) => i).map((i) => `<article class="card plog ghosted" style="--i:${i}" aria-hidden="true"><header>${GHOST.bar(8)}</header>`
  + `<div class="windows">${[0, 1].map(() => `<div class="pw"><div class="pl">${GHOST.bar(3.4)}<span class="pr"><span class="pct">${GHOST.fig(4.2)}</span><span class="when">${GHOST.ring()}${GHOST.bar(6.2)}</span></span></div>${GHOST.track()}</div>`).join("")}</div></article>`), PLOG_MIN, "plogs");
// ---------- a limit or a line, on a track with its scale ----------
// A LIMIT IS A TRACK TO ITS END, AND WHAT HOLDS IT RIGHT NOW IS ITS FILL (Oct 28): "Requests at once 100" and "Requests
// waiting in line 512" were numbers in boxes. Each command's gate answers what it runs and what waits this moment (GET
// /api/heads, gate.inflight and gate.queued, read again every few seconds), so the command nearest its own limit is the
// fill at the scale the limit sets, the dots on it are the commands' busiest moments today and the ends of the track carry
// their figures. The same track draws the warn line among the plans' dots. It is as thick as every meter on the console.
const WARN_LANES = 3, LANE_GAP = 4, MORE_GAP = 12;
/** Dots on one track, the highest first: each { p (0 to 100), c, title, full }. A dot takes the first lane where it
 *  clears the dot before it; the ones left over join a count on a lane of their own under their crowd. */
function laneDots(items) {
  const fronts = [], dots = [], more = [];
  for (const it of items) {
    const p = Math.max(0, Math.min(100, it.p)), lane = [...Array(WARN_LANES).keys()].find((i) => fronts[i] === undefined || fronts[i] - p >= LANE_GAP);
    if (lane !== undefined) { fronts[lane] = p; dots.push({ ...it, p, lane }); continue; }
    const m = more.at(-1);
    if (m && m.p - p < MORE_GAP) m.its.push(it); else more.push({ p, its: [it] });
  }
  const lanes = Math.max(1, ...dots.map((d) => d.lane + 1)) + (more.length ? 1 : 0);
  // only a dot at its limit wears the limit red ("atlimit", never "over", which is the kit's per-command line, .kn
  // .over, and gave the dot its padding and rule)
  const marks = dots.map((d) => `<i class="ldot${d.full ? " atlimit" : ""}" style="--at:${d.p};--lane:${d.lane};--c:${d.c}" title="${esc(d.title)}"></i>`)
    .concat(more.map(({ p, its }) => `<i class="lmore${p < 50 ? " lo" : ""}" style="--at:${p};--lane:${lanes - 1}" title="${esc(its.map((x) => x.title).join("\n"))}">+${fmt(its.length)}</i>`));
  return { lanes, marks: marks.join("") };
}
/** The warn line on a plan's whole, with every login a dot at its highest running window, in its provider's colour, and
 *  the scale named at its ends and at the line. */
function warnDraw() {
  if (ui.accts === undefined) return GHOST.track();
  const warn = warnPct();
  const all = planLogins().filter((y) => y.top).sort((a, b) => b.top.used - a.top.used);
  const name = (l) => `${loginWord(l.x)} · ${l.top.used}% of its ${lenWord(l.top)} limit`;
  const { lanes, marks } = laneDots(all.map((l) => ({ p: l.top.used, c: colorOf(familyOf(l.x.provider)), title: name(l), full: l.top.used >= 100 })));
  const said = `Warns at ${warn}%${all.length ? `; ${all.map((l) => `${loginWord(l.x)} ${l.top.used}%`).join(", ")}` : ""}`;
  return DRAW.scale({ zone: warn / 100, dots: marks, lanes, ticks: [[0, "0"], [warn / 100, `${warn}%`], [1, "100%"]], said });
}
const queueLimitOf = (head) => Number(ks.cfg?.layers?.perHead?.[head]?.maxQueued ?? val("maxQueued")) || 0;
/** What one field of every command's gate holds this moment, with the command nearest its own limit and its share. */
function gateNow(field, limit) {
  const rows = Object.entries(ui.gates || {}).filter(([, g]) => g).map(([head, g]) => ({ head, n: Number(g[field]) || 0, lim: limit(head) }));
  const share = (r) => (r.lim ? r.n / r.lim : 0);
  const top = [...rows].sort((a, b) => share(b) - share(a) || b.n - a.n)[0];
  return top ? { rows, top, share: share(top) } : null;
}
/** REQUESTS AT ONCE: what the busiest command runs now as the fill, and every command that ran today as a dot at its
 *  busiest moment, in its provider's colour; one holding a limit of its own stands at its share of its own. */
function inflightDraw() {
  const lim = Number(val("maxInflight")) || 0;
  if (!lim) return "";
  const now = gateNow("inflight", limitOf), peaks = ui.peaks;
  const busy = (peaks || []).filter((p) => !p.err && p.peak > 0 && limitOf(p.head.key) > 0)
    .map((p) => ({ p, lim: limitOf(p.head.key) })).sort((a, b) => b.p.peak / b.lim - a.p.peak / a.lim);
  const title = ({ p, lim: l }) => `${p.head.command} · ${fmt(p.peak)} of ${fmt(l)} at once, ${clock(new Date(p.at))}`;
  const { lanes, marks } = laneDots(busy.map((b) => ({ p: (b.p.peak / b.lim) * 100, c: colorOf(ks.providerOf[b.p.head.key]), title: title(b), full: b.p.peak >= b.lim })));
  const said = `${now ? `Now ${fmt(now.top.n)} of ${fmt(now.top.lim)} for ${ks.commandOf[now.top.head] || now.top.head}. ` : ""}Today: ${busy.length ? busy.map(title).join("; ") : "none"}`;
  return DRAW.scale({ end: fmt(lim), fill: now ? now.share : null, full: !!now && now.share >= 1, dots: marks, lanes: busy.length ? lanes : 1, now: now ? fmt(now.top.n) : "", said, unread: !now });
}
/** REQUESTS WAITING IN LINE: the longest line any command has this moment, against the room the line has. */
function queueDraw() {
  const lim = Number(val("maxQueued")) || 0;
  if (!lim) return "";
  const now = gateNow("queued", queueLimitOf);
  const said = now ? `Now ${fmt(now.top.n)} waiting of ${fmt(now.top.lim)} for ${ks.commandOf[now.top.head] || now.top.head}` : "Not read";
  return DRAW.scale({ end: fmt(lim), fill: now ? now.share : null, full: !!now && now.share >= 1, now: now ? fmt(now.top.n) : "", said, unread: !now });
}
/** THE LARGEST REQUEST has no reading of use on the wire, so the limit is drawn among the sizes it can be set to: the
 *  track runs to the largest choice, its fill to the value and each choice is a tick. */
function sizeDraw() {
  const k = KNOB.maxRequestBytes, v = Number(val("maxRequestBytes")) || 0, opts = k.c.opts.map(Number);
  const top = Math.max(v, ...opts);
  if (!v || !top) return "";
  const mb = (n) => `${+(n / KNOBS.MB).toFixed(1)}`;
  // the reading's column is kept, empty: no gate counts the size of a request in flight, and the track ends where the two above it end
  return DRAW.scale({ fill: v / top, ticks: [[0, "0"], ...opts.map((o) => [o / top, mb(o)])].map(([p, w], i, all) => [p, i === all.length - 1 ? `${w} MB` : w]), said: `${mb(v)} MB, of the choices up to ${mb(top)} MB` })
    + `<b class="lnow" aria-hidden="true"></b>`;
}
const planHtml = () => pair(DRAW.section("Right now", `${planHero()}${planCards()}`, { aside: planAside() }), knobSec("When splice warns", keysOf("plan"), { adv: "plan" }));

// ---------- MCP servers ----------
// GET /api/mcp is a SNAPSHOT, not a count over a window: the host keeps no tally of tool calls, of how long one took,
// or of how many servers an idle sweep stopped (McpStatus.kt, HostedServers.kt), so this pane says what is true now
// and never a week figure it would have to invent. Each server's own line is what the settings above it act on.
// The daemon says WHY a server is not shared as a stable key (McpSharing.kt, McpInventory.kt) with the value it names;
// this table is the only place those keys become words. A key it does not know is grouped as "No reason given", never a guess.
const MCP_STATE = {
  cwd: { word: "Per session", line: "Starts in its own folder" },
  variable: { word: "Per session", line: "A different value in each session" },
  relative_path: { word: "Per project", line: "A different folder in each project" },
  location_flag: { word: "Per project", line: "A different place in each project" },
  roots: { word: "Per project", line: "Asks which project it's in" },
  directory: { word: "Per project", line: "One project's folder" },
  stateful: { word: "Keeps state", line: "Keeps one session's work" },
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
  // A FILE IS NAMED, NEVER SPELLED AS A PATH (Oct 26): his own .claude.json, or the config folder a copy sits in when it
  // is not his home's (.claude-grok); the whole path stays on the line's title
  const parts = homeWord(e.source_file).split("/"), name = parts.pop() || "", dir = parts.at(-1) || "";
  return name === ".claude.json" && dir && dir !== "~" ? dir : name;
};
// a project's or a repo's own entry names it by the folder it sits in, so a row shows that name and not the config path
const projectOf = (e) => (e?.kind === "project" || e?.kind === "repo" ? String(e.scope || "").split("/").filter(Boolean).pop() ?? "" : "");
// a web server's address, without the part of a URL that can carry a credential: the host and path, never a query or a login
const addressOf = (v) => {
  try { const u = new URL(v); return u.host + (u.pathname === "/" ? "" : u.pathname); } catch { return ""; }
};
// EVERY VALUE SHOWN AS CODE IS READ AS AN ADDRESS FIRST (Oct 26): a server's deciding value can be its URL, whose query
// carries a key. One that parses as a web address shows its host and path only, as the shared row's address does.
const codeOf = (v) => (/^(https?|wss?):\/\//i.test(String(v || "")) ? addressOf(v) : homeWord(v));
// where a server is declared, under its name: its project's chip, else its file or plugin, and a web server's address
// a server the files never declared (the daemon planned it from elsewhere) has no line here: a file glyph naming nothing
// read as a file with no name (temple, Per project, at 3822 on Oct 26)
// HIS OWN .claude.json IS WHERE A SERVER IS DECLARED UNLESS SAID OTHERWISE, so no row says it: the same file name under
// every server of a list is the one qualifier of a page said on every row. A row names its place only where it is
// somewhere else (a project, a plugin, a command's own copy), and a web server its address, which is what tells it apart.
const OWN_FILE = ".claude.json";
const placeOf = (s) => s.project || (s.from && s.from !== OWN_FILE ? s.from : "");
const fromHtml = (s) => {
  const place = s.project ? `<span class="chip">${esc(s.project)}</span>` : placeOf(s) ? `<span class="ft">${esc(s.from)}</span>` : "";
  if (!place && !s.address) return "";
  return `<span class="from" title="${esc(s.path || s.from)}">${place ? SV.file : ""}${place}${s.address ? `<code>${esc(s.address)}</code>` : ""}</span>`;
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
    return { name, from: fromWord(at), path: at?.source_file ? homeWord(at.source_file) : "", project, shared, web, address: web ? addressOf(value) : "", state, value,
      cmd: state === "added_in" ? (entries.length && value && why === "added_in" ? value : copyOf(copy?.source_file)) : null,
      cmds: live?.commands || [], stale, last: live?.last_activity || 0, err: live?.last_error || "", started: live?.started_at || 0,
      // the one process splice runs for it now, what it holds read from /proc, and the sessions on it (McpStatus.kt hosted)
      hosted: !!live?.hosted, rss: live?.rss_bytes ?? null, sessions: live?.sessions || 0 };
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
/** The switch, on the Sharing section's own kicker line: it is what the figure under it answers to. */
function shareSwitch() {
  if (!ui.topo) return ""; // an unread splice.toml draws no switch: one tap would write over what it never read
  const on = shareOn();
  const sw = `<span class="switch${ui.shook === "share" ? " failed" : ""}" role="group" aria-label="Share servers between sessions">${[[true, "Shared"], [false, "One per session"]].map(([v, w]) =>
    `<button data-act="mshare" data-v="${v}" aria-pressed="${v === on}"${ui.shareSaving ? " disabled" : ""}>${w}</button>`).join("")}</span>`;
  const said = ui.shareSaving ? `<span class="said">${ICON.wait}</span>` : ui.shareSaid ? `<span class="said ${ui.shareSaid.bad ? "limit" : "ok"}">${esc(ui.shareSaid.word)}</span>` : "";
  return `<span class="mshare">${sw}${said}</span>`;
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
    shake("share");
    return;
  }
  ui.shareSaid = { word: r.body.restart_required ? "After a restart" : "New sessions" };
  await rereadMcp();
}
/** Share, or stop sharing, one server: Don't share adds it to [daemon] mcp_hosting_exclude, Share takes it off. */
const exAct = (name, shared) => {
  const said = ui.exSaid[name], busy = said === "saving";
  const word = said && !busy ? `<span class="said ${said.bad ? "limit" : "ok"}">${esc(said.word)}</span>` : "";
  const shook = said?.bad && ui.shook === `ex:${name}` ? " shook" : "";
  return `${word}<button class="act quiet small${shook}" data-act="mexclude" data-name="${esc(name)}" data-v="${shared}"${busy ? " disabled" : ""}>${shared ? "Don't share" : "Share"}</button>`;
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
    shake(`ex:${name}`);
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
// is off. Every other server keeps one copy per session either way and is named with its reason.
const shareable = (s) => s.class === "shared" || s.why === "per_session_choice";
// one server reads one answer on this page: where the list keeps a server unshared for a reason the switch does not
// change, a project's server the hub does not host yet among them, the census counts it with the ones kept one per
// session, under that reason and out of what sharing saves (marlin, desk m, Oct 12)
const asListed = (s, rows) => {
  const r = rows.get(s.label ?? s.name);
  return r && !r.shared && MCP_STATE[r.state] && r.state !== "per_session_choice" ? { ...s, class: "per_session", why: r.state, value: r.value } : s;
};
const copiesWord = (n) => `${fmt(n)} ${n === 1 ? "copy" : "copies"}`;
// the sessions holding a server now, its own copy or the hub's, each a door to it on Sessions (spec §8: ownership visible
// per session). A session with no name reads as Sessions titles it, once /api/sessions has answered for it
const HELD_SHOWN = 4;
const heldName = (h) => h.name || ui.snames?.get(h.session_id) || "Unnamed session";
/** Titles for the holding sessions that carry no name, read only when one of them has none. */
async function nameHolders() {
  const held = (ui.mmem?.servers || []).flatMap((s) => s.held_by || []);
  if (!held.some((h) => !h.name)) return;
  const r = await API.get("/api/sessions");
  if (!r.ok) { whyNot("/api/sessions", r); return; }
  ui.snames = new Map((r.body?.sessions || []).map((x) => [x.session_id, x.name || x.title]));
  later();
}
/** What the machine-wide census (GET /api/mcp/memory) holds for one server, summed over its entries: one name can be
 *  declared by several plugins, and each is measured on its own. Null while the census has nothing under that name. */
function censusOf(name) {
  const xs = (ui.mmem?.servers || []).filter((c) => (c.label ?? c.name) === name);
  return xs.length ? { copies: xs.reduce((n, c) => n + (c.copies || 0), 0), now: xs.reduce((n, c) => n + (c.bytes_now || 0), 0), held: xs.flatMap((c) => c.held_by || []), web: xs.some((c) => c.web) } : null;
}
// the cause in his words: the daemon's sentence ends in the part he can act on, and the whole of it stays on the title
const shortWhy = (t) => String(t || "").split(/:\s+/).filter(Boolean).pop() || "";
/** The sessions on a server now: by name where the census names them (four, then the rest as a count), else how many. */
function heldHtml(s, c) {
  const held = c?.held || [];
  if (held.length) {
    return held.slice(0, HELD_SHOWN).map((h) => DRAW.chip({ text: heldName(h), live: true, href: `sessions.html#${encodeURIComponent(h.session_id)}`, cls: "sess", title: heldName(h) })).join("")
      + (held.length > HELD_SHOWN ? `<span class="more">+${fmt(held.length - HELD_SHOWN)}</span>` : "");
  }
  return s.sessions ? `<span class="nsess"><b>${fmt(s.sessions)}</b> ${s.sessions === 1 ? "session" : "sessions"}</span>` : "";
}
// THE LAMP IS THE SERVER'S STATE (the kit's tlamp): the live trace while splice runs its one copy, the flat line in the
// limit red when it last failed, and an unlit socket for one nothing runs now (a web address has no process here)
const lampOf = (s) => DRAW.lamp(s.err ? "stalled" : s.hosted ? "working" : "");
// THE COLUMNS ARE NAMED ONCE, over the rows (Oct 26): "Used by" and "Last call" on every row said the same two words
// three times over three servers. A time is a dash where the daemon has none to give, as a web server never has.
const USES_SHOWN = 4;
// A LIST'S TAIL IS NEVER ONE (Oct 26): a pill that hides one item is as big as the item, so a list one over its cap
// shows whole, and a pill only ever stands for two or more
const capOf = (n, cap) => (n > cap + 1 ? cap : n);
// WHAT A SERVER IS WHEN NOTHING RUNS IT HERE IS A MARK, NOT A WORD ON EVERY ROW (Oct 27): "At a web address" and "Not
// running" read on twenty-one rows of one section, one qualifier said twenty-one times. A globe is a server that lives at
// a web address, an unlit socket one nothing runs now, and the section's line says each once (idleKey).
const idleWord = { web: "At a web address", off: "Not running" };
/** "web" or "off" for a server with no copy running; null where copies run (the census's web, else its value's scheme). */
function idleKind(s) {
  const c = censusOf(s.name);
  if (c?.copies) return null;
  return c?.web || /^(https?|wss?):\/\//i.test(String(s.value || "")) ? "web" : "off";
}
const idleMark = (web) => `<span class="idle ${web ? "web" : "off"}" role="img" aria-label="${idleWord[web ? "web" : "off"]}" title="${idleWord[web ? "web" : "off"]}">${web ? SV.web : SV.off}</span>`;
/** The key to the marks the rows under it carry, for the kinds that appear. */
const idleKey = (kinds) => (kinds.size ? `<span class="legend idlekey">${["web", "off"].filter((k) => kinds.has(k)).map((k) => `<span class="lg">${k === "web" ? SV.web : SV.off}${idleWord[k]}</span>`).join("")}</span>` : "");
const SRV_HEAD = `<div class="srvhead"><span></span><span></span><span>Used by</span><span>Memory</span><span>${esc(zoneHead("Started"))}</span><span>${esc(zoneHead("Last call"))}</span><span></span></div>`;
/** What a shared server's one copy holds now: the census's reading of every copy on the machine, else /proc's for the one
 *  splice runs. A web address runs no process here and has no figure. */
const copyBytes = (s) => { if (s.web) return null; const c = censusOf(s.name); return c?.copies ? c.now : s.hosted && s.rss ? s.rss : null; };
function sharedRow(s, top = 1) {
  const c = censusOf(s.name);
  // THE COMMANDS ON A SERVER ARE A BOUNDED LIST (Oct 26): eleven chips ran the row past every other column. The first
  // four, then the rest behind one pill; a command whose own copy of the config is older joins at its next session,
  // which rewrites the copy (fin, Oct 12), and those are one count that opens to their chips in place
  const usesOpen = !!ui.mmore[`uses:${s.name}`], soonOpen = !!ui.mmore[`soon:${s.name}`];
  const cmds = (usesOpen ? s.cmds : s.cmds.slice(0, capOf(s.cmds.length, USES_SHOWN))).map((x) => cmdChip(x)).join("")
    + (s.cmds.length > USES_SHOWN + 1 ? DRAW.more(s.cmds.length - USES_SHOWN, { act: "mmore", data: ` data-id="uses:${esc(s.name)}"`, open: usesOpen, pill: !usesOpen }) : "");
  // the ones joining at their next session show by name, under that word, while the row has room for them beside the
  // ones using it now; more than that are one count
  const soonFits = Math.min(s.cmds.length, USES_SHOWN) + s.stale.length <= USES_SHOWN + 1;
  const soon = !s.stale.length ? "" : soonOpen || soonFits
    ? `<span class="next">Next session</span>${s.stale.map((x) => cmdChip(x).replace("class=\"chip\"", "class=\"chip soon\"")).join("")}${soonFits ? "" : DRAW.more(s.stale.length, { act: "mmore", data: ` data-id="soon:${esc(s.name)}"`, open: true })}`
    : DRAW.more(s.stale.length, { word: "join next session", act: "mmore", data: ` data-id="soon:${esc(s.name)}"` });
  const used = `${cmds}${soon}${heldHtml(s, c)}`;
  // what its one copy holds now is a bar on the list's one scale (the largest copy is the full track), so the server that
  // holds the most is read before a figure is; the census's reading is the same one What sharing saves draws. A web
  // address runs no process here, so its cell is the dash every figure without a reading is.
  const bytes = copyBytes(s);
  const mem = s.web ? DRAW.dash : bytes != null ? `${DRAW.barCell({ w: (bytes / top) * 100, text: esc(mbWord(bytes)) })}${c?.copies > 1 ? `<span class="n">${copiesWord(c.copies)}</span>` : ""}`
    : s.hosted ? `<span class="state">Running</span>` : idleMark(false);
  const when = (ms) => (ms ? esc(whenShort(ms)) : DRAW.dash);
  const err = s.err ? `<span class="serr" title="${esc(s.err)}">${esc(shortWhy(s.err))}</span>` : "";
  const test = `${testSaid(s.name)}<button class="act quiet small" data-act="mtest" data-name="${esc(s.name)}"${ui.mtest[s.name] === "running" ? " disabled" : ""}>Test</button>`;
  return `<div class="srv${s.err ? " err" : ""}">${lampOf(s)}<div class="sname"><span class="nm">${hl(s.name)}</span>${fromHtml(s)}${err}</div>`
    + `<span class="fig uses">${used}</span>${s.web ? `<span class="fig none">${DRAW.dash}</span>` : `<span class="fig mem">${mem}</span><span class="fig t">${when(s.started)}</span><span class="fig t">${when(s.last)}</span>`}`
    + `<span class="acts">${test}${exAct(s.name, true)}</span></div>`;
}
// THE REASON IS SAID ONCE, ON ITS GROUP (Oct 26): fifteen rows each reading "Plugin-owned" said one word fifteen times.
// Each reason is a card with its count; under it, each server with the value that decides it and where it is declared.
const MCP_SHOWN = 4;
const outWord = (s) => MCP_STATE[s.state]?.word ?? (s.state === "hosting_disabled" ? "Sharing off" : "No reason given");
function outGroups(rows) {
  const groups = new Map();
  for (const s of rows.filter((r) => !r.shared)) groups.set(outWord(s), [...(groups.get(outWord(s)) ?? []), s]);
  return [...groups].sort((a, b) => b[1].length - a[1].length || a[0].localeCompare(b[0]));
}
function outItem(s, lines, own = true) {
  const m = MCP_STATE[s.state], c = censusOf(s.name);
  const cmd = m?.cmd && s.cmd ? cmdChip(s.cmd) : "";
  const code = m && s.value && s.state !== "added_in" ? `<code>${esc(codeOf(s.value))}</code>` : "";
  const why = cmd || code || (lines && m?.line) ? `<div class="why">${cmd || code ? `<span class="w">${cmd}${code}</span>` : ""}${lines && m?.line ? `<span class="wl">${esc(m.line)}</span>` : ""}</div>` : "";
  // what its copies hold now, one per session; a server with no copy is a mark that says why nothing of it is here:
  // nothing runs it now, or it lives at a web address (idleKind), the two marks named once on the section's line
  const kind = idleKind(s);
  const mem = c?.copies ? `<span class="fig mem"><b>${esc(mbWord(c.now))}</b> <span>${copiesWord(c.copies)}</span>${heldHtml(s, c)}</span>`
    : `<span class="fig mem">${idleMark(kind === "web")}</span>`;
  const acts = s.state === "excluded" ? `<span class="acts">${exAct(s.name, false)}</span>` : "";
  return `<div class="srv out"><div class="sname"><span class="nm">${hl(s.name)}</span>${own ? fromHtml(s) : ""}</div>${why}${mem}${acts}</div>`;
}
function outCard([word, xs]) {
  // a reason whose servers all carry the same line says it once, under the reason, instead of on every server
  const lines = [...new Set(xs.map((s) => MCP_STATE[s.state]?.line || ""))], once = lines.length === 1 ? lines[0] : "";
  // SERVERS DECLARED IN ONE PLACE ARE UNDER IT ONCE: a plugin or a project that declares two or more of a card's servers
  // is a line of its own, its servers under it without it, where four rows each read the same plugin's name
  const at = placeOf;
  const runs = new Map(); for (const s of xs) runs.set(at(s), (runs.get(at(s)) ?? 0) + 1);
  const sorted = [...xs].sort((a, b) => (runs.get(at(b)) ?? 0) - (runs.get(at(a)) ?? 0) || at(a).localeCompare(at(b)) || a.name.localeCompare(b.name));
  const open = !!ui.mmore[word], shown = open ? sorted : sorted.slice(0, capOf(sorted.length, MCP_SHOWN));
  const more = xs.length > MCP_SHOWN + 1 ? DRAW.more(xs.length - MCP_SHOWN, { act: "mmore", data: ` data-id="${esc(word)}"`, pill: true, keepWord: true, open }) : "";
  const items = shown.map((s, i) => {
    const grouped = at(s) && runs.get(at(s)) > 1;
    const head = grouped && (i === 0 || at(shown[i - 1]) !== at(s)) ? `<div class="mfrom">${fromHtml(s).replace('class="from"', 'class="from mf"')}</div>` : "";
    return head + outItem(s, !once, !grouped);
  }).join("");
  return `<article class="mcard"><header class="mtop"><span class="mw">${esc(word)}</span><b class="mn">${fmt(xs.length)}</b></header>`
    + `${once ? `<p class="ml">${esc(once)}</p>` : ""}<div class="srvs out">${items}</div>${more}</article>`;
}
const SAVE_SHOWN = 6;
// the saving's figure, then rows of a name and two bars with the copies between them, as the servers land
const saveGhost = () => `${GHOST.row([GHOST.fig(6), GHOST.bar(9)])}<div class="ghost-rows">${[0, 1, 2].map(() => GHOST.row([GHOST.bar(8), GHOST.track(), GHOST.bar(4), GHOST.track()])).join("")}</div>`;
/** What sharing saves, from the census: every copy of every server sharing would serve, against one copy of each. */
function savedHtml() {
  const m = ui.mmem;
  const aside = m?.claude_processes ? `<span class="hn"><b>${fmt(m.claude_processes)}</b> Claude Code processes</span>` : "";
  if (m === undefined) return DRAW.section("What sharing saves", saveGhost());
  if (!m) return DRAW.section("What sharing saves", `<div class="pd-sub"><b>${DRAW.dash}</b>${unreadable}</div>`);
  // each server carries its class from the host's own sharing plan, unless the list keeps it unshared (asListed)
  const byName = new Map((ui.mcp ? mcpServerRows(ui.mcp) : []).map((r) => [r.name, r]));
  const share = (m.servers || []).map((s) => asListed(s, byName)).filter((s) => shareable(s) && s.copies);
  if (!share.length) return DRAW.section("What sharing saves", DRAW.nothing("No copies running"), { aside });
  const now = share.reduce((n, s) => n + s.bytes_now, 0), one = share.reduce((n, s) => n + s.bytes_if_shared, 0);
  const copies = share.reduce((n, s) => n + s.copies, 0);
  // the census measures what runs now, so the saving it can show is what one copy of each would free from it: once each
  // runs one copy there is nothing more to free, which is sharing working, never "sharing saves nothing" (Oct 26)
  const saves = now - one > 0 ? DRAW.focal(mbWord(now - one), "sharing would free") : DRAW.focal(mbWord(0), "sharing would free");
  // EACH SERVER ON ONE SCALE (Oct 27): what its copies hold now beside what one copy of it would, so the server worth
  // sharing is the longest pair of bars and the total is their sum on the last line. The biggest six, the rest in place.
  const rows = [...share].sort((a, b) => b.bytes_now - a.bytes_now || a.name.localeCompare(b.name));
  const top = Math.max(...rows.map((s) => Math.max(s.bytes_now, s.bytes_if_shared)), 1);
  const open = !!ui.mmore["save"], shown = open ? rows : rows.slice(0, capOf(rows.length, SAVE_SHOWN));
  // ONE BAR A SERVER, ON ONE SCALE: its whole length is what its copies hold now, the solid part is the one copy sharing would
  // keep, and the hatched part after it is what sharing frees, so the server worth sharing is the one with the longest gap
  const fig = (b) => `<span class="fig">${esc(mbWord(b))}</span>`;
  const gapBar = (s) => {
    const one = Math.min(s.bytes_if_shared, s.bytes_now), x = ((one / top) * 100).toFixed(2), w = (((s.bytes_now - one) / top) * 100).toFixed(2);
    return `<span class="dtrk save" role="img" aria-label="${esc(`${mbWord(s.bytes_now)} now; one copy would hold ${mbWord(s.bytes_if_shared)}`)}"><i class="dfill" data-tw="" data-w="${x}" style="width:${x}%"></i>${s.bytes_now > one ? `<i class="sgap" style="left:${x}%;width:${w}%"></i>` : ""}</span>`;
  };
  const line = (s) => `<div class="msrv"><span class="nm">${esc(s.label ?? s.name)}</span>${gapBar(s)}${fig(s.bytes_now)}<span class="n">${copiesWord(s.copies)}</span>${fig(s.bytes_if_shared)}</div>`;
  const more = rows.length > SAVE_SHOWN + 1 ? DRAW.more(rows.length - SAVE_SHOWN, { act: "mmore", data: ` data-id="save"`, pill: true, keepWord: true, open }) : "";
  const sum = rows.length > 1 ? `<div class="msrv sum"><span class="nm">${fmt(rows.length)} servers</span><span></span>${fig(now)}<span class="n">${copiesWord(copies)}</span>${fig(one)}</div>` : "";
  const head = `<div class="msrv head"><span></span><span class="hl"><i class="sw one"></i>Stays<i class="sw gap"></i>Freed</span><span>Now</span><span></span><span>One copy</span></div>`;
  return DRAW.section("What sharing saves", `${saves}<div class="msave">${head}${shown.map(line).join("")}${more}${sum}</div>`, { aside });
}
// the server timers in the order they bite: how many may run, how long one may take to start, to answer, to sit unused
const MCP_ORDER = ["mcpMaxServers", "mcpInitializeTimeoutMs", "mcpRequestTimeoutMs", "mcpIdleTimeoutMs"];
const mcpKeys = () => { const keys = keysOf("mcp"); return [...MCP_ORDER.filter((k) => keys.includes(k)), ...keys.filter((k) => !MCP_ORDER.includes(k))]; };
// the reasons land as cards, so their place is held by two cards of their shape: a name and its count, then servers
// four reasons stand on a row at his width, each as tall as its servers make it: the ghost is four cards, each as tall as a typical machine's
const mcardsGhost = () => DRAW.masonry([0, 1, 2, 3].map(() => `<article class="mcard" aria-hidden="true"><header class="mtop">${GHOST.bar(8)}${GHOST.fig(1.6)}</header>${GHOST.lines([9, 12, 8, 11, 9, 12])}</article>`), 26, { cls: "mcards", gap: "var(--gap-card)" });
// the shared list's rows as the grid they land on: lamp, name, users, memory bar, two times and the two acts
const srvGhost = () => `<div class="srvs" aria-hidden="true">${SRV_HEAD}${[0, 1, 2].map(() => `<div class="srv"><i class="ghost ring lampg"></i><div class="sname">${GHOST.bar(8)}${GHOST.bar(6)}</div>`
  + `<span class="fig uses">${GHOST.chip()}${GHOST.chip()}</span><span class="fig mem">${GHOST.cell()}</span><span class="fig t">${GHOST.bar(6)}</span><span class="fig t">${GHOST.bar(6)}</span>`
  + `<span class="acts">${GHOST.bar(3.4, "chip")}${GHOST.bar(6, "chip")}</span></div>`).join("")}</div>`;
/** The topic: how many servers are shared and why the rest are not, the shared ones, the rest by reason, what sharing
 *  saves, and the timers. Each part draws its own shape while its read is out. */
function mcpHtml() {
  const m = ui.mcp, aside = shareSwitch();
  const keys = mcpKeys(), timed = keys.filter((k) => KNOB[k]?.c?.k === "dur");
  // the timers read as lengths on one log axis, in the order they bite; how many may run stays its own row
  const limits = knobSec("Server limits", keys, { ax: durAxis(msOf(timed), "How long"), draw: Object.fromEntries(timed.map((k) => [k, durDraw])) });
  if (m === undefined) {
    return DRAW.section("Sharing", `${heroGhost()}${GHOST.stack()}`, { aside, cls: "tally" })
      + DRAW.topic([{ html: DRAW.section("Shared", srvGhost()), col: 1 }, { html: savedHtml(), col: 2 }, { html: DRAW.section("Not shared", mcardsGhost()), col: 1 }, { html: limits, col: 2 }], { lead: true });
  }
  // the route is mounted only while splice hosts MCP (ControlServer.kt), so its 404 is the honest "nothing is hosted"
  if (m === false) return DRAW.section("Sharing", DRAW.nothing("splice runs no MCP servers"), { aside, cls: "tally" }) + savedHtml() + limits;
  if (!m) return DRAW.section("Sharing", `<div class="pd-top"><b class="pd-rate">${DRAW.dash}</b><span class="pd-failed"><span>servers shared</span></span>${unreadable}</div>`, { aside, cls: "tally" }) + savedHtml() + limits;
  const rows = mcpServerRows(m), shared = rows.filter((r) => r.shared), groups = outGroups(rows);
  const hosted = shared.filter((r) => !r.web).length, web = shared.length - hosted;
  // one server is written in several files (a head's copy of the canonical home, a repo's own entry), so the figure is
  // how many SERVERS he set up, and the entries splice read are said once, at the end of the bar's names
  const entries = (m.sources?.servers || []).length;
  const tail = entries > rows.length ? `<span class="pd-lb quiet"><b>${fmt(entries)}</b> entries in your files fold into <b>${fmt(rows.length)}</b> servers</span>` : "";
  const parts = [
    { n: hosted, word: "Hosted by splice", text: fmt(hosted), cls: "ok" },
    { n: web, word: "At a web address", text: fmt(web), cls: "ok" },
    ...groups.map(([w, xs]) => ({ n: xs.length, word: w, text: fmt(xs.length), cls: "rest" })),
  ];
  const top = rows.length
    ? DRAW.hero({ figure: fmt(shared.length), of: `of ${fmt(rows.length)} servers shared` }) + splitBar(parts, rows.length, { id: "mcp", tail })
    : DRAW.nothing("No MCP servers set up");
  const up = shared.filter((r) => r.hosted), sessions = up.reduce((n, r) => n + r.sessions, 0);
  const upAside = up.length ? `<span class="hn"><b>${fmt(up.length)}</b> running${sessions ? ` · <b>${fmt(sessions)}</b> ${sessions === 1 ? "session" : "sessions"}` : ""}</span>` : "";
  // memory on one scale: the largest copy on the list is the full track
  const topBytes = Math.max(1, ...shared.map((s) => copyBytes(s) ?? 0));
  const sharedKinds = new Set(shared.filter((s) => !s.web && !s.hosted && copyBytes(s) == null).map(() => "off"));
  const list = shared.length ? DRAW.section("Shared", `<div class="srvs${m.hosting === false ? " dim" : ""}">${SRV_HEAD}${shared.map((s) => sharedRow(s, topBytes)).join("")}</div>`, { aside: upAside && sharedKinds.size ? `<span class="kside">${upAside}${idleKey(sharedKinds)}</span>` : upAside + idleKey(sharedKinds) }) : "";
  // the two marks a server with nothing running wears are named once, on the line the cards stand under
  const out = groups.length ? DRAW.section("Not shared", DRAW.masonry(groups.map(outCard), 26, { cls: "mcards", gap: "var(--gap-card)" }), { aside: idleKey(new Set(groups.flatMap(([, xs]) => xs.map(idleKind)).filter(Boolean))) }) : "";
  // what runs stands beside what sharing saves, and the servers left out beside the limits that bound them: the plate holds two columns, each a stack of sections
  return DRAW.section("Sharing", top, { aside, cls: "tally" })
    + DRAW.topic([{ html: list, col: 1 }, { html: savedHtml(), col: 2 }, { html: out, col: 1 }, { html: limits, col: 2 }], { lead: true });
}

// ---------- Version ----------
// What this daemon runs, where the newest is, and the way back, from GET /api/upgrade. The route never fetches, so the
// newest is a version only when a check has succeeded here; otherwise it is the releases page, one tap away. Go back is
// POST /api/upgrade {rollback}.
//
// ONE CARD PER VERSION THERE IS, AND NO CARD FOR A VERSION THERE IS NOT (splice-lead, Oct 11): Running always has a
// number; Previous joins it only when a release is on this machine to go back to, and Newest only when a check found a
// version he does not run. THEY STAND IN THE ORDER THEY CAME OUT (Oct 26), the one he runs at the hero's size between
// them, so which way each act moves him is read from where it sits before its word is.
const RELEASES = "https://github.com/torad-labs/splice/releases";
// ONE ROUTE, TWO DIRECTIONS: POST /api/upgrade takes him to a release or back to one, and the run that follows is the
// same run. Only the words differ, so the header says which way it went rather than "Going back to" over an upgrade.
const RUN_WORD = {
  up: { running: "Upgrading to", succeeded: "Now on", failed: "Not on", lost: "Not on" },
  back: { running: "Going back to", succeeded: "Back on", failed: "Not back on", lost: "Not back on" },
};
const vnode = (label, cls, body) => `<article class="vcard${cls}"><i class="vsock" aria-hidden="true"></i><span class="vk">${esc(label)}</span>${body}</article>`;
const vnum = (s) => `<b class="v">${esc(s)}</b>`;
// the version each card holds is on the card, so its act says only what it does: "Go back", named in full for a reader
// that hears the button alone; armed, the question names both versions, since that is the moment the two are weighed
const goAct = (id, word, to, going) => `<button class="act quiet" data-act="arm" data-id="${id}" aria-label="${esc(`${word} to ${to}`)}"${going ? " disabled" : ""}>${esc(word)}</button>`;
const askAct = (act, word, to, from) => `<button class="act primary" data-act="${act}">${esc(`${word} to ${to}`)}</button><button class="act quiet" data-act="disarm">Stay on ${esc(from)}</button>`;
function versionHtml() {
  const v = ui.ver;
  if (v === undefined) {
    const ghost = (w) => `<div class="vghost" aria-hidden="true">${GHOST.bar(5)}${GHOST.fig(w)}${GHOST.bar(9)}</div>`;
    // the files come with the settings, which have answered by now, so they stand while the versions are read
    return pair(DRAW.section("Versions", `<div class="vline">${ghost(5)}${ghost(8)}</div>`), filesHtml(), "lead");
  }
  // the word the Version job's own rail line uses; why is warned where it was read (fin)
  if (!v) return pair(DRAW.section("Versions", `<div class="vline">${vnode("Running", " now", `<b class="v">${DRAW.dash}</b>${unreadable}`)}</div>`), filesHtml(), "lead");
  const back = v.rollback_target, run = ui.run, going = run && run.state === "running";
  // "Not checked" sits where "Checked Oct 12, 4:10 AM CDT" would, in the same small line, so the version stays the
  // largest thing on the card (Marlin with hitstop, Oct 12)
  const checked = `<p class="kel">${v.checked_at_epoch_millis ? `Checked ${esc(whenWord(v.checked_at_epoch_millis))}` : "Not checked"}</p>`;
  // A STATE AND AN ACT, NOT A SENTENCE (Oct 12): "Not read" when a tap answered nothing, "Newest"
  // when he is already on the newest. A newer version is a version there is, so it takes a card of its own.
  const news = v.latest && v.latest !== v.installed;
  const newest = news ? "" : v.latest ? `<span class="state">Newest</span>` : v.checked_at_epoch_millis ? `<span class="state limit">Not read</span>` : "";
  // ONLY HIS TAP READS THE NETWORK (Marlin's privacy line, Oct 12): nothing here polls, and the page never checks when
  // it opens. GET /api/upgrade answers the snapshot a past tap left, and POST /api/upgrade/check is the only fetch.
  const act = ui.armed === "restart" ? "" : `<button class="act quiet${ui.shook === "check" ? " shook" : ""}" data-act="check"${ui.checking || going ? " disabled" : ""}>${ui.checking ? ICON.wait : ""}Check</button>`
    + (ui.checkSaid ? `<span class="said limit">${esc(ui.checkSaid)}</span>` : "");
  const now = vnode("Running", " now", `<div class="vtop">${vnum(v.installed)}${newest}</div>${checked}<div class="vacts">${act}${restartAct(going)}</div>`);
  // Previous is a card only when there is a release to go back to, or when the read that would have found one failed:
  // "None" under a name is not news. A failed read keeps the cause he can act on; the daemon's sentence is its title.
  const prev = back ? vnode("Previous", " prev", `${vnum(back)}<div class="vacts">${ui.armed === "rollback" ? askAct("rollback", "Go back", back, v.installed) : goAct("rollback", "Go back", back, going)}</div>`)
    : v.rollback_unavailable_reason ? vnode("Previous", " prev", `<b class="v">${DRAW.dash}</b><span class="state limit">Not read</span><p class="vwhy" title="${esc(v.rollback_unavailable_reason)}">${esc(shortWhy(v.rollback_unavailable_reason))}</p>`) : "";
  // UPGRADE IS THE SAME ARMING AS GO BACK, on the card that holds the version it would take him to. The releases page is
  // the door for reading the notes before he arms; armed, the card holds the question and nothing else.
  const next = news ? vnode("Newest", " next", `${vnum(v.latest)}<div class="vacts">${ui.armed === "upgrade" ? askAct("upgrade", "Upgrade", v.latest, v.installed)
    : `${goAct("upgrade", "Upgrade", v.latest, going)}<a class="act quiet" href="${RELEASES}" target="_blank" rel="noopener">${SV.open}Releases</a>`}</div>`) : "";
  return pair(DRAW.section("Versions", `<div class="vline">${prev}${now}${next}</div>${runHtml(run, back)}`), filesHtml(), "lead");
}
// WHERE SPLICE KEEPS ITS FILES, shown and never edited (v0.4.0 section 9, Resolved: computed paths are inspectable, not
// editable). The daemon resolved them (GET /api/config paths), so an older daemon without the field draws nothing. Each
// reads from his home as ~, the way he would type it; the whole path is its title.
function filesHtml() {
  const p = ks.cfg?.paths;
  if (!p) return "";
  const line = (name, path) => (path ? `<div class="vfile" role="row"><span class="nm">${esc(name)}</span><code title="${esc(path)}">${esc(homeWord(path))}</code></div>` : "");
  const rows = `${line("Settings file", p.config)}${line("State", p.state)}${line("Logs", p.logs)}`;
  return rows ? DRAW.section("Files", `<div class="vfiles">${rows}</div>`) : "";
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
  return `<section class="run${failed ? " bad" : ""}"><header><h3>${words[run.state] ?? words.running} ${esc(run.to ?? back ?? "")}</h3></header>${steps}${door}</section>`;
}
/** Restart splice, beside the version it is running. With nothing in flight it is one press: splice is built to come
 *  back from a restart. With requests in flight it arms the way Remove does on Models (Marlin, Oct 12): the first press
 *  names the agents mid-request, each a chip with the Working lamp that opens its session, says how long the drain lets
 *  them finish before it cuts them (the daemon's own cap, GET /api/daemon/restart's drain_ms, never a number written
 *  here), and the second press restarts. Check steps aside while it is armed. What a restart waits for is said on the button, because a daemon that waits on a compaction looks
 *  the same as one that hung. */
function restartAct(going) {
  if (ui.armed === "restart" && !ui.restarting) {
    const then = ui.drainMs ? `<span class="said then">· ${KNOBS.dur(ui.drainMs)} to finish, then stopped</span>` : "";
    return armedAsk("Mid-request", ui.cut || [], '<button class="act danger" data-act="restart-now">Restart splice</button>',
      '<button class="act quiet" data-act="disarm">Keep running</button>', then);
  }
  const wait = ui.restartWait;
  const word = !ui.restarting ? "Restart splice"
    : wait ? `Waiting for ${fmt(wait)} ${wait === 1 ? "compaction" : "compactions"}`
    : "Restarting";
  return `<button class="act quiet${ui.shook === "restart" ? " shook" : ""}" data-act="restart"${ui.restarting || ui.restartArming || going ? " disabled" : ""}>${ui.restarting ? ICON.wait : ""}${word}</button>`
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
    render();
    return;
  }
  ui.checkSaid = r.status === 409 ? "Already checking" : r.status === 429 ? "Checked a moment ago" : "Not read";
  if (r.body?.error) console.warn(`upgrade check: ${r.body.error}`);
  shake("check");
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
    ui.restartSaid = r.status === 409 ? "Already restarting" : r.status === 503 ? "Nothing is set to start it again" : "Not restarted";
    if (r.body?.error) console.warn(`restart: ${r.body.error}`);
    shake("restart");
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

// ---------- Projects ----------
// WHAT A PROJECT IS, AND WHAT HE HAS: the projects splice groups his sessions by (GET /api/projects), drawn first, then
// the folders under which each child folder is a project of its own. With none saved, a project is the git repo, which
// is the rule in force and what the rail says. The folder list is drawn here rather than by the kit because a list of
// paths is not one of its controls: each path carries the way to drop it, the projects it makes, and whether it is
// still on disk. The value is the kit's (PATCH /api/config projectsFolders, colon-separated), so a change reads
// "Applied" in the same place every other setting does.
const FOLDER_SEP = ":";
const foldersNow = () => String(val("projectsFolders") || "").split(FOLDER_SEP).map((s) => s.trim()).filter(Boolean);
/** What the daemon says about each saved folder: whether it is on disk, and the projects it makes. */
const folderFacts = () => {
  const rows = ui.projects?.folders || [];
  const by = Object.fromEntries(rows.map((f) => [f.path, f]));
  return foldersNow().map((path) => ({
    path,
    found: by[path] ? by[path].found !== false : null, // null while /api/projects has not answered
    makes: (ui.projects?.projects || []).filter((p) => p.folder === path && (p.name || p.label)),
  }));
};
// A PROJECT IS AN OBJECT HE CAN OPEN (Oct 27): each one is a door to its sessions on Sessions, with how many sessions it
// holds drawn on one scale and when it was last active, newest first, so the projects he works in lead and the rest
// follow. Sixteen, then the rest behind one pill that opens them in place. The counts come from one read of
// /api/sessions made the first time the topic is drawn; until it lands each row holds a bar's place.
const PROJECTS_SHOWN = 16;
const atMs = (v) => (typeof v === "number" ? v : Date.parse(v) || 0);
/** Sessions per project id, and the newest update among them, read once from /api/sessions. */
async function readProjectSessions() {
  ui.psess = null;
  const r = await API.get("/api/sessions");
  if (!r.ok) { whyNot("/api/sessions", r); ui.psess = false; later(); return; }
  const by = new Map();
  for (const x of r.body?.sessions || []) {
    const id = x.project?.id;
    if (!id) continue;
    const t = atMs(x.updated_at), had = by.get(id);
    by.set(id, had ? { n: had.n + 1, last: Math.max(had.last, t) } : { n: 1, last: t });
  }
  ui.psess = by;
  later();
}
const projectName = (p) => p.name || p.label || "";
/** Projects as doors to their sessions, the most recent first, each with its session count on the list's one scale. */
function projectChips(list, id, hueOf = () => null) {
  if (ui.psess === undefined) readProjectSessions();
  const facts = ui.psess instanceof Map ? ui.psess : null;
  const of = (p) => facts?.get(p.id) ?? { n: 0, last: 0 };
  const rows = [...list].filter((p) => projectName(p)).sort((a, b) => of(b).last - of(a).last || projectName(a).localeCompare(projectName(b)));
  const top = Math.max(1, ...rows.map((p) => of(p).n));
  // a list one over the cap shows whole, as a server's commands do (capOf): a "+1" pill is as big as the name it hides
  const open = !!ui.pmore[id], shown = open ? rows : rows.slice(0, capOf(rows.length, PROJECTS_SHOWN));
  const more = rows.length > PROJECTS_SHOWN + 1 ? DRAW.more(rows.length - PROJECTS_SHOWN, { act: "pmore", data: ` data-id="${esc(id)}"`, open, pill: !open }) : "";
  const line = (p) => {
    const x = of(p);
    const n = facts ? DRAW.barCell({ w: (x.n / top) * 100, text: x.n ? `${fmt(x.n)}` : DRAW.dash }) : ui.psess === false ? `<span class="barcell">${DRAW.dash}</span>` : GHOST.bar(8);
    const when = facts && x.last ? esc(whenShort(x.last)) : facts || ui.psess === false ? DRAW.dash : "";
    const hue = hueOf(p);
    // WHEN IT WAS LAST ACTIVE IS A DOT ON ONE LOG AXIS OF AGE (a year back to now, the Keep for axis read the other way), so the projects he works in are the dots at the right
    const rec = facts && x.last ? `<span class="rec" aria-hidden="true"><i style="left:${(100 - keepAt(Math.max(0, (Date.now() - x.last) / DAY))).toFixed(2)}%"></i></span>` : facts || ui.psess === false ? `<span class="rec" aria-hidden="true"></span>` : "";
    return `<a class="prj" href="sessions.html?project=${encodeURIComponent(p.id)}" title="${esc(p.root || p.id)}"><span class="nm">${hue ? `<i class="psw" style="--c:${hue}" aria-hidden="true"></i>` : ""}${hl(projectName(p))}</span>${n}<span class="t">${rec}<span class="tw">${when}</span></span></a>`;
  };
  const head = `<div class="prj head"><span></span><span>Sessions</span><span class="trh"><span class="rec ax"><b>1 yr</b><b>Now</b></span><span class="tw">${esc(zoneHead("Last active"))}</span></span></div>`;
  return `<div class="prjs">${head}${shown.map(line).join("")}</div>${more}`;
}
/** The projects there are, as the topic's figure, and where they come from: one bar split by the folder that makes
 *  each (and the repos no folder holds), only where there is more than one source to split. */
function projectsTop(p, folders) {
  const all = p.projects || [];
  if (!all.length) return DRAW.nothing(folders.length ? "No projects in these folders yet" : "No git repos seen yet");
  const from = all.filter((x) => x.folder), repos = all.filter((x) => !x.folder);
  // the rule that makes them is said on the folder side, where it is changed
  const hero = DRAW.hero({ figure: fmt(all.length), of: folders.length ? `projects from ${fmt(folders.length)} ${folders.length === 1 ? "folder" : "folders"}` : "projects" });
  const parts = [...folders.map((f, i) => ({ n: f.makes.length, word: f.path, text: fmt(f.makes.length), ink: storeInk(i) })),
    ...(from.length && repos.length ? [{ n: repos.length, word: "Git repos", text: fmt(repos.length), cls: "rest" }] : [])];
  const split = parts.filter((x) => x.n > 0).length > 1 ? splitBar(parts, all.length, { id: "projects" }) : "";
  // every project is one table, newest first, each wearing the colour its folder has on the bar above and on the folder's own line
  const hue = (x) => { const i = folders.findIndex((f) => f.path === x.folder); return i < 0 ? null : storeInk(i); };
  return `${hero}${split}<div class="pmakes pall">${projectChips(all, "all", hue)}</div>`;
}
function pathRow(f) {
  const armed = ui.armed === `folder:${f.path}`;
  // REMOVAL ARMS IN PLACE (Oct 26): the × turns into the act it would take and the way out, on the path it removes
  const drop = armed
    ? `<span class="armed"><button class="act danger small" data-act="dropfolder" data-path="${esc(f.path)}">Remove</button><button class="act quiet small" data-act="disarm">Keep</button></span>`
    : `<button class="icon" data-act="armfolder" data-path="${esc(f.path)}" aria-label="Remove ${esc(f.path)}">${ICON.close}</button>`;
  // a folder that has vanished still holds its place: splice never stops over it, and its sessions fall back to repos
  const gone = f.found === false ? `<span class="state limit">Not found</span>` : "";
  // how many projects it makes, beside it: the projects themselves are in the table, in this folder's colour
  // the folder's own projects are one press away, under it, in the order the table above has them
  const fold = !!ui.pmore[`fold:${f.path}`];
  const makes = f.found === null ? GHOST.bar(6) : f.makes.length
    ? `<button class="count pn" data-act="pmore" data-id="fold:${esc(f.path)}" aria-expanded="${fold}"><span><b>${fmt(f.makes.length)}</b> ${f.makes.length === 1 ? "project" : "projects"}</span>${ICON.caret}</button>` : "";
  const i = foldersNow().indexOf(f.path);
  return `<div class="pfolder"><div class="prow${armed ? " arming" : ""}"><i class="psw" style="--c:${storeInk(i)}" aria-hidden="true"></i><span class="ppath"><code>${hl(f.path)}</code>${drop}</span>${gone}${makes}</div>`
    + `${fold && f.makes.length ? `<div class="pmakes pfold">${projectChips(f.makes, `list:${f.path}`)}</div>` : ""}</div>`;
}
/** The add tile where the next path lands; pressed, it turns into its own form in place, and a refused path shakes it. */
function addFolder() {
  if (ui.addPath === null) return DRAW.addTile({ word: "Add a folder", act: "addfolder", cls: "padd" });
  const bad = ui.bad.projectsFolders;
  return `<div class="add signing padd${bad && ui.shook === "projects" ? " failed" : ""}" role="group" aria-label="Add a folder">`
    + `<span class="plus" aria-hidden="true">+</span><input class="field wide" id="kf-projects-add" data-pfield value="${esc(ui.addPath)}" placeholder="~/code" aria-label="Projects folder">`
    + `<button class="act primary small" data-act="savefolder">Save</button><button class="act quiet small" data-act="cancelfolder">Cancel</button>`
    + (bad ? `<span class="said limit">${esc(bad)}</span>` : "") + `</div>`;
}
// the projects' rows before they are read, as the grid they land on: a name, a bar's track and a time, as many as the
// last read held (sixteen at most, the rest being one pill)
const prjGhost = () => `<div class="prjs" aria-hidden="true"><div class="prj head"><span></span><span>Sessions</span><span class="trh"><span class="rec ax"><b>1 yr</b><b>Now</b></span><span class="tw">${esc(zoneHead("Last active"))}</span></span></div>`
  + `${Array.from({ length: Math.max(1, Math.min(PROJECTS_SHOWN, shapeOf("projects", 8))) }, () => `<div class="prj"><span class="nm">${GHOST.bar(8)}</span>${GHOST.cell()}<span class="t">${GHOST.bar(5)}</span></div>`).join("")}</div>`;
function projectsHtml() {
  const k = KNOB.projectsFolders, folders = folderFacts(), p = ui.projects;
  const top = p === undefined ? `${heroGhost()}<div class="pmakes">${prjGhost()}</div>`
    : p === null ? `<div class="pd-top"><b class="pd-rate">${DRAW.dash}</b><span class="pd-failed"><span>projects</span></span>${unreadable}</div>`
    : projectsTop(p, folders);
  // the folders are one setting: its name is the section's kicker, lit by a search the way a setting's name is
  // with no folder saved, the rule in force is said where a folder would change it
  // the rule that makes a project of every git repo no folder holds is a line of the same list, with how many it made
  const repoN = p ? (p.projects || []).filter((x) => !x.folder).length : null;
  const rule = `<div class="prow rule"><i class="psw rest" aria-hidden="true"></i><span class="prule">One project per git repo</span>${repoN == null ? "" : `<span class="pn"><b>${fmt(repoN)}</b> ${repoN === 1 ? "project" : "projects"}</span>`}</div>`;
  const list = `<div class="plist">${rule}${folders.map(pathRow).join("")}${addFolder()}</div>`;
  // the projects and the folders that make them are one pair: side by side once the plate holds two columns
  return pair(DRAW.section("Projects", top, { cls: "tally" }),
    `<section class="psec pfolders${lit(knobHay(k))}"><div class="sechead"><h3 class="kicker" data-row="${k.key}">${hl(k.label)}</h3>${saidHtml(k.key)}</div>${list}</section>`, "lead");
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
  // A REFUSAL IS SAID ONCE, WHERE HE ACTED (Oct 26): an added path is refused on its own form, so the kicker keeps no
  // second word for it, and it reads "Not found" only when the daemon turned the path down; with no answer it was
  // never weighed, and reads "Not saved". A removal has no form, so its "Not saved" stays on the kicker.
  if (was === null) ui.bad.projectsFolders = "Not saved";
  else { ui.bad.projectsFolders = r.reason ? "Not found" : "Not saved"; delete ks.bad.projectsFolders; }
  shake("projects");
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
  reasoning: reasoningHtml,
  plan: planHtml,
  mcp: mcpHtml,
  projects: projectsHtml,
  data: dataHtml,
  version: versionHtml,
};

// ---------- the rail ----------
// A TOPIC THAT HOLDS A LIMIT HIT OR A FAILURE SAYS SO ON ITS CARD, AND OPENS FIRST (Oct 28): the limit red is for limits and
// failures, so a card wears it on its edge when its topic holds one (a plan at its limit, a command held at its own ceiling,
// requests a provider turned away, a week of answers that ended early past a tenth), and the page opens on the first such
// topic once its reads have landed, unless he has already chosen one or came by a link that names one.
function badTopic(id) {
  if (id === "silent") {
    if (weekReading("restarted", "gaveup", "allweek")) return false;
    const early = ["restarted", "gaveup"].map(weekTotal), all = weekTotal("allweek");
    return !early.includes(null) && all > 0 && (early[0] + early[1]) / all >= 0.1;
  }
  if (id === "busy") {
    const over = weekTotal("overloaded");
    return (over ?? 0) > 0 || (ui.peaks || []).some((p) => !p.err && limitOf(p.head.key) > 0 && p.peak >= limitOf(p.head.key));
  }
  if (id === "plan") { const t = planTop(); return !!t && t.p >= 100; }
  return false;
}
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
  if (id === "mcp") return [ui.mcp === undefined ? "Reading" : ui.mcp === false ? "Not hosted" : !ui.mcp ? "Not read" : `${word(KNOB.mcpMaxServers, val("mcpMaxServers"))} at most`];
  // with nothing saved the rail says the rule in force, which is the one a first-time user is already under
  if (id === "projects") { const f = foldersNow(); return f.length ? f : ["One per repo"]; }
  if (id === "busy" && weekTotal("overloaded") !== null && weekTotal("queued") !== null) return [`${fmt(weekTotal("overloaded"))} overloaded today`, `${fmt(weekTotal("queued"))} waited in line`];
  if (id === "busy") return [`${word(KNOB.maxInflight, val("maxInflight"))} at once`, `${word(KNOB.maxQueued, val("maxQueued"))} waiting`];
  if (DATA_ROWS.some(stillReading)) return ["Reading"];
  const h = DATA_ROWS.map(heldOf), stop = traceStopped();
  return h.some((x) => x === null) ? ["Not read"] : [...(stop ? [`Saving stopped ${stop.when}`] : []), `${mbWord(h.reduce((s, x) => s + x.bytes, 0))} kept`];
}
// ---------- what a rail card draws ----------
// A CARD SHOWS THE STATE, NOT THE SETTING (Marcos, Oct 25: "show don't tell"): the rail's job is to say which topic
// needs him, and a sentence does that only after it is read. Where this page already holds a figure, the card draws
// it on the scale it belongs to — a share of a limit as a meter, two outcomes on one scale, a failure in the limit
// red. Where the fact is a choice and not a quantity (how reasoning shows), the choice itself is the state and stays
// in words. Nothing is invented: a card whose read has not landed draws the ghost of its own shape.
// A figure that did not come back is a dash beside the limit red's "Not read", as every unread figure on the page is.
const RAIL_UNREAD = `<p class="rfig"><b>${DRAW.dash}</b><span class="state limit">Not read</span></p>`;
function railDraw(id) {
  if (id === "silent") {
    if (weekReading("restarted", "gaveup", "waited")) return railGhost(id);
    const early = ["restarted", "gaveup"].map(weekTotal), waited = weekTotal("waited");
    if (early.includes(null) || waited === null) return "";
    const n = early[0] + early[1], top = Math.max(n, waited, 1);
    return `<div class="rmini">${DRAW.miniPair({ label: "Ended early", figure: DRAW.n(n), w: (n / top) * 100, cls: "row", level: n ? "bad" : "" })}`
      + `${DRAW.miniPair({ label: "Waited out", figure: DRAW.n(waited), w: (waited / top) * 100, cls: "row" })}</div>`;
  }
  if (id === "busy") {
    if (ui.peaks === undefined) return railGhost(id);
    const rows = (ui.peaks || []).filter((p) => !p.err && p.peak > 0);
    const over = weekTotal("overloaded");
    const share = (p) => { const lim = limitOf(p.head.key); return lim ? p.peak / lim : 0; };
    const top = [...rows].sort((a, b) => share(b) - share(a) || b.peak - a.peak)[0];
    if (!top) return over ? `<div class="rmini">${DRAW.miniPair({ label: "Overloaded today", figure: DRAW.n(over), w: 100, cls: "row", level: "bad" })}</div>` : "";
    const lim = limitOf(top.head.key), pc = lim ? Math.min(100, (100 * top.peak) / lim) : 100;
    // the overloaded count is a handful against a day of thousands: a bar of it would draw a hairline and say nothing,
    // so it stands as the figure itself, in the limit red, beside the share that a bar does say something about
    return `<div class="rmini">${DRAW.miniPair({ label: "Busiest at once", figure: lim ? `${DRAW.n(top.peak)}/${DRAW.n(lim)}` : DRAW.n(top.peak), w: pc, cls: "row", level: lim && top.peak >= lim ? "bad" : "" })}`
      + (over === null ? "" : `<p class="rfig"><b${over ? ' class="lv-bad"' : ""}>${DRAW.n(over)}</b>Overloaded today</p>`) + `</div>`;
  }
  if (id === "reasoning") {
    if (!KNOB.showReasoning) return "";
    // the terminal in miniature: the line reasoning adds (hatched when it stands in the thinking block, solid when it is the
    // reply's own text, a dashed slot when a setting took it away), then the reply, then what each setting is set to in words
    const where = val("showReasoning"), sum = val("summary"), shown = where !== "off" && sum !== "none";
    const rs = shown ? `<i class="wire${where === "thinking" ? " think" : ""}" style="width:62%"></i>` : `<i class="wire gone" style="width:62%"></i>`;
    const said = `${shown ? `Reasoning ${word(KNOB.showReasoning, where).toLowerCase()}` : "Reasoning hidden"}; waiting line ${flagOn("progressLine") ? "on" : "off"}`;
    return `<div class="rterm" role="img" aria-label="${esc(said)}">${rs}<i class="wire reply" style="width:${{ low: 38, medium: 62, high: 92 }[val("verbosity")] ?? 70}%"></i></div>`
      + `<div class="meta">${summary(id).map((w) => `<span>${esc(w)}</span>`).join("")}</div>`;
  }
  if (id === "plan") {
    if (ui.accts === undefined) return railGhost(id);
    const top = planTop();
    if (!top) return "";
    const warn = Number(val("usageWarnPct")) || 80, logins = planLogins().filter((l) => l.top), over = logins.filter((l) => l.top.used >= warn).length;
    // two quantities on one bar: how close the highest plan is, and where the warn line sits (the kit's own tick); under it,
    // how many logins stand at or past that line, since the line is what the setting moves: one dot a login, the ones at the
    // line lit and the ones at their limit in the limit red, the count beside them
    const said = `${over} of ${logins.length} ${logins.length === 1 ? "login" : "logins"} at ${warn}% or more`;
    const dots = logins.map((l) => `<i class="ld${l.top.used >= 100 ? " full" : l.top.used >= warn ? " over" : ""}"></i>`).join("");
    return `<div class="rmini">${DRAW.miniPair({ label: "Highest plan", figure: `${top.p}%`, w: Math.min(100, top.p),
      cls: "row", mark: warn, markTitle: `Warns at ${warn}%`, level: top.p >= 100 ? "bad" : "" })}`
      + `<div class="rlogins" title="${esc(said)}"><span class="rdots" role="img" aria-label="${esc(said)}">${dots}</span>`
      + `<p class="rfig"><b${over ? "" : ' class="nil"'}>${fmt(over)}</b>of ${fmt(logins.length)}</p></div></div>`;
  }
  // MCP servers: the share of his servers that are shared, on its whole, how many run, and a failure in the limit red
  if (id === "mcp") {
    if (ui.mcp === undefined) return railGhost(id);
    if (ui.mcp === null) return RAIL_UNREAD;
    if (!ui.mcp) return "";
    const rows = mcpServerRows(ui.mcp), shared = rows.filter((r) => r.shared);
    if (!rows.length) return "";
    const up = shared.filter((r) => r.hosted).length, failed = rows.filter((r) => r.err).length;
    return `<div class="rmini">${DRAW.miniPair({ label: "Shared", figure: `${DRAW.n(shared.length)}/${DRAW.n(rows.length)}`, w: (shared.length / rows.length) * 100, cls: "row" })}`
      + `<div class="rfigs">${up ? `<p class="rfig"><b>${DRAW.n(up)}</b>running</p>` : ""}${failed ? `<p class="rfig"><b class="lv-bad">${DRAW.n(failed)}</b>failed</p>` : ""}</div></div>`;
  }
  // Projects: how many there are, and the rule that makes them (the saved folders, or one per repo)
  if (id === "projects") {
    if (ui.projects === undefined) return railGhost(id);
    const n = ui.projects ? (ui.projects.projects || []).length : null, f = foldersNow();
    // the count and its rule share one line, as Version's two figures do: stacked, the rail ran 14 px under the
    // composer at 1440 x 900 once every card had read (Oct 26)
    const rule = f.length ? `<div class="meta">${f.map((w) => `<span>${esc(w)}</span>`).join("")}</div>` : "";
    if (n === 0 && !f.length) return "";
    return `<div class="rfigs">${n === null ? RAIL_UNREAD : n ? `<p class="rfig"><b>${DRAW.n(n)}</b>${n === 1 ? "project" : "projects"}</p>` : ""}${rule}</div>`;
  }
  // Your data: everything kept, split by store in the store colours the pane uses, and Saving stopped in the limit red
  if (id === "data") {
    if (DATA_ROWS.some(stillReading)) return railGhost(id);
    if (DATA_ROWS.some((r) => heldOf(r) === null)) return RAIL_UNREAD;
    const held = bySize(), all = allBytes(), stop = traceStopped();
    const bar = all ? `<span class="rsplit" aria-hidden="true">${held.map((p, i) => `<i style="${hueAt(i)};width:max(${((p.x.bytes / all) * 100).toFixed(2)}%, .12rem)"></i>`).join("")}</span>` : "";
    // the total and its split on one line, the bar taking what the figure leaves: the rail fits above the composer
    return `<div class="rmini"><div class="rkept"><p class="rfig"><b>${esc(mbWord(all))}</b>kept</p>${bar}</div>`
      + (stop ? `<p class="rfig rstop">${DRAW.lamp("stalled")}<span class="state limit">Saving stopped ${esc(stop.when)}</span></p>` : "") + `</div>`;
  }
  // Version: the one it runs, and the newer one a check found, each a figure
  if (id === "version") {
    if (ui.ver === undefined) return railGhost(id);
    if (!ui.ver) return RAIL_UNREAD;
    const v = ui.ver, news = v.latest && v.latest !== v.installed;
    return `<div class="rfigs"><p class="rfig"><b>${esc(v.installed)}</b>running</p>${news ? `<p class="rfig"><b>${esc(v.latest)}</b>newest</p>` : ""}</div>`;
  }
  return "";
}
// A TOPIC IS A CARD WITH ITS GLYPH AND ITS STATE, AND A SEARCH NEVER RESIZES IT (Oct 27): the glyph names the topic
// beside its name, as a list card's glyph does, and is not a lamp, since a topic has no live state of its own to
// light. What the search found in a topic takes the place of the card's state for as long as the search stands, on one
// line, so the rail keeps its height and every card its place under the pointer.
function cardHtml(x, dim = false) {
  const f = found(x.id), drawn = railDraw(x.id), s = drawn ? [] : summary(x.id);
  // a card still being read draws the line it will hold, striped, instead of saying the word Reading
  const draw = drawn || (s.length === 1 && s[0] === "Reading" ? railGhost(x.id) : `<div class="meta">${s.map((w) => `<span>${esc(w)}</span>`).join("")}</div>`);
  const hit = f.length ? `<div class="hits"><span>${hl(f[0])}</span>${f.length > 1 ? `<span class="more">${fmt(f.length - 1)} more</span>` : ""}</div>` : "";
  // a card the search leaves goes quiet by a fade, and what it found fades in over its state (turn)
  const q = turn(`card:${x.id}`, dim), h = turn(`hit:${x.id}`, Boolean(hit));
  return `<article class="card job${dim ? " dim" : ""}${badTopic(x.id) ? " needs" : ""}${q.cls}" data-open="${x.id}" aria-current="${ui.open === x.id}"${q.style}${f.length > 1 ? ` title="${esc(f.join(" · "))}"` : ""}>`
    + `<div class="top"><span class="jglyph" aria-hidden="true">${SV[x.id]}</span><span class="jname">${hl(x.name)}</span></div>`
    + `<div class="jstate${hit ? " hit" : ""}${h.cls}"${h.style}><div class="jdraw">${draw}</div>${hit}</div></article>`;
}

// ---------- the page ----------
const root = document.getElementById("st");
function render() {
  tick(); // times read in the person's present
  if (ui.err) { root.innerHTML = `<div class="list rail plain empty"><div class="nomatch"><span class="state limit">${esc(ui.err)}</span><button class="act quiet" data-act="again">Read again</button></div></div>`; return; }
  ui.legendSaid = new Set();
  ui.advIn = null;
  // SEARCH NARROWS, IT NEVER EMPTIES (Marcos, Oct 25): every topic keeps its place on the rail and the ones with no
  // match go quiet, so the shape of the page does not change under him and a miss is read as a miss. A search that
  // finds nothing keeps the topic he was on, and says so once, beside the field (searchCount).
  const all = jobs(), searching = words().length > 0;
  const shown = searching ? all.filter((x) => found(x.id).length) : all;
  if (shown.length && !shown.some((x) => x.id === ui.open)) ui.open = shown[0].id;
  if (!all.some((j) => j.id === ui.open) && all.length) ui.open = all[0].id;
  // the page opens on the topic that needs him, once, when what decides it has been read
  if (!ui.chosen && !ui.opened && ui.peaks !== undefined && ui.accts !== undefined && !weekReading("restarted", "gaveup", "allweek", "overloaded")) {
    ui.opened = true;
    const w = all.find((j) => badTopic(j.id));
    if (w) ui.open = w.id;
  }
  const x = TOPIC[ui.open];
  // a pane opened afresh draws its rows as they are, with no fade from the last time it was open
  if (ui.drawnTopic !== ui.open) { for (const id of [...turns.keys()]) if (/^(row|adv):/.test(id)) turns.delete(id); ui.drawnTopic = ui.open; }
  const cards = all.map((j) => cardHtml(j, searching && !found(j.id).length));
  const list = `<div class="list rail plain">${cards.join("")}</div>`;
  // the pane's header names the topic and nothing the card beside it already says: its state is drawn inside the pane
  // another topic's pane arrives by a short rise, once, so the eye follows the change from the card he pressed
  const pt = turn("pane", ui.open);
  const pane = `<div class="pane${pt.cls}"${pt.style}><header><span class="jglyph" aria-hidden="true">${SV[x.id]}</span><div class="who"><div class="top"><span class="name">${esc(x.name)}</span></div></div>`
    + `<div class="acts"><button class="icon close" data-act="close" aria-label="Close">${ICON.close}</button></div></header><div class="body kn plate">${PANES[x.id]()}${advancedHtml(x.id)}</div></div>`;
  // a setting's typed field keeps its caret through a redraw (knobs.js keeps the text)
  const f = document.activeElement, fid = f?.id?.startsWith("kf-") ? f.id : null, pos = f?.selectionStart;
  root.innerHTML = `<div class="jobs${ui.shown ? " open" : ""}">${list}${pane}</div>`;
  if (fid) { const g = document.getElementById(fid); if (g) { g.focus(); if (pos != null) g.setSelectionRange(pos, pos); } }
  KNOBS.focusCustom(root);
  // the add tile turned into its form: the caret goes to its field, once
  if (ui.focusPath) { ui.focusPath = false; root.querySelector("[data-pfield]")?.focus(); }
  root.querySelector(".pane .menu")?.scrollIntoView({ block: "nearest" });
  // every meter and bar takes its width after the markup lands, and grows in from nothing the first time a pane opens
  DRAW.paint(root, { grow: ui.grew === ui.open ? null : "all" });
  ui.grew = ui.open;
  searchCount();
}
/** How many settings the search found, beside the field: a count is the one thing the rail cannot show him. A search
 *  that finds no setting but names a topic counts the topics; one that finds nothing says so, with the way out. */
function searchCount() {
  const box = document.getElementById("qn");
  if (!box) return;
  const w = words().length, n = w ? liveKnobs().filter((k) => hits(knobHay(k))).length : 0, t = w ? jobs().filter((x) => found(x.id).length).length : 0;
  box.classList.toggle("miss", w > 0 && !n && !t);
  box.innerHTML = !w ? "" : n ? `<b>${fmt(n)}</b> ${n === 1 ? "setting" : "settings"}${t > 1 ? ` in ${fmt(t)} topics` : ""}`
    : t ? `<b>${fmt(t)}</b> ${t === 1 ? "topic" : "topics"}`
    : `<span>No setting matches</span><button class="act quiet small" type="button" data-act="clear">Clear</button>`;
}

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
// WHAT RUNS AND WHAT WAITS NOW is each command's own gate on /api/heads. It is read again every few seconds while the page
// is in view and drawn again only when a figure on it moved, so a quiet machine redraws nothing.
let gatesSeen = "";
async function readGates() {
  const r = await API.get("/api/heads");
  if (!r.ok) return;
  const gates = Object.fromEntries((r.body?.heads || []).map((h) => [h.key, h.gate || null]));
  const seen = JSON.stringify(Object.values(gates).map((g) => [g?.inflight, g?.queued, g?.max]));
  ui.gates = gates;
  if (seen !== gatesSeen) { gatesSeen = seen; later(); }
}
setInterval(() => { if (!document.hidden && !ui.err) readGates(); }, 5000);
document.getElementById("q").addEventListener("input", (e) => { ui.q = e.target.value; ks.menu = null; ks.custom = null; render(); });
document.addEventListener("keydown", (e) => {
  // the folder being added answers to the keys a field does: Enter saves it, Escape puts the add tile back
  if (e.target.matches?.("[data-pfield]")) {
    if (e.key === "Enter") { e.preventDefault(); saveFolders(withPath(e.target.value)); return; }
    if (e.key === "Escape") { ui.addPath = null; delete ui.bad.projectsFolders; render(); return; }
  }
  // Escape in the search field clears it, as the Clear beside a miss does
  if (e.key === "Escape" && e.target.id === "q" && ui.q) { ui.q = ""; e.target.value = ""; render(); return; }
  if (e.key === "Escape" && (ui.armed || ui.ask)) { ui.armed = null; ui.ask = null; render(); }
});
// what he types into the folder field is kept, so a drawing while he types (a read landing) does not take it from him
document.addEventListener("input", (e) => { if (e.target.matches?.("[data-pfield]")) ui.addPath = e.target.value; });
document.addEventListener("click", (e) => {
  if (KNOBS.click(e, render)) { render(); return; }
  const t = e.target.closest("[data-act], [data-open]");
  if (ui.armed && !e.target.closest("[data-act=delete], [data-act=arm]")) { ui.armed = null; if (!t) { render(); return; } }
  if (!t) return;
  const d = t.dataset;
  // leaving a job drops what was said on it: a refusal he has walked away from is stale by the time he comes back
  if (d.open && !d.act) { ui.open = d.open; ui.chosen = true; ui.shown = true; ui.armed = null; ui.checkSaid = null; ui.restartSaid = null; ks.custom = null; render(); return; }
  switch (d.act) {
    case "theme": toggleTheme(); return;
    case "close": ui.shown = false; break;
    case "clear": { ui.q = ""; const q = document.getElementById("q"); q.value = ""; q.focus(); break; }
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
    case "addfolder": ui.addPath = ""; ui.focusPath = true; delete ui.bad.projectsFolders; break;
    case "cancelfolder": ui.addPath = null; delete ui.bad.projectsFolders; break;
    case "savefolder": saveFolders(withPath(root.querySelector("[data-pfield]")?.value ?? "")); return;
    case "dropfolder": saveFolders(foldersNow().filter((p) => p !== d.path)); return;
    case "armfolder": ui.armed = `folder:${d.path}`; break;
    case "mmore": ui.mmore[d.id] = !ui.mmore[d.id]; break;
    case "pmore": ui.pmore[d.id] = !ui.pmore[d.id]; break;
    case "dzero": ui.dzero = !ui.dzero; break;
    // the setting a dimmed row waits on, brought into view and lit once where it stands, its control under the keys
    case "seerow": {
      const row = root.querySelector(`.krow[data-key="${CSS.escape(d.key ?? "")}"]`);
      if (row) {
        row.scrollIntoView({ behavior: "smooth", block: "center" });
        row.classList.remove("landed"); void row.offsetWidth; row.classList.add("landed");
        row.querySelector(".ctl button, .ctl input")?.focus({ preventScroll: true });
      }
      return;
    }
    // the way back from Saving stopped: the stores' deletes, brought into view and lit once where they stand
    case "tofree": {
      const dels = [...root.querySelectorAll(".drow .dx")].filter((c) => c.querySelector("button"));
      if (dels.length) {
        dels[0].scrollIntoView({ behavior: "smooth", block: "center" });
        for (const c of dels) { c.classList.remove("landed"); void c.offsetWidth; c.classList.add("landed"); }
      }
      return;
    }
    default: return;
  }
  render();
});
if (TOPIC[location.hash.slice(1)]) { ui.open = location.hash.slice(1); ui.chosen = true; ui.shown = true; }
// before the first read answers, the rail draws its cards: their names and glyphs are this page's own, and each card's
// state is the ghost of what will land in it (Marcos, Oct 12: a blank page under its title read as broken)
// EACH CARD'S GHOST IS THE STATE IT WILL HOLD (Oct 27): the pairs of a word, a figure and a bar the card draws once read,
// in their own words, their figures striped and their bars empty tracks, so no card grows and no card under it moves
// when the reads land. A one-line stripe stood where two pairs land, and the rail grew 150 px under the pointer.
const gPair = (label) => `<div class="pd-q row" aria-hidden="true"><span class="k">${esc(label)}</span><b>${GHOST.fig(2.6)}</b><span class="pd-mini"><i style="width:0%"></i></span></div>`;
const gFig = (w) => `<p class="rfig" aria-hidden="true"><b>${GHOST.fig(1.6)}</b>${esc(w)}</p>`;
const RAIL_GHOST = {
  silent: () => `<div class="rmini">${gPair("Ended early")}${gPair("Waited out")}</div>`,
  busy: () => `<div class="rmini">${gPair("Busiest at once")}${gFig("Overloaded today")}</div>`,
  reasoning: () => `<div class="rterm" aria-hidden="true"><i class="wire ghost" style="width:62%"></i><i class="wire ghost" style="width:70%"></i></div>${GHOST.row([GHOST.bar(9)])}`,
  // the logins as dots, one each, and how many of them stand at the warn line
  plan: () => `<div class="rmini">${gPair("Highest plan")}<div class="rlogins" aria-hidden="true"><span class="rdots">${GHOST.bar(Math.max(1, shapeOf("logins", 6)) * .87)}</span>`
    + `<p class="rfig"><b>${GHOST.fig(1.6)}</b>of ${GHOST.fig(1)}</p></div></div>`,
  mcp: () => `<div class="rmini">${gPair("Shared")}<div class="rfigs">${gFig("running")}</div></div>`,
  projects: () => `<div class="rfigs">${gFig("projects")}</div>`,
  data: () => `<div class="rmini"><div class="rkept"><p class="rfig" aria-hidden="true"><b>${GHOST.fig(3.4)}</b>kept</p><span class="rsplit">${GHOST.track()}</span></div></div>`,
  version: () => `<div class="rfigs">${gFig("running")}</div>`,
};
const railGhost = (id) => (RAIL_GHOST[id] ?? RAIL_GHOST.reasoning)();
// the rail AND the open topic beside it, both as the shapes that will land: a rail alone left two thirds of the page
// empty for as long as the first read takes, and the pane then arrived into that hole (the slow door, Oct 26)
const ghostHtml = () => `<div class="jobs${ui.shown ? " open" : ""}"><div class="list rail plain" aria-busy="true" aria-label="Reading settings">${TOPICS.map((x) => `<article class="card job ghosted" aria-hidden="true" aria-current="${ui.open === x.id}">`
  + `<div class="top"><span class="jglyph">${SV[x.id]}</span><span class="jname">${esc(x.name)}</span></div><div class="jstate"><div class="jdraw">${railGhost(x.id)}</div></div></article>`).join("")}</div>`
  + `<div class="pane" aria-hidden="true"><header><span class="jglyph">${SV[ui.open] ?? SV.busy}</span><div class="who"><div class="top"><span class="name">${esc(TOPIC[ui.open]?.name ?? "")}</span></div></div></header>`
  + `<div class="body kn plate">${(PANE_GHOST[ui.open] ?? PANE_GHOST.busy)()}</div></div></div>`;
// EVERY TOPIC ARRIVES IN ITS OWN SHAPE (Oct 26): opened from a link, a topic draws its sections under their real
// kickers, its settings under their own names, and the figures, bars, rows and cards they will hold as ghosts, so
// nothing moves when the reads land.
const ghostSec = (body, kicker = "") => `<section class="psec"><div class="sechead">${kicker ? `<h3 class="kicker">${esc(kicker)}</h3>` : GHOST.bar(5)}</div>${body}</section>`;
/** A section's settings before the settings are read: each name, and its control's ghost. */
/** A section's settings before the settings are read: each name, its control's ghost, and where the loaded rows draw
 *  what a value does, that drawing's ghost [draw] by key, on the grid the loaded rows use, with the axis line [axis]
 *  names under them and the longest stall's place over them [head], so the rows land where their ghosts stood. */
function ghostRows(keys, { draw = null, axis = "", head = false, adv = "" } = {}) {
  const xs = keys.filter((key) => KNOB[key]);
  const cls = draw ? "" : ` flat${xs.length >= 3 ? " n3" : xs.length === 2 ? " n2" : ""}`;
  const rows = xs.map((key) => `<div class="krow" aria-hidden="true"><div class="lbl">${esc(KNOB[key].label)}</div><div class="ctl">${GHOST.bar(9, "chip")}</div>`
    + `${draw ? `<div class="kdraw">${draw[key] ?? ""}</div>` : ""}</div>`).join("");
  return `<div class="krows${cls}">${head ? `<div class="khead" aria-hidden="true"><span></span><span></span><span class="kmk"></span></div>` : ""}${rows}`
    + `${axis ? `<div class="kaxis" aria-hidden="true"><span></span><span class="kaw">${esc(axis)}</span><span class="pd-ax"></span></div>` : ""}${adv ? advGhost(adv, true) : ""}</div>`;
}
/** The Advanced button a topic ends in, as the shape it will be, with the count of what it holds. */
const advGhost = (id, inside = false) => {
  // the daemon names a topic's advanced settings when it answers, so until then the count is what the last visit held, and
  // a ghost where no visit has kept one (every topic but Plan limits ends in the button)
  const n = Object.values(KNOB).filter((k) => k.adv === id).length || shapeOf(`adv:${id}`, -1);
  const count = n < 0 ? GHOST.bar(1.6) : `<b>${fmt(n)}</b>`;
  return n ? `<section class="${inside ? "adv in" : "psec adv"}"><div class="sechead"><span class="act quiet advbtn" aria-hidden="true">Advanced${count}${ICON.caret}</span></div></section>` : "";
};
const trackOf = (keys) => Object.fromEntries(keys.map((key) => [key, GHOST.track()]));
const PANE_GHOST = {
  silent: () => pair(ghostSec(heroGhost() + stallGhost(), "This week"),
    ghostSec(ghostRows(keysOf("silent"), { draw: trackOf(keysOf("silent")), axis: "Time without progress", head: true, adv: "silent" }), "How long splice waits")),
  busy: () => pair(ghostSec(heroGhost() + peaksGhost(), "Today") + (shapeOf("refused", 0) ? ghostSec(`${GHOST.focal(16)}${GHOST.row([GHOST.chip(), GHOST.cell()])}`, "Providers today") : ""),
    ghostSec(ghostRows(LIMITS.filter((key) => keysOf("busy").includes(key)), { draw: trackOf(["maxInflight"]), adv: "busy" }), "splice's limits"), "lead"),
  reasoning: () => pair(ghostSec(termGhost(), "In your terminal"),
    ghostSec(ghostRows(SHOWS.filter((key) => keysOf("reasoning").includes(key)), { adv: "reasoning" }), "How reasoning shows")),
  plan: () => pair(ghostSec(heroGhost() + planGhost(), "Right now"), ghostSec(ghostRows(keysOf("plan"), { draw: {
    quotaPollIntervalMs: GHOST.bar(9), usageWarnPct: GHOST.track(),
    usageWarnTokens5h: `<div class="kfacts">${GHOST.bar(8)}<span class="kchips">${GHOST.chip()}${GHOST.chip()}${GHOST.chip()}</span></div>` } }), "When splice warns")),
  mcp: () => ghostSec(heroGhost() + GHOST.stack(), "Sharing") + ghostSec(srvGhost(), "Shared")
    + pair(ghostSec(mcardsGhost(), "Not shared"), ghostSec(saveGhost(), "What sharing saves")
      + ghostSec(ghostRows(mcpKeys(), { draw: Object.fromEntries(mcpKeys().map((k) => [k, KNOB[k]?.c?.k === "dur" ? GHOST.track() : GHOST.bar(6)])), axis: "How long" }), "Server limits"), "lead") + advGhost("mcp"),
  // the projects and the folder that makes them, in the two columns they land in
  projects: () => pair(ghostSec(`${heroGhost()}<div class="pmakes">${prjGhost()}</div>`, "Projects"),
    ghostSec(`<div class="plist">${GHOST.bar(12)}<i class="ghost" style="width:100%;height:2.7rem;border-radius:var(--r-in)" aria-hidden="true"></i></div>`, "Projects folder"), "lead") + advGhost("projects"),
  // the sections it lands in, each under its kicker: a table of rows for each group (a row to each store the page lists), so
  // the page is as tall at its first frame as when the reads land
  data: () => {
    const row = `<article class="drow dghost" aria-hidden="true"><div class="dn"><div class="dnm"><span class="nm">${GHOST.bar(9)}</span></div><span class="dmeta">${GHOST.bar(8)}</span></div>`
      + `<div class="dz">${GHOST.cell()}</div><div class="dr">${GHOST.cell()}</div><div class="ds">${GHOST.bar(6.5, "chip")}</div>`
      + `<div class="dk"><span class="dkc">${GHOST.bar(7, "chip")}</span>${GHOST.track()}</div><div class="dx">${GHOST.bar(5.5, "chip")}</div></article>`;
    const tab = (g) => `<div class="dwrap"><div class="dtab" aria-hidden="true">${DHEAD}${DATA_ROWS.filter((r) => r.g === g).map(() => row).join("")}${dfoot()}</div></div>`;
    return ghostSec(heroGhost() + GHOST.stack(), "On this computer") + ghostSec(tab("text"), "Conversation text") + ghostSec(tab("records"), "Records")
      + ghostSec(ghostRows(["transcriptView", "replayReasoning"], { draw: { transcriptView: GHOST.bar(6), replayReasoning: GHOST.bar(8) } }), "Claude Code conversations") + advGhost("data");
  },
  version: () => pair(ghostSec(`<div class="vline">${[5, 8].map((w) => `<div class="vghost" aria-hidden="true">${GHOST.bar(5)}${GHOST.fig(w)}${GHOST.bar(9)}</div>`).join("")}</div>`, "Versions"),
    ghostSec(`<div class="vfiles">${[13, 10, 10].map((w) => `<div class="vfile" aria-hidden="true">${GHOST.bar(5)}${GHOST.bar(w)}</div>`).join("")}</div>`, "Files"), "lead") + advGhost("version"),
};
root.innerHTML = ghostHtml();
refresh();

});
