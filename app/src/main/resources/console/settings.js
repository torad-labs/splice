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
  : key === "mcpMaxServers" && ui.hubOn ? "The MCP hub runs these servers, with no limit on how many" : null;
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
  if (!keys.length) return "";
  const open = advOpen(id);
  // the count is on the button: how much is behind it is the one thing he cannot see while it is closed
  return `<section class="psec adv"><div class="sechead"><button class="act quiet advbtn" data-act="adv" data-id="${id}" aria-expanded="${open}">Advanced<b>${fmt(keys.length)}</b>${ICON.caret}</button>${open ? paneLegend() : ""}</div>`
    + `<div class="advin"${open ? "" : " hidden"}>${open ? krowsHtml(keys) : ""}</div></section>`;
}

// A figure not read yet is undefined and draws its own shape, striped; one asked for and not answered is null and draws "Not read", its reason warned to the console.
const ui = { adv: {}, said: {}, capture: {}, week: {}, ver: undefined, run: null, headRows: [], saving: null, held: {}, open: "busy", shown: false, q: "", armed: null, bad: {}, ask: null, heads: [], hist: undefined, stores: undefined, kept: {}, peaks: undefined, mcp: undefined, mtest: {}, mtestAt: {}, topo: undefined, mmem: undefined, shareSaving: false, shareSaid: null, exSaid: {}, accts: undefined, projects: undefined, addPath: null, checking: false, checkSaid: null, restarting: false, restartSaid: null, restartWait: 0, mmore: {}, pmore: {}, grown: new Set(), shook: null, err: null, legendSaid: new Set() };
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
  // a command's colour is its provider's FAMILY: the models route names a provider in its module's own word ("codex",
  // "openai"), which the colour table does not, and a chip drawn from the module's word came out in no colour at all
  ks.providerOf = Object.fromEntries((models.body?.heads || []).map((r) => [r.head, familyOf(r.provider)]));
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
/** A provider as the colour table names it (kit.js COLORS), from the word its module uses ("codex", "anthropic"). */
const familyOf = (p) => (COLORS[p] ? p : PROVIDER_FAMILY[p] ?? p);

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
  if (key === "quotaPollIntervalMs") {
    const seen = seenAt();
    return seen ? `<span class="kfact">Read <b>${esc(whenWord(seen * 1000))}</b></span>` : "";
  }
  if (key === "usageWarnPct") return warnDraw();
  if (key === "usageWarnTokens5h") {
    const quiet = quietLogins();
    return quiet.length ? `<span class="kfact">No limit reported</span><span class="kchips">${quiet.map(loginChip).join("")}</span>` : "";
  }
  // how many run now against the most it may, as a meter on that ceiling: full at the limit, in the limit red
  if (key === "mcpMaxServers") {
    if (!ui.mcp) return "";
    const up = Object.values(ui.mcp.servers || {}).filter((s) => s.hosted).length, most = Number(val("mcpMaxServers")) || 0;
    return most ? `<div class="kmeter">${DRAW.meter((up / most) * 100, up >= most ? "full" : "")}<span><b>${fmt(up)}</b> running</span></div>`
      : `<span class="kfact"><b>${fmt(up)}</b> running</span>`;
  }
  return "";
}

// ---------- a section's settings, on one grid ----------
// A SETTING IS A ROW, AND WHAT IT DOES IS DRAWN ON IT (Oct 27, from Marcos's "show don't tell"): its name, its control
// and, where this page reads the fact, the drawing of what the value does, each in a column of its own, so the names,
// the controls and the drawings start at one x down a section. A section whose rows draw nothing is two columns.
// [draw] gives a row its own drawing by key. [ax] puts the drawings on one time axis, its ticks said once under the
// rows. [mark] is a figure this page read on that axis (the longest stall this week): one upright line through every
// row, with its door above them, so each wait is read against what happened.
function krowsHtml(keys, { ax = null, draw = {}, mark = null } = {}) {
  if (!keys.length) return "";
  const drawn = keys.map((key) => (draw[key] && ax ? draw[key](key, ax) : elOf(key)));
  const flat = !ax && drawn.every((d) => !d);
  const at = ax && mark ? ax.at(mark.ms) : null;
  const line = at == null ? "" : `<i class="dline" style="left:${at.toFixed(2)}%" aria-hidden="true"></i>`;
  const rows = keys.map((key, i) => {
    const k = KNOB[key], hay = knobHay(k);
    // a search keeps every row in its place: the ones it found keep their ink, the rest go quiet
    const found = words().length ? (hits(hay) ? " hit" : " miss") : "";
    const el = flat ? "" : `<div class="kdraw">${drawn[i] ? `${drawn[i]}${draw[key] ? line : ""}` : ""}</div>`;
    return `<div class="krow${found}" data-key="${key}">${rowHtml(k, { hl, lit: lit(hay), el, inert: inertWhy(key) })}</div>`;
  });
  const head = at == null ? "" : `<div class="khead"><span></span><span></span><span class="kmk">`
    + `<a class="dmark${at > 55 ? " end" : ""}" href="${mark.href}" style="left:${at.toFixed(2)}%">${mark.inner}</a></span></div>`;
  const ticks = ax ? ax.ticks.map((v) => {
    const x = ax.at(v), edge = x < 4 ? " pd-t-first" : x > 96 ? " pd-t-last" : "";
    return `<span class="pd-tk${edge}" style="left:${x.toFixed(2)}%">${esc(dur(v))}</span>`;
  }).join("") : "";
  // the axis's word sits in the control column, at its right edge, so it ends where the axis starts and reads as its caption
  const foot = ax ? `<div class="kaxis" aria-hidden="true"><span></span><span class="kaw">${esc(ax.word)}</span><span class="pd-ax">${ticks}</span></div>` : "";
  return `<div class="krows${flat ? " flat" : ""}">${head}${rows.join("")}${foot}</div>`;
}
// THE MARKS ARE NAMED ONCE A PANE (Marcos, Oct 25: "show don't tell"): a row names a command only where that command
// holds its own value, so the scope every other row follows is said once, on the first section it applies to; and the
// first section carrying a mark names it on its kicker line, a later one naming only the marks above it did not.
const perCommandIn = (keys) => keys.some((key) => ks.cfg?.knobs?.[key]?.per_command ?? Boolean(KNOB[key]?.cmd));
function paneLegend(keys) {
  const said = ui.legendSaid;
  const scope = !said.has("scope") && perCommandIn(keys) ? `<span class="lg sc">Every command, unless one is named</span>` : "";
  if (scope) said.add("scope");
  const items = KNOBS.legendHtml(keys, { bare: true }).split('<span class="lg">').filter(Boolean).map((s) => `<span class="lg">${s}`).filter((s) => !said.has(s));
  items.forEach((s) => said.add(s));
  return scope || items.length ? `<span class="legend">${scope}${items.join("")}</span>` : "";
}
/** A section of settings: its kicker, what its rows' marks mean, and the rows. */
const knobSec = (kicker, keys, opts = {}) => (keys.length ? DRAW.section(kicker, krowsHtml(keys, opts), { aside: paneLegend(keys) }) : "");

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
  { id: "recordings", g: "text", name: "Compaction summaries", unit: "files", store: "compaction_summaries", knob: "keepCompactionSummaries", off: "When off, retried compactions cost again", life: "2 hours" },
  { id: "journals", g: "text", name: "Code mode work", unit: "files", store: "code_mode", knob: "keepCodeModeWork", off: "When off, a restart ends running scripts", life: "1 day unused" },
  { id: "reasoning", g: "text", name: "Reasoning between requests", own: true, door: ["sessions.html", "Sessions"] },
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
/** A store whose count has not come back yet (a store with no route to count it is never waiting). */
const stillReading = (r) => !(r.noRoute || r.own) && heldOf(r) === undefined;
// REMOVAL ARMS IN PLACE: Delete now turns into what it would delete, by count, and the way out, on the store's own tile
const delBtn = (id, n, unit) => (!n ? "" : ui.armed === id
  ? `<span class="armed"><button class="act danger small" data-act="delete" data-id="${id}">Delete ${fmt(n)} ${unit}</button><button class="act quiet small" data-act="disarm">Cancel</button></span>`
  : `<button class="act quiet small" data-act="arm" data-id="${id}">Delete now</button>`);
const ALWAYS = `<span class="fixed quiet">Always saved</span>`; // a store whose daemon has no off switch
// WHAT OFF COSTS IS SAID WHILE IT IS IN FORCE (Oct 26): under a switch that is On the line told him, on every card, what
// a press he is not making would cost. It is the Off button's own title while On, and the line under the switch once Off,
// where it is the state he is in. Each part wraps at a dot, never inside a part (fin).
const costHtml = (off, isOff) => (off && isOff ? `<p class="offcost now">${off.split(" · ").map((t) => `<span class="whole">${esc(t)}</span>`).join(" · ")}</p>` : "");
const offTitles = (off) => (off ? { titles: { false: off.replace(/ · /g, ". ") } } : {});
/** One store, as a card: its colour, its name and door, its size at the focal size, what it holds, and its own controls.
 *  The card returns its Delete rather than drawing it, so every destructive act stands together in Free up space. */
const drow = ({ cls = "", name, hay = name, door, amount, keep = "", sw = "", del = "", off = "", offShown = false, more = "", hue = "", lamp = "" }) => ({
  name, del,
  // .din is the card's inner layout, one element in, so it can ask the card how wide it turned out (settings.css)
  html: `<article class="dcard${cls}${lit(hay)}"><div class="din">`
    + `<header class="dtop">${hue ? `<i class="dsw" style="${hue}" aria-hidden="true"></i>` : ""}<span class="nm">${hl(name)}</span>${lamp}${door ? `<a class="link" href="${door[0]}">${SV.open}${door[1]}</a>` : ""}</header>`
    + `<div class="dfig">${amount}</div>`
    + (keep || sw ? `<div class="dctl">${sw ? `<div class="dc"><span class="dcl">Save</span><div class="dcv">${sw}</div></div>` : ""}${keep ? `<div class="dc"><span class="dcl">Keep for</span><div class="dcv">${keep}</div></div>` : ""}</div>` : "")
    + `${costHtml(off, offShown)}${more}</div></article>`,
});
/** Everything splice keeps, in bytes: the figure the pane leads with. */
const allBytes = () => DATA_ROWS.reduce((s, r) => s + (heldOf(r)?.bytes ?? 0), 0);
/** How fast a store grows. Usage history is the one store the daemon measures (rate_bytes_per_month, over its own days);
 *  for every other store it is the size over the days since the oldest thing kept. */
function growOf(r, x) {
  if (r.id === "hist") return ui.hist && ui.hist.rate_bytes_per_month ? `About ${mbWord(ui.hist.rate_bytes_per_month)} a month` : "";
  if (!x.since || !x.bytes) return "";
  const days = Math.max(1, Math.round((Date.now() - x.since) / DAY));
  return `About ${mbWord(x.bytes / days)} a day`;
}
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
const amountOf = (x, r) => (x.n || x.bytes
  ? `<div class="pd-sub"><b>${esc(mbWord(x.bytes))}</b><span>${fmt(x.n)} ${esc(r.unit)}${x.since ? ` · since ${esc(dateWord(new Date(x.since)))}` : ""}</span></div>`
    + (growOf(r, x) ? `<span class="dgrow">${growOf(r, x)}</span>` : "") + (r.id === "hist" ? daysChart(ui.hist, hueOf(r)) : "")
  : DRAW.nothing("Nothing saved"));
// what a store's size will be, while it is read: the figure and the line under it, striped
const AMOUNT_GHOST = GHOST.row([GHOST.fig(5.5), GHOST.bar(9)]);
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
function histRow(r) {
  const hue = hueOf(r);
  if (ui.hist === undefined) return drow({ name: r.name, door: r.door, amount: AMOUNT_GHOST, cls: " hist" });
  if (!ui.hist) return drow({ name: r.name, door: r.door, amount: unreadable, cls: " hist" });
  const a = ui.ask, x = heldOf(r), id = "del:hist";
  return drow({
    name: r.name, hay: `${r.name} ${KNOB.historyRetentionDays.also}`, door: r.door, hue, cls: " hist",
    sw: ALWAYS, // the daemon has no switch for turn statistics: only how far back they go
    amount: amountOf(x, r),
    // the month's rate is said once, under the size it grows
    keep: `${ctlHtml(KNOB.historyRetentionDays, a ? a.v : keptDays())}${saidHtml("historyRetentionDays")}`,
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
function traceKeep() {
  const w = ui.hist.window, windowDays = w.forever ? Infinity : w.nothing ? 0 : w.days;
  const days = ui.heads.map((h, i) => {
    const body = ui.kept.trace?.[i];
    // the route's kept_days (227e4e5bb): null keeps every day; a body without the field is an older daemon
    if (body && "kept_days" in body) return body.kept_days === null ? Infinity : body.kept_days;
    return traceSetDays(h) ?? windowDays;
  });
  const n = days.length ? Math.max(...days) : windowDays;
  return n === Infinity ? "Until its size caps" : n === 0 ? "Today only" : `Up to ${n} ${n === 1 ? "day" : "days"}`;
}
const TRACE_CAPS = "Sooner past its size caps, the oldest day first";
/** Prompts and answers, one command at a time: its switch, what its change did, and what it holds on one shared scale. */
function traceCommands() {
  const sizes = ui.heads.map((h, i) => ({ h, b: ui.kept.trace?.[i]?.bytes ?? null }));
  const top = Math.max(1, ...sizes.map((s) => s.b ?? 0));
  const rows = [...sizes].sort((a, b) => (b.b ?? -1) - (a.b ?? -1)).map(({ h, b }) => {
    const said = ui.said[`capture@${h}`] ? `<span class="said ${ui.bad[`capture@${h}`] ? "limit" : "ok"}">${esc(ui.said[`capture@${h}`])}</span>` : "";
    return `<div class="cs${traceOn(h) ? "" : " off"}">${cmdChip(h)}${b === null ? DRAW.dash : DRAW.barCell({ w: (b / top) * 100, text: esc(mbWord(b)) })}`
      + `<span class="switch"><button data-act="capture" data-head="${esc(h)}" data-v="true" aria-pressed="${traceOn(h)}">On</button><button data-act="capture" data-head="${esc(h)}" data-v="false" aria-pressed="${!traceOn(h)}">Off</button></span>${said}</div>`;
  }).join("");
  return `<div class="dmore"><div class="cmdsw">${rows}</div><p class="dcap">${esc(TRACE_CAPS)}</p></div>`;
}
function dataRow(r) {
  if (r.id === "hist") return histRow(r);
  if (r.own) { // splice keeps no copy: the reasoning rides inside the conversations Claude Code writes, so the switch stops it and Sessions deletes it
    return drow({ name: r.name, door: r.door, amount: DRAW.nothing("Inside your conversations"), keep: `<span class="fixed quiet">As long as the conversation</span>`, sw: `${ctlHtml(KNOB.replayReasoning, val("replayReasoning"))}`, del: `<a class="act quiet small" href="sessions.html">Delete in Sessions</a>` });
  }
  if (r.noRoute) { // no route counts or deletes it: say so on the row, never an empty Delete
    return drow({ name: r.name, amount: DRAW.nothing("Not counted"), keep: "", sw: r.always ? ALWAYS : "" }); // a store the daemon has no off switch for
  }
  const x = heldOf(r), id = `del:${r.id}`;
  if (x === undefined) return drow({ name: r.name, door: r.door, amount: AMOUNT_GHOST });
  if (!x) return drow({ name: r.name, door: r.door, amount: unreadable });
  const on = r.id === "trace" ? ui.heads.filter(traceOn) : null;
  // the copies keep their own days (the President, Oct 12), and the messages theirs, so each row sets them in its Keep for
  const keep = r.id === "originals" ? `${ctlHtml(KNOB.transcriptCopyDays, val("transcriptCopyDays"))}${saidHtml("transcriptCopyDays")}`
    : r.id === "trace" && ui.hist ? `<span class="fixed quiet">${esc(traceKeep())}</span>`
    : r.id === "edges" ? `${ctlHtml(KNOB.activityRetentionDays, val("activityRetentionDays"))}${saidHtml("activityRetentionDays")}`
    : r.id === "labels" ? `<span class="fixed quiet">Today and yesterday</span>`
    : r.life ? `<span class="fixed quiet">${esc(r.life)}</span>` : "";
  const said = r.knob ? saidHtml(r.knob) : "";
  // every command saving is a dot in its provider's colour, hollow where it is off, so "9 of 11" is seen before it is read
  const dots = on ? `<span class="cdots" aria-hidden="true">${ui.heads.map((h) => `<i class="${traceOn(h) ? "" : "off"}" style="--c:${colorOf(ks.providerOf[h])}"></i>`).join("")}</span>` : "";
  return drow({
    name: r.name, door: r.door, amount: amountOf(x, r), keep, hue: hueOf(r), cls: said || r.knob ? " once" : "", off: r.off || "", offShown: !!r.knob && !val(r.knob),
    lamp: r.id === "trace" && traceStopped() ? DRAW.lamp("stalled") : "",
    sw: r.knob ? ctlHtml(KNOB[r.knob], val(r.knob), null, offTitles(r.off)) + said
      : on ? `<button class="count" data-act="saving" aria-expanded="${ui.saving === r.id}">${dots}${on.length} of ${ui.heads.length} commands${ICON.caret}</button>` : "",
    more: on && ui.saving === r.id ? traceCommands() : "",
    del: `${ui.bad[id] ? `<span class="said limit">${esc(ui.bad[id])}</span>` : ""}${delBtn(id, x.n, r.unit)}`,
  });
}
// THE SPLIT BAR IS THE KIT'S (DRAW.stack): one proportional bar over a whole, each part labelled inside itself where
// it is wide enough and in one row under the bar where it is not. The kit now takes each part's own figure and its own
// colour, and a part that is the REST of a whole ("rest": the servers not shared, the repos no folder holds) keeps its
// own word instead of being gathered under an outcome's phrase — so this page carries no copy of the bar. A bar grows
// in from nothing the first time it is drawn with data, once.
const SPLIT_REM = 60; // about how wide the pane's bar is at the narrowest side-by-side width, in rem
const storeInk = (i) => `var(--store-${(i % 6) + 1})`;
function splitBar(parts, total, { id = "", tail = "" } = {}) {
  const grow = id && !ui.grown.has(id) && total > 0;
  if (grow) ui.grown.add(id);
  const label = parts.filter((p) => p.n > 0).map((p) => `${p.word} ${p.text}`).join(", ");
  return DRAW.stack(parts, total, { grow, label, tail, bw: SPLIT_REM });
}
/** What splice keeps, all of it, as the topic's one figure and one bar split by store, biggest first; and the moment
 *  saving stopped, with the way to the space that brings it back. */
function keptHtml() {
  const reading = DATA_ROWS.some(stillReading), unread = DATA_ROWS.some((r) => !(r.noRoute || r.own) && heldOf(r) === null);
  const held = bySize(), all = allBytes();
  // a total over a store that was not read is not read either: "0 B" over unread stores is a figure that is not true
  const hero = reading ? heroGhost()
    : unread ? `<div class="pd-top"><b class="pd-rate">${DRAW.dash}</b><span class="pd-failed"><span>kept</span></span>${unreadable}</div>`
    : DRAW.hero({ figure: mbWord(all), of: "kept" });
  const stop = reading ? null : traceStopped();
  // SAVING STOPPED IS A STATE ON THE FIGURE IT STOPS (Oct 26): the stalled lamp, since when, the commands it holds for,
  // the cause, and the one way back, which scrolls to the deletes and lights them once
  const stopped = stop ? `<div class="dstop" role="status">${DRAW.lamp("stalled")}<b>Saving stopped ${esc(stop.when)}</b>`
    + `${stop.heads.length ? `<span class="dwho">${stop.heads.map((h) => cmdChip(h)).join("")}</span>` : ""}${stop.words ? `<span class="dwhy">${esc(stop.words)}</span>` : ""}`
    + (stop.known ? `<button class="act quiet small" data-act="tofree">Free up space</button><span class="dthen">Saves again on the next request</span>` : "") + `</div>` : "";
  const bar = reading ? GHOST.stack() : all ? splitBar(held.map((p, i) => ({ n: p.x.bytes, text: mbWord(p.x.bytes), word: p.r.name, ink: storeInk(i) })), all, { id: "data" }) : "";
  return DRAW.section("On this computer", `${hero}${stopped}${bar}`, { cls: "tally" });
}
// the knobs each group's cards carry, so the marks on their values are named once on the group's own line
const GROUP_KNOBS = {
  text: ["transcriptView", "keepTranscriptCopies", "transcriptCopyDays", "keepCompactionSummaries", "keepCodeModeWork", "replayReasoning"],
  records: ["historyRetentionDays", "messageEdges", "activityRetentionDays"],
};
// the tap is set per command in splice.toml and read at start, so its card carries the lock and restart marks, and the
// group's legend names them only while that card is drawn
const groupKnobs = (g, tapped) => (g === "text" && tapped ? [...GROUP_KNOBS.text, "wireTap"] : GROUP_KNOBS[g]);
function dataHtml() {
  const read = drow({ name: "Read your Claude Code conversations", door: ["sessions.html", "Sessions"], amount: DRAW.nothing("Nothing saved"), keep: saidHtml("transcriptView"), sw: ctlHtml(KNOB.transcriptView, val("transcriptView"), null, offTitles(TV_OFF)), off: TV_OFF, offShown: !val("transcriptView") });
  // what each tapped command holds in memory right now: counted, never shown, and no Delete since the daemon has none
  const taps = ui.heads.filter((h) => tapOf(h) > 0), heldN = taps.reduce((n, h) => n + (ui.held[h] ?? 0), 0);
  // The card draws when the fact exists: a command taps its requests or bodies are held. With neither it holds nothing and
  // offers nothing (the tap is set per command in splice.toml and read at start), so it is not drawn at all (fin, Oct 11).
  const memory = taps.length || heldN ? drow({
    name: "Last requests, in memory",
    amount: taps.some((h) => ui.held[h] === undefined) ? AMOUNT_GHOST : taps.some((h) => ui.held[h] === null) ? unreadable
      : heldN ? `<div class="pd-sub"><b>${fmt(heldN)}</b><span>${heldN === 1 ? "request" : "requests"} held</span></div>` : DRAW.nothing("Nothing held"),
    keep: `<span class="taps">${taps.map((h) => `<span class="tap">${cmdChip(h)}<span class="fixed">Last ${tapOf(h)}</span></span>`).join("")}</span>`,
    sw: `<span class="count">${taps.length} of ${ui.heads.length} commands</span><span class="marks">${KNOBS.markHtml("lock", "Set per command in splice.toml")}${KNOBS.markHtml("restart")}</span>`,
  }) : "";
  const bytes = (g) => DATA_ROWS.filter((r) => r.g === g).reduce((s, r) => s + (heldOf(r)?.bytes ?? 0), 0);
  const unreadIn = (g) => DATA_ROWS.some((r) => r.g === g && !(r.noRoute || r.own) && heldOf(r) === null);
  const sum = (g) => (DATA_ROWS.some((r) => r.g === g && stillReading(r)) ? GHOST.fig(4) : unreadIn(g) ? unreadable : `<span class="hn">${esc(mbWord(bytes(g)))}</span>`);
  // each store is built once: its card goes to its group, its Delete to Free up space
  const built = DATA_ROWS.map((r) => ({ r, p: dataRow(r) }));
  // a group's cards, biggest first, then the ones holding nothing yet, in the order they are listed
  const order = (b) => -(heldOf(b.r)?.bytes ?? -1);
  const cards = (g, extra = []) => `<div class="dcards ${esc(g)}">${built.filter((b) => b.r.g === g).sort((a, b) => order(a) - order(b)).map((b) => b.p.html).join("")}${extra.join("")}</div>`;
  const legend = (g) => { const items = KNOBS.legendHtml(groupKnobs(g, !!memory), { bare: true }); return items ? `<span class="legend">${items}</span>` : ""; };
  // conversation text is his prompts word for word, which is said once, on the group, instead of once per card
  const text = DRAW.section("Conversation text", cards("text", [memory ? memory.html : "", read.html]), { aside: `<span class="gaside"><span class="yours">Your prompts</span>${sum("text")}${legend("text")}</span>` });
  const records = DRAW.section("Records", cards("records"), { aside: `<span class="gaside">${sum("records")}${legend("records")}</span>`, cls: "drec" });
  return keptHtml() + text + records + freeHtml(built);
}
const TV_OFF = "When off, Sessions, Requests and Teams show no conversations · Questions are answered in the terminal · Nothing is deleted";
/** Every delete in one place, away from the cards, biggest first, each store's size on one scale with its colour. */
function freeHtml(built) {
  // a store still being read holds its row with its name, its size and its Delete as ghosts, so the list does not grow
  // from one row to seven as the sizes land (the slow door, Oct 26)
  const rows = built.filter((b) => (b.p.del && b.p.del.trim()) || stillReading(b.r)).map((b) => ({ b, x: heldOf(b.r) })).sort((a, z) => (z.x?.bytes ?? -1) - (a.x?.bytes ?? -1));
  if (!rows.length) return "";
  const top = Math.max(1, ...rows.map((f) => f.x?.bytes ?? 0));
  const tile = ({ b, x }) => {
    const hue = hueOf(b.r);
    const nm = `<span class="nm">${hue ? `<i class="dsw" style="${hue}" aria-hidden="true"></i>` : ""}${esc(b.r.name)}</span>`;
    if (stillReading(b.r)) return `<div class="dfrow">${nm}${GHOST.track()}<span class="dfa">${GHOST.bar(5.5)}</span></div>`;
    return `<div class="dfrow">${nm}${x && x.bytes ? DRAW.barCell({ w: (x.bytes / top) * 100, text: esc(mbWord(x.bytes)) }) : "<span></span>"}<span class="dfa">${b.p.del}</span></div>`;
  };
  return `<section class="psec dfree"><div class="sechead"><h3 class="kicker">Free up space</h3><span class="hn">Deleting cannot be undone</span></div>`
    + `<div class="dfrows cols" style="--n:${perRow(rows.length)}">${rows.map(tile).join("")}</div></section>`;
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
/** Two parts of a topic side by side once the plate holds both whole, one over the other below that (settings.css
 *  .pair). "lead" gives the first part the larger share, for a table beside a short list of settings. */
const pair = (left, right, cls = "") => (left && right
  ? `<div class="pair${cls ? ` ${cls}` : ""}"><div class="pcol">${left}</div><div class="pcol">${right}</div></div>` : left || right);
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
  if (all === 0) return DRAW.hero({ figure: "0", of: "requests this week" });
  return DRAW.hero(all
    ? { figure: DRAW.pct(n / all), level: levelOf(n / all, 0.02, 0.1), count: n, word: "ended early", of: `of ${DRAW.n(all)} requests this week` }
    : { figure: DRAW.n(n), level: n ? "bad" : "ok", of: "ended early this week" });
}
function stallRow(why, what, top, early) {
  const per = ui.week[why] ?? [], total = weekTotal(why), ask = { win: "7d", why };
  const cells = (cls, bar, count, cmds, last) => `<div class="orow${cls}" role="row"><span class="ow" role="cell">${esc(what)}</span>`
    + `<span class="obar" role="cell">${bar}</span><span class="on r" role="cell">${count}</span><span class="ocmds" role="cell">${cmds}</span><span class="olast" role="cell">${last}</span></div>`;
  if (total === null) {
    console.warn(`${what}: ${per.find((p) => p.err)?.err ?? "splice did not answer"}`);
    return cells(" unread", "", `<span class="state limit">Not read</span>`, "", "");
  }
  if (!total) return cells(" zero", "", "0", "", "");
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
  const ax = durAxis([...msOf(keys), ...(mark ? [mark.ms] : [])], "Without progress, log scale");
  const draw = Object.fromEntries(keys.filter((key) => KNOB[key].c.k === "dur").map((key) => [key, durDraw]));
  return knobSec("How long splice waits", keys, { ax, draw, mark });
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
const peakHead = () => `<div class="pk ph" role="row"><span role="columnheader">Command</span><span role="columnheader">At once, of its limit</span>`
  + `<span role="columnheader" class="pdh">${esc(zoneHead("Busiest at"))}</span><span role="columnheader" class="r">Requests</span><span role="columnheader" class="r">Waited in line</span></div>`;
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
function peakRow(p, maxN, now) {
  const key = p.head.key, lim = limitOf(key), full = lim > 0 && p.peak >= lim;
  const q = (ui.week.queued || []).find((x) => x.head.key === key), queued = q && !q.err ? q.n : p.queued || 0;
  const meter = lim ? DRAW.meter(Math.min(100, (p.peak / lim) * 100), full ? "full" : "") : "";
  return `<div class="pk" role="row" data-grow="peak-${esc(key)}" style="--c:${colorOf(ks.providerOf[key])}">`
    + `<span role="cell"><a class="pcmd" href="${reqUrl({ from: today(), cmd: p.head.command })}">${cmdChip(key)}</a></span>`
    + `<span class="pm" role="cell">${meter}<span class="pmv${full ? " lv-bad" : ""}"><b>${fmt(p.peak)}</b><i>${lim ? `of ${fmt(lim)}` : "no limit"}</i></span></span>`
    + `<span class="pclk" role="cell">${esc(clock(new Date(p.at)))}</span>`
    + `<span class="pday" aria-hidden="true"><i class="pfut" style="left:${now.toFixed(2)}%"></i><i class="pdot" style="left:${dayAt(p.at).toFixed(2)}%"></i></span>`
    + `<span role="cell">${DRAW.barCell({ w: ((p.n || 0) / maxN) * 100, text: fmt(p.n || 0) })}</span>`
    + `<span class="pq r" role="cell">${queued ? `<a class="pd-count" href="${reqUrl({ from: today(), why: "queued", cmd: p.head.command })}"><b>${fmt(queued)}</b>${DRAW.go}</a>` : `<span class="pq0">0</span>`}</span></div>`;
}
function peaksTable() {
  if (ui.peaks === undefined) return peaksGhost();
  if (!ui.peaks.length) return "";
  const rows = [...ui.peaks].sort((a, b) => (b.peak ?? -1) - (a.peak ?? -1));
  // the commands that ran today are rows; the ones that held nothing are one line naming them, and a command whose
  // peak was not read says so beside its chip, its reason in the console (fin, Oct 12)
  const busy = rows.filter((p) => !p.err && p.peak > 0), idle = rows.filter((p) => !p.err && !p.peak), bad = rows.filter((p) => p.err);
  const maxN = Math.max(1, ...busy.map((p) => p.n || 0)), now = dayAt(Date.now());
  const ticks = DAY_TICKS.map(([x, w]) => `<span class="pd-tk${x ? "" : " pd-t-first"}" style="left:${x}%">${w}</span>`).join("");
  const table = busy.length ? `<div class="peaks" role="table" aria-label="Today's busiest moment per command">${peakHead()}${busy.map((p) => peakRow(p, maxN, now)).join("")}`
    + `<div class="pk pt" aria-hidden="true"><span class="pdax pd-ax">${ticks}</span></div></div>` : "";
  const quiet = idle.length ? `<p class="pidle"><span>No requests today</span>${idle.map((p) => cmdChip(p.head.key)).join("")}</p>` : "";
  const errs = [...new Set(bad.map((p) => p.err))].map((err) => `<p class="pidle"><span class="state limit">${esc(err)}</span>${bad.filter((p) => p.err === err).map((p) => cmdChip(p.head.key)).join("")}</p>`).join("");
  return table + quiet + errs;
}
const peaksGhost = () => `<div class="peaks" aria-busy="true">${peakHead()}${(ui.headRows.length ? ui.headRows : [0, 1, 2]).slice(0, 6).map(() => `<div class="pk" aria-hidden="true">`
  + `<span>${GHOST.chip()}</span><span class="pm">${GHOST.track()}</span><span class="pclk">${GHOST.bar(3.6)}</span><span class="pday"></span>`
  + `<span>${GHOST.cell()}</span><span class="pq r">${GHOST.fig(1.4)}</span></div>`).join("")}</div>`;
/** How many waited in line today, as a door to them, on the section's kicker line. */
function queuedAside() {
  if (weekReading("queued")) return "";
  const q = weekTotal("queued");
  if (q === null) return `<span class="pd-count"><span class="state limit">Not read</span> waited in line</span>`;
  return q ? DRAW.countDoor(q, "waited in line today", reqUrl({ from: today(), why: "queued" })) : `<span class="pd-count"><b>0</b> waited in line today</span>`;
}
/** What the providers pushed back with today: each kind's count against the day's requests as a door to them, then
 *  each command's share of it on one scale. A kind that came back zero, or unread, is one quiet line. */
function pushback() {
  if (weekReading("overloaded", "cutoff", "allday")) return `${GHOST.focal(16)}${GHOST.row([GHOST.chip(), GHOST.cell()])}`;
  const all = weekTotal("allday"), parts = [], quiet = [];
  for (const [why, what] of [["overloaded", "overloaded"], ["cutoff", "cut off"]]) {
    const per = ui.week[why] ?? [], total = weekTotal(why);
    if (total === null) {
      console.warn(`${what} today: ${per.find((p) => p.err)?.err ?? "splice did not answer"}`);
      quiet.push(`<span class="fig"><span class="state limit">Not read</span> ${esc(what)}</span>`);
      continue;
    }
    if (!total) { quiet.push(`<span class="fig"><b>0</b> ${esc(what)}</span>`); continue; }
    const shares = per.filter((p) => !p.err && p.n).sort((a, b) => b.n - a.n), top = shares[0]?.n || 1;
    parts.push(`<div class="pb"><a class="pd-sub pbfig" href="${reqUrl({ from: today(), why })}"><b>${fmt(total)}</b>${esc(what)}${all ? ` of ${DRAW.n(all)} requests` : ""}${DRAW.go}</a>`
      + `<div class="pbrows">${shares.map((p) => `<a class="pbrow" href="${reqUrl({ from: today(), why, cmd: p.head.command })}">${cmdChip(p.head.key)}${DRAW.barCell({ w: (p.n / top) * 100, text: fmt(p.n) })}</a>`).join("")}</div></div>`);
  }
  return parts.join("") + (quiet.length ? `<p class="zeros">${quiet.join('<span class="dot">·</span>')}</p>` : "");
}
const busyHtml = () => pair(DRAW.section("Today", `${busyHero()}${peaksTable()}`, { aside: queuedAside() }),
  knobSec("splice's limits", LIMITS.filter((key) => keysOf("busy").includes(key))), "lead")
  + DRAW.section("Providers today", pushback());

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
  const where = val("showReasoning"), sum = val("summary"), prog = flagOn("progressLine");
  const n = where === "off" || sum === "none" ? 0 : sum === "detailed" ? 2 : 1;
  const tie = (key, gone) => `<span class="tie">${esc(KNOB[key]?.label ?? "")}${gone ? `<em>${esc(gone)}</em>` : ""}</span>`;
  const line = (cls, body, key = "", gone = "") => `<div class="tl ${cls}${gone ? " gone" : ""}"${key ? ` data-tie="${key}"` : ""}>`
    + `<span class="tx">${gone ? "" : body}</span>${key ? tie(key, gone) : "<span></span>"}</div>`;
  const wire = (w, cls = "") => `<i class="wire${cls ? ` ${cls}` : ""}" style="width:${w}%"></i>`;
  // the bullet the terminal prints before a block of its own; named apart from the rows' own mark (knobs.css .mk)
  const dot = `<b class="bul">⏺</b>`;
  // in the thinking block the reasoning is indented under Thinking and muted; in the reply it is the reply's own text
  const reasoning = n
    ? [62, 44].slice(0, n).map((w, i) => line(`rs ${where === "thinking" ? "in" : "out"}`, `${where === "text" && !i ? dot : ""}${wire(w)}`, i ? "" : "summary")).join("")
    : line("rs", "", "summary", where === "off" ? word(KNOB.showReasoning, where) : word(KNOB.summary, sum));
  const said = `${where === "off" ? "Reasoning hidden" : `Reasoning ${word(KNOB.summary, sum).toLowerCase()}, ${word(KNOB.showReasoning, where).toLowerCase()}`}; waiting line ${prog ? "on" : "off"}`;
  return `<div class="term" role="img" aria-label="${esc(said)}"><div class="tbar" aria-hidden="true"><i></i><i></i><i></i></div>`
    + line("you", `<b class="pr">$</b>claude`)
    + line("think", `${dot}Thinking…`, "showReasoning", where === "off" ? word(KNOB.showReasoning, where) : "")
    + reasoning
    + line("wait", `<i class="spin"></i>${wire(8, "s")}<b class="sep">·</b>${wire(6, "s")}<b class="sep">·</b>${wire(7, "s")}`, "progressLine", prog ? "" : word(KNOB.progressLine, false))
    + line("reply", `${dot}${wire(70)}`)
    + `</div>`;
}
const termGhost = () => `<div class="term" aria-busy="true"><div class="tbar" aria-hidden="true"><i></i><i></i><i></i></div>`
  + [24, 40, 62, 44, 30, 70].map((w) => `<div class="tl" aria-hidden="true"><span class="tx">${GHOST.bar(w / 4)}</span><span></span></div>`).join("") + `</div>`;
const reasoningHtml = () => pair(DRAW.section("In your terminal", terminal()),
  knobSec("How reasoning shows", SHOWS.filter((key) => keysOf("reasoning").includes(key))));

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
  const warn = warnPct(), back = top.w.resetMs ? `Resets ${backWord(new Date(top.w.resetMs))}` : "";
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
function planWin(w, warn) {
  const label = `<span class="label">${w.label}</span>`;
  if (w.rolledMs != null) return `${label}${DRAW.meter(null, "gone")}<span class="pct">${DRAW.dash}</span><span class="when rolled">Ended ${esc(backWord(new Date(w.rolledMs)))}</span>`;
  const full = w.used >= 100;
  return `${label}<span class="wmeter">${DRAW.meter(Math.min(100, w.used), `${full ? "full" : ""}${w.stale ? " stale" : ""}`)}<i class="wmk" style="left:${warn}%"></i></span>`
    + `<span class="pct${full ? " limit" : ""}">${w.used}%<span class="used">used</span></span>`
    + (w.left != null ? `<span class="when">${DRAW.ring(w.left / w.len)}Resets ${esc(backWord(new Date(w.resetMs)))}</span>` : "<span></span>");
}
function planCards() {
  const a = ui.accts;
  if (a === undefined) return planGhost();
  if (!a) return `<p class="pidle"><span class="state limit">Not read</span></p>`;
  const warn = warnPct(), out = a.filter(signedOut);
  const cards = planLogins().map(({ x }) => `<article class="plog" style="--c:${colorOf(familyOf(x.provider))}" data-grow="plan">`
    + `<header title="${esc(loginFull(x))}"><span class="blot"></span><span class="pln">${esc(loginWord(x))}</span></header>`
    + `<div class="windows">${windowsOf(x).map((w) => planWin(w, warn)).join("")}</div></article>`);
  // a signed-out login's last reading is from before, so it stands as its chip under the state, never as windows
  const signed = out.length ? `<p class="pidle"><span>Signed out</span>${out.map(loginChip).join("")}</p>` : "";
  if (!cards.length) {
    // "No limits reported" is the providers answering with none; a login splice has never read is Not read, since this
    // page asks for no reading of its own (marlin, Oct 12)
    const never = a.some((x) => !x.refusal && x.credential_present !== false && !x.observed_at_epoch_seconds && !x.no_usage_at_epoch_seconds);
    return `<p class="pidle"><span class="state">${never ? "Not read" : "No limits reported"}</span></p>${signed}`;
  }
  return DRAW.cols(cards, 26, "plogs") + signed;
}
const planGhost = () => DRAW.cols([0, 1, 2].map((i) => `<article class="plog ghosted" style="--i:${i}" aria-hidden="true"><header>${GHOST.bar(8)}</header>`
  + `<div class="windows">${[0, 1].map(() => `${GHOST.bar(3.4)}${GHOST.track()}<span class="pct">${GHOST.fig(4.2)}</span><span class="when">${GHOST.ring()}${GHOST.bar(6.2)}</span>`).join("")}</div></article>`), 26, "plogs");
/** The warn line on a plan's whole, with every login a dot at its highest running window, in its provider's colour.
 *  Dots that would touch take a lane under the one before, so each stays a dot of its own. */
function warnDraw() {
  if (ui.accts === undefined) return GHOST.track();
  const warn = warnPct(), lanes = [];
  const dots = planLogins().filter((l) => l.top).sort((a, b) => a.top.used - b.top.used).map((l) => {
    let lane = lanes.findIndex((last) => l.top.used - last >= 4);
    if (lane < 0) lane = Math.min(lanes.length, 2);
    lanes[lane] = l.top.used;
    return { ...l, lane };
  });
  const said = `Warns at ${warn}%${dots.length ? `; ${dots.map((d) => `${loginWord(d.x)} ${d.top.used}%`).join(", ")}` : ""}`;
  return `<span class="wtrk" style="--lanes:${Math.max(1, lanes.length)}" role="img" aria-label="${esc(said)}"><i class="wzone" style="left:${warn}%"></i>`
    + dots.map((d) => `<i class="ldot${d.top.used >= warn ? " over" : ""}" style="left:${Math.min(100, d.top.used)}%;--lane:${d.lane};--c:${colorOf(familyOf(d.x.provider))}" title="${esc(`${loginWord(d.x)} · ${d.top.used}% of its ${lenWord(d.top)} limit`)}"></i>`).join("")
    + `</span>`;
}
const planHtml = () => pair(DRAW.section("Right now", `${planHero()}${planCards()}`, { aside: planAside() }), knobSec("When splice warns", keysOf("plan")));

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
const fromHtml = (s) => !s.project && !s.from && !s.address ? "" : `<span class="from" title="${esc(s.path || s.from)}">${SV.file}${s.project ? `<span class="chip">${esc(s.project)}</span>` : `<span class="ft">${esc(s.from)}</span>`}${s.address ? `<code>${esc(s.address)}</code>` : ""}</span>`;
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
  return xs.length ? { copies: xs.reduce((n, c) => n + (c.copies || 0), 0), now: xs.reduce((n, c) => n + (c.bytes_now || 0), 0), held: xs.flatMap((c) => c.held_by || []) } : null;
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
const SRV_HEAD = `<div class="srvhead"><span></span><span></span><span>Used by</span><span>Memory</span><span>Started</span><span>Last call</span><span></span></div>`;
function sharedRow(s) {
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
  // what its one copy holds now, as the census measures every copy on the machine, so this row and What sharing saves
  // read one figure; /proc's own reading where the census has none, Running where neither does. A web address runs no
  // process here, so its figure is the dash every figure without a reading is.
  const mem = s.web ? DRAW.dash : c?.copies ? `<b>${esc(mbWord(c.now))}</b>${c.copies > 1 ? ` <span>${copiesWord(c.copies)}</span>` : ""}`
    : s.hosted ? (s.rss ? `<b>${esc(mbWord(s.rss))}</b>` : "Running") : `<span class="state">Not running</span>`;
  const when = (ms) => (ms ? esc(whenWord(ms)) : DRAW.dash);
  const err = s.err ? `<span class="serr" title="${esc(s.err)}">${esc(shortWhy(s.err))}</span>` : "";
  const test = `${testSaid(s.name)}<button class="act quiet small" data-act="mtest" data-name="${esc(s.name)}"${ui.mtest[s.name] === "running" ? " disabled" : ""}>Test</button>`;
  return `<div class="srv${s.err ? " err" : ""}">${lampOf(s)}<div class="sname"><span class="nm">${hl(s.name)}</span>${fromHtml(s)}${err}</div>`
    + `<span class="fig uses">${used}</span><span class="fig mem">${mem}</span><span class="fig t">${when(s.started)}</span><span class="fig t">${when(s.last)}</span>`
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
function outItem(s, lines) {
  const m = MCP_STATE[s.state], c = censusOf(s.name);
  const cmd = m?.cmd && s.cmd ? cmdChip(s.cmd) : "";
  const code = m && s.value && s.state !== "added_in" ? `<code>${esc(codeOf(s.value))}</code>` : "";
  const why = cmd || code || (lines && m?.line) ? `<div class="why">${cmd || code ? `<span class="w">${cmd}${code}</span>` : ""}${lines && m?.line ? `<span class="wl">${esc(m.line)}</span>` : ""}</div>` : "";
  // what its copies hold now, one per session: drawn only where there are copies, never a row of zeros
  const mem = c?.copies ? `<span class="fig mem"><b>${esc(mbWord(c.now))}</b> <span>${copiesWord(c.copies)}</span>${heldHtml(s, c)}</span>` : "";
  const acts = s.state === "excluded" ? `<span class="acts">${exAct(s.name, false)}</span>` : "";
  return `<div class="srv out"><div class="sname"><span class="nm">${hl(s.name)}</span>${fromHtml(s)}</div>${why}${mem}${acts}</div>`;
}
function outCard([word, xs]) {
  // a reason whose servers all carry the same line says it once, under the reason, instead of on every server
  const lines = [...new Set(xs.map((s) => MCP_STATE[s.state]?.line || ""))], once = lines.length === 1 ? lines[0] : "";
  const open = !!ui.mmore[word], shown = open ? xs : xs.slice(0, capOf(xs.length, MCP_SHOWN));
  const more = xs.length > MCP_SHOWN + 1 ? DRAW.more(xs.length - MCP_SHOWN, { act: "mmore", data: ` data-id="${esc(word)}"`, open }) : "";
  return `<article class="mcard"><header class="mtop"><span class="mw">${esc(word)}</span><b class="mn">${fmt(xs.length)}</b></header>`
    + `${once ? `<p class="ml">${esc(once)}</p>` : ""}<div class="srvs out">${shown.map((s) => outItem(s, !once)).join("")}</div>${more}</article>`;
}
/** What sharing saves, from the census: every copy of every server sharing would serve, against one copy of each. */
function savedHtml() {
  const m = ui.mmem;
  const aside = m?.claude_processes ? `<span class="hn"><b>${fmt(m.claude_processes)}</b> Claude Code processes</span>` : "";
  if (m === undefined) return DRAW.section("What sharing saves", `${GHOST.row([GHOST.fig(6), GHOST.bar(9)])}<div class="msave">${GHOST.track()}${GHOST.track()}</div>`);
  if (!m) return DRAW.section("What sharing saves", `<div class="pd-sub"><b>${DRAW.dash}</b>${unreadable}</div>`);
  // each server carries its class from the host's own sharing plan, unless the list keeps it unshared (asListed)
  const byName = new Map((ui.mcp ? mcpServerRows(ui.mcp) : []).map((r) => [r.name, r]));
  const share = (m.servers || []).map((s) => asListed(s, byName)).filter((s) => shareable(s) && s.copies);
  if (!share.length) return DRAW.section("What sharing saves", DRAW.nothing("No copies running"), { aside });
  const now = share.reduce((n, s) => n + s.bytes_now, 0), one = share.reduce((n, s) => n + s.bytes_if_shared, 0);
  const copies = share.reduce((n, s) => n + s.copies, 0), top = Math.max(now, one, 1);
  // the census measures what runs now, so the saving it can show is what one copy of each would free from it: once each
  // runs one copy there is nothing more to free, which is sharing working, never "sharing saves nothing" (Oct 26)
  const saves = now - one > 0 ? DRAW.focal(mbWord(now - one), "sharing would free") : DRAW.nothing("Nothing more to free");
  const bar = (k, b) => `<div class="mbar"><span class="k">${k}</span>${DRAW.barCell({ w: (b / top) * 100, text: esc(mbWord(b)) })}</div>`;
  const each = `${fmt(share.length)} ${share.length === 1 ? "server" : "servers"}, one copy each`;
  return DRAW.section("What sharing saves", `${saves}<div class="msave">${bar(`${copiesWord(copies)} now`, now)}${bar(each, one)}</div>`, { aside });
}
// the server timers in the order they bite: how many may run, how long one may take to start, to answer, to sit unused
const MCP_ORDER = ["mcpMaxServers", "mcpInitializeTimeoutMs", "mcpRequestTimeoutMs", "mcpIdleTimeoutMs"];
const mcpKeys = () => { const keys = keysOf("mcp"); return [...MCP_ORDER.filter((k) => keys.includes(k)), ...keys.filter((k) => !MCP_ORDER.includes(k))]; };
// the reasons land as cards, so their place is held by two cards of their shape: a name and its count, then servers
const mcardsGhost = () => `<div class="mcards">${[0, 1].map(() => `<article class="mcard" aria-hidden="true"><header class="mtop">${GHOST.bar(8)}${GHOST.fig(1.6)}</header>${GHOST.lines([9, 12, 8, 11])}</article>`).join("")}</div>`;
/** The topic: how many servers are shared and why the rest are not, the shared ones, the rest by reason, what sharing
 *  saves, and the timers. Each part draws its own shape while its read is out. */
function mcpHtml() {
  const m = ui.mcp, aside = shareSwitch();
  const limits = knobSec("Server limits", mcpKeys());
  if (m === undefined) {
    const ghost = [0, 1, 2].map(() => GHOST.row([GHOST.tile(), GHOST.bar(9), GHOST.bar(15), GHOST.bar(5), GHOST.bar(8)])).join("");
    return DRAW.section("Sharing", `${heroGhost()}${GHOST.stack()}`, { aside, cls: "tally" }) + DRAW.section("Shared", `<div class="ghost-rows">${ghost}</div>`)
      + DRAW.section("Not shared", mcardsGhost()) + savedHtml() + limits;
  }
  // the route is mounted only while splice hosts MCP (ControlServer.kt), so its 404 is the honest "nothing is hosted"
  if (m === false) return DRAW.section("Sharing", DRAW.nothing("splice runs no MCP servers"), { aside, cls: "tally" }) + savedHtml() + limits;
  if (!m) return DRAW.section("Sharing", `<div class="pd-top"><b class="pd-rate">${DRAW.dash}</b><span class="pd-failed"><span>servers shared</span></span>${unreadable}</div>`, { aside, cls: "tally" }) + savedHtml() + limits;
  const rows = mcpServerRows(m), shared = rows.filter((r) => r.shared), groups = outGroups(rows);
  const hosted = shared.filter((r) => !r.web).length, web = shared.length - hosted;
  // one server is written in several files (a head's copy of the canonical home, a repo's own entry), so the figure is
  // how many SERVERS he set up, and the entries splice read are said once, at the end of the bar's names
  const entries = (m.sources?.servers || []).length;
  const tail = entries > rows.length ? `<span class="pd-lb quiet"><b>${fmt(entries)}</b> entries in your files</span>` : "";
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
  const list = shared.length ? DRAW.section("Shared", `<div class="srvs${m.hosting === false ? " dim" : ""}">${SRV_HEAD}${shared.map(sharedRow).join("")}</div>`, { aside: upAside }) : "";
  const out = groups.length ? DRAW.section("Not shared", `<div class="mcards">${groups.map(outCard).join("")}</div>`) : "";
  return DRAW.section("Sharing", top, { aside, cls: "tally" }) + list + out + savedHtml() + limits;
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
const vnode = (label, cls, body) => `<article class="vcard${cls}"><span class="vk">${esc(label)}</span>${body}</article>`;
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
    return DRAW.section("Versions", `<div class="vline">${ghost(5)}${ghost(8)}</div>`) + filesHtml();
  }
  // the word the Version job's own rail line uses; why is warned where it was read (fin)
  if (!v) return DRAW.section("Versions", `<div class="vline">${vnode("Running", " now", `<b class="v">${DRAW.dash}</b>${unreadable}`)}</div>`) + filesHtml();
  const back = v.rollback_target, run = ui.run, going = run && run.state === "running";
  // "Not checked" sits where "Checked Oct 12, 4:10 AM CDT" would, in the same small line, so the version stays the
  // largest thing on the card (Marlin with hitstop, Oct 12)
  const checked = `<p class="kel">${v.checked_at_epoch_millis ? `Checked ${esc(whenWord(v.checked_at_epoch_millis))}` : "Not checked"}</p>`;
  // A STATE AND AN ACT, NOT A SENTENCE (fin, Marlin approved, Oct 12): "Not read" when a tap answered nothing, "Newest"
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
  return DRAW.section("Versions", `<div class="vline">${prev}${now}${next}</div>${runHtml(run, back)}`) + filesHtml();
}
// WHERE SPLICE KEEPS ITS FILES, shown and never edited (v0.4.0 section 9, Resolved: computed paths are inspectable, not
// editable). The daemon resolved them (GET /api/config paths), so an older daemon without the field draws nothing. Each
// reads from his home as ~, the way he would type it; the whole path is its title.
function filesHtml() {
  const p = ks.cfg?.paths;
  if (!p) return "";
  const line = (name, path) => (path ? `<div class="vfile"><span class="nm">${esc(name)}</span><code title="${esc(path)}">${esc(homeWord(path))}</code></div>` : "");
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
 *  here), and the second press restarts. fin's words, Marlin approved; Check steps aside while it is armed. What a restart waits for is said on the button, because a daemon that waits on a compaction looks
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
    makes: (ui.projects?.projects || []).filter((p) => p.folder === path).map((p) => p.name || p.label).filter(Boolean).sort(),
  }));
};
// THE PROJECTS ARE THE TOPIC'S CONTENT (Oct 26): four names and "+31" stood in a column 1,600 px wide at 3822, so he
// clicked to read what splice groups his sessions by. They show whole up to forty, the rest behind one pill.
const PROJECTS_SHOWN = 40;
/** A list of project names as chips, up to forty, and the rest behind one pill that opens them in place. */
function projectChips(names, id) {
  // a list one over the cap shows whole, as a server's commands do (capOf): a "+1" pill is as big as the name it hides
  const open = !!ui.pmore[id], shown = open ? names : names.slice(0, capOf(names.length, PROJECTS_SHOWN));
  const more = names.length > PROJECTS_SHOWN + 1 ? DRAW.more(names.length - PROJECTS_SHOWN, { act: "pmore", data: ` data-id="${esc(id)}"`, open, pill: !open }) : "";
  return `${shown.map((n) => `<span class="mchip quiet">${hl(n)}</span>`).join("")}${more}`;
}
/** The projects there are, as the topic's figure, and where they come from: one bar split by the folder that makes
 *  each (and the repos no folder holds), only where there is more than one source to split. */
function projectsTop(p, folders) {
  const all = p.projects || [];
  if (!all.length) return DRAW.nothing(folders.length ? "No projects in these folders yet" : "No git repos seen yet");
  const from = all.filter((x) => x.folder), repos = all.filter((x) => !x.folder);
  const hero = DRAW.hero({ figure: fmt(all.length), of: folders.length ? `projects from ${fmt(folders.length)} ${folders.length === 1 ? "folder" : "folders"}` : "projects, one per git repo" });
  const parts = [...folders.map((f, i) => ({ n: f.makes.length, word: f.path, text: fmt(f.makes.length), ink: storeInk(i) })),
    ...(from.length && repos.length ? [{ n: repos.length, word: "Git repos", text: fmt(repos.length), cls: "rest" }] : [])];
  const split = parts.filter((x) => x.n > 0).length > 1 ? splitBar(parts, all.length, { id: "projects" }) : "";
  // with no folder saved every project is a repo, and those are the chips; with folders, each path carries its own
  const chips = !folders.length ? `<div class="pmakes">${projectChips(repos.map((x) => x.name || x.label).filter(Boolean).sort(), "repo")}</div>` : "";
  return `${hero}${split}${chips}`;
}
function pathRow(f) {
  const armed = ui.armed === `folder:${f.path}`;
  // REMOVAL ARMS IN PLACE (Oct 26): the × turns into the act it would take and the way out, on the path it removes
  const drop = armed
    ? `<span class="armed"><button class="act danger small" data-act="dropfolder" data-path="${esc(f.path)}">Remove</button><button class="act quiet small" data-act="disarm">Keep</button></span>`
    : `<button class="icon" data-act="armfolder" data-path="${esc(f.path)}" aria-label="Remove ${esc(f.path)}">${ICON.close}</button>`;
  // a folder that has vanished still holds its place: splice never stops over it, and its sessions fall back to repos
  const gone = f.found === false ? `<span class="state limit">Not found</span>` : "";
  const makes = f.found === null ? GHOST.row([GHOST.bar(6), GHOST.bar(7)]) : f.makes.length ? projectChips(f.makes, f.path) : "";
  return `<div class="prow${armed ? " arming" : ""}"><span class="ppath"><code>${hl(f.path)}</code>${drop}</span>${gone}${makes ? `<div class="pmakes">${makes}</div>` : ""}</div>`;
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
function projectsHtml() {
  const k = KNOB.projectsFolders, folders = folderFacts(), p = ui.projects;
  const top = p === undefined ? `${heroGhost()}${GHOST.row([GHOST.bar(7), GHOST.bar(8), GHOST.bar(6), GHOST.bar(7)])}`
    : p === null ? `<div class="pd-top"><b class="pd-rate">${DRAW.dash}</b><span class="pd-failed"><span>projects</span></span>${unreadable}</div>`
    : projectsTop(p, folders);
  // the folders are one setting: its name is the section's kicker, lit by a search the way a setting's name is
  const list = `<div class="plist">${folders.map(pathRow).join("")}${addFolder()}</div>`;
  // the projects and the folders that make them are one pair: side by side once the plate holds two columns (settings.css)
  return `<div class="psec pcols"><div class="pin">${DRAW.section("Projects", top, { cls: "tally" })}`
    + `<section class="psec pfolders${lit(knobHay(k))}"><div class="sechead"><h3 class="kicker" data-row="${k.key}">${hl(k.label)}</h3>${saidHtml(k.key)}</div>${list}</section></div></div>`;
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
  const h = DATA_ROWS.filter((r) => !(r.noRoute || r.own)).map(heldOf), tag = DATA_ROWS.some((r) => r.noRoute || r.own) ? "counted" : "kept"; // a total that leaves a store out says counted
  const stop = traceStopped();
  return h.some((x) => x === null) ? ["Not read"] : [...(stop ? [`Saving stopped ${stop.when}`] : []), `${mbWord(h.reduce((s, x) => s + x.bytes, 0))} ${tag}`];
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
    if (weekReading("restarted", "gaveup", "waited")) return GHOST.row([GHOST.bar(6), GHOST.bar(4)]);
    const early = ["restarted", "gaveup"].map(weekTotal), waited = weekTotal("waited");
    if (early.includes(null) || waited === null) return "";
    const n = early[0] + early[1], top = Math.max(n, waited, 1);
    return `<div class="rmini">${DRAW.miniPair({ label: "Ended early", figure: DRAW.n(n), w: (n / top) * 100, cls: "row", level: n ? "bad" : "" })}`
      + `${DRAW.miniPair({ label: "Waited out", figure: DRAW.n(waited), w: (waited / top) * 100, cls: "row" })}</div>`;
  }
  if (id === "busy") {
    if (ui.peaks === undefined) return GHOST.row([GHOST.bar(6), GHOST.track()]);
    const rows = (ui.peaks || []).filter((p) => !p.err && p.peak > 0);
    const over = weekTotal("overloaded");
    const share = (p) => { const lim = limitOf(p.head.key); return lim ? p.peak / lim : 0; };
    const top = [...rows].sort((a, b) => share(b) - share(a) || b.peak - a.peak)[0];
    if (!top) return over ? `<div class="rmini">${DRAW.miniPair({ label: "Overloaded today", figure: DRAW.n(over), w: 100, cls: "row", level: "bad" })}</div>` : "";
    const lim = limitOf(top.head.key), pc = lim ? Math.min(100, (100 * top.peak) / lim) : 100;
    // the overloaded count is a handful against a day of thousands: a bar of it would draw a hairline and say nothing,
    // so it stands as the figure itself, in the limit red, beside the share that a bar does say something about
    return `<div class="rmini">${DRAW.miniPair({ label: "Busiest at once", figure: lim ? `${DRAW.n(top.peak)}/${DRAW.n(lim)}` : DRAW.n(top.peak), w: pc, cls: "row", level: lim && top.peak >= lim ? "bad" : "" })}`
      + (over ? `<p class="rfig"><b class="lv-bad">${DRAW.n(over)}</b>Overloaded today</p>` : "") + `</div>`;
  }
  if (id === "plan") {
    if (ui.accts === undefined) return GHOST.row([GHOST.bar(5), GHOST.track()]);
    const top = planTop();
    if (!top) return "";
    const warn = Number(val("usageWarnPct")) || 80;
    // two quantities on one bar: how close the highest plan is, and where the warn line sits (the kit's own tick)
    return `<div class="rmini">${DRAW.miniPair({ label: "Highest plan", figure: `${top.p}%`, w: Math.min(100, top.p),
      cls: "row", mark: warn, markTitle: `Warns at ${warn}%`, level: top.p >= 100 ? "bad" : "" })}</div>`;
  }
  // MCP servers: the share of his servers that are shared, on its whole, how many run, and a failure in the limit red
  if (id === "mcp") {
    if (ui.mcp === undefined) return GHOST.row([GHOST.bar(5), GHOST.track()]);
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
    if (ui.projects === undefined) return GHOST.row([GHOST.bar(6), GHOST.bar(5)]);
    const n = ui.projects ? (ui.projects.projects || []).length : null, f = foldersNow();
    // the count and its rule share one line, as Version's two figures do: stacked, the rail ran 14 px under the
    // composer at 1440 x 900 once every card had read (Oct 26)
    const rule = `<div class="meta">${(f.length ? f : ["One per repo"]).map((w) => `<span>${esc(w)}</span>`).join("")}</div>`;
    return `<div class="rfigs">${n === null ? RAIL_UNREAD : n ? `<p class="rfig"><b>${DRAW.n(n)}</b>${n === 1 ? "project" : "projects"}</p>` : ""}${rule}</div>`;
  }
  // Your data: everything kept, split by store in the store colours the pane uses, and Saving stopped in the limit red
  if (id === "data") {
    if (DATA_ROWS.some(stillReading)) return GHOST.row([GHOST.bar(5), GHOST.track()]);
    if (DATA_ROWS.some((r) => !(r.noRoute || r.own) && heldOf(r) === null)) return RAIL_UNREAD;
    const held = bySize(), all = allBytes(), stop = traceStopped();
    const bar = all ? `<span class="rsplit" aria-hidden="true">${held.map((p, i) => `<i style="${hueAt(i)};width:max(${((p.x.bytes / all) * 100).toFixed(2)}%, .12rem)"></i>`).join("")}</span>` : "";
    // the total and its split on one line, the bar taking what the figure leaves: the rail fits above the composer
    return `<div class="rmini"><div class="rkept"><p class="rfig"><b>${esc(mbWord(all))}</b>kept</p>${bar}</div>`
      + (stop ? `<p class="rfig rstop">${DRAW.lamp("stalled")}<span class="state limit">Saving stopped ${esc(stop.when)}</span></p>` : "") + `</div>`;
  }
  // Version: the one it runs, and the newer one a check found, each a figure
  if (id === "version") {
    if (ui.ver === undefined) return GHOST.row([GHOST.bar(4), GHOST.bar(5)]);
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
  const draw = drawn || (s.length === 1 && s[0] === "Reading" ? GHOST.row([GHOST.bar(7)]) : `<div class="meta">${s.map((w) => `<span>${esc(w)}</span>`).join("")}</div>`);
  const hit = f.length ? `<div class="hits"><span>${hl(f[0])}</span>${f.length > 1 ? `<span class="more">${fmt(f.length - 1)} more</span>` : ""}</div>` : "";
  return `<article class="card job${dim ? " dim" : ""}" data-open="${x.id}" aria-current="${ui.open === x.id}"${f.length > 1 ? ` title="${esc(f.join(" · "))}"` : ""}>`
    + `<div class="top"><span class="jglyph" aria-hidden="true">${SV[x.id]}</span><span class="jname">${hl(x.name)}</span></div>`
    + `<div class="jstate${hit ? " hit" : ""}"><div class="jdraw">${draw}</div>${hit}</div></article>`;
}

// ---------- the page ----------
const root = document.getElementById("st");
function render() {
  tick(); // times read in the person's present
  if (ui.err) { root.innerHTML = `<div class="list rail plain empty"><div class="nomatch"><span class="state limit">${esc(ui.err)}</span><button class="act quiet" data-act="again">Read again</button></div></div>`; return; }
  ui.legendSaid = new Set();
  // SEARCH NARROWS, IT NEVER EMPTIES (Marcos, Oct 25): every topic keeps its place on the rail and the ones with no
  // match go quiet, so the shape of the page does not change under him and a miss is read as a miss. A search that
  // finds nothing keeps the topic he was on, and says so once, beside the field (searchCount).
  const all = jobs(), searching = words().length > 0;
  const shown = searching ? all.filter((x) => found(x.id).length) : all;
  if (shown.length && !shown.some((x) => x.id === ui.open)) ui.open = shown[0].id;
  if (!all.some((j) => j.id === ui.open) && all.length) ui.open = all[0].id;
  const x = TOPIC[ui.open];
  const cards = all.map((j) => cardHtml(j, searching && !found(j.id).length));
  const list = `<div class="list rail plain">${cards.join("")}</div>`;
  // the pane's header names the topic and nothing the card beside it already says: its state is drawn inside the pane
  const pane = `<div class="pane"><header><span class="jglyph" aria-hidden="true">${SV[x.id]}</span><div class="who"><div class="top"><span class="name">${esc(x.name)}</span></div></div>`
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
  moreBelow();
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

// A pane that runs past the fold says so on its lower edge, and the word goes once the end is on screen. THE PAGE
// SCROLLS, NOT THE PANE (Marcos, Oct 25: the pane had its own scrollbar inside the page's, so a setting could be
// hidden twice over), so the cue is measured against the window.
function moreBelow() {
  const pane = root.querySelector(".pane");
  if (pane) pane.classList.toggle("more", pane.getBoundingClientRect().bottom > window.innerHeight + 8);
}
addEventListener("scroll", moreBelow, { passive: true });
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
    case "addfolder": ui.addPath = ""; ui.focusPath = true; delete ui.bad.projectsFolders; break;
    case "cancelfolder": ui.addPath = null; delete ui.bad.projectsFolders; break;
    case "savefolder": saveFolders(withPath(root.querySelector("[data-pfield]")?.value ?? "")); return;
    case "dropfolder": saveFolders(foldersNow().filter((p) => p !== d.path)); return;
    case "armfolder": ui.armed = `folder:${d.path}`; break;
    case "mmore": ui.mmore[d.id] = !ui.mmore[d.id]; break;
    case "pmore": ui.pmore[d.id] = !ui.pmore[d.id]; break;
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
    // the way back from Saving stopped: the deletes, brought into view and lit once where they stand
    case "tofree": {
      const free = root.querySelector(".dfree");
      if (free) { free.scrollIntoView({ behavior: "smooth", block: "start" }); free.classList.remove("landed"); void free.offsetWidth; free.classList.add("landed"); }
      return;
    }
    default: return;
  }
  render();
});
if (TOPIC[location.hash.slice(1)]) { ui.open = location.hash.slice(1); ui.shown = true; }
// before the first read answers, the rail draws its cards: their names and glyphs are this page's own, and each card's
// state is the ghost of what will land in it (Marcos, Oct 12: a blank page under its title read as broken)
const RAIL_GHOST = { silent: [6, 4], busy: [6, 0], reasoning: [9], plan: [5, 0], mcp: [5, 0], projects: [6, 5], data: [5, 0], version: [4, 5] };
const railGhost = (id) => GHOST.row((RAIL_GHOST[id] ?? [7]).map((w) => (w ? GHOST.bar(w) : GHOST.track())));
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
const ghostRows = (keys) => `<div class="krows flat">${keys.filter((key) => KNOB[key]).map((key) => `<div class="krow" aria-hidden="true"><div class="lbl">${esc(KNOB[key].label)}</div><div class="ctl">${GHOST.bar(9, "chip")}</div></div>`).join("")}</div>`;
const PANE_GHOST = {
  silent: () => pair(ghostSec(heroGhost() + stallGhost(), "This week"), ghostSec(ghostRows(keysOf("silent")), "How long splice waits")),
  busy: () => pair(ghostSec(heroGhost() + peaksGhost(), "Today"), ghostSec(ghostRows(LIMITS.filter((key) => keysOf("busy").includes(key))), "splice's limits"), "lead")
    + ghostSec(`${GHOST.focal(16)}${GHOST.row([GHOST.chip(), GHOST.cell()])}`, "Providers today"),
  reasoning: () => pair(ghostSec(termGhost(), "In your terminal"),
    ghostSec(ghostRows(SHOWS.filter((key) => keysOf("reasoning").includes(key))), "How reasoning shows")),
  plan: () => pair(ghostSec(heroGhost() + planGhost(), "Right now"), ghostSec(ghostRows(keysOf("plan")), "When splice warns")),
  mcp: () => ghostSec(heroGhost() + GHOST.stack()) + ghostSec(`<div class="ghost-rows">${[0, 1, 2].map(() => GHOST.row([GHOST.tile(), GHOST.bar(9), GHOST.bar(15), GHOST.bar(5), GHOST.bar(8)])).join("")}</div>`) + ghostSec(mcardsGhost()),
  projects: () => ghostSec(heroGhost() + GHOST.row([GHOST.bar(7), GHOST.bar(8), GHOST.bar(6), GHOST.bar(7)])) + ghostSec(GHOST.row([GHOST.bar(9), GHOST.tile()])),
  data: () => ghostSec(heroGhost() + GHOST.stack()) + ghostSec(`<div class="dcards text">${[0, 1, 2].map((i) => GHOST.card([9, 6, 12, 10], i, "dghost")).join("")}</div>`),
  version: () => ghostSec(`<div class="vline">${[5, 8].map((w) => `<div class="vghost" aria-hidden="true">${GHOST.bar(5)}${GHOST.fig(w)}${GHOST.bar(9)}</div>`).join("")}</div>`)
    + ghostSec(`<div class="vfiles">${[13, 10, 10].map((w) => `<div class="vfile" aria-hidden="true">${GHOST.bar(5)}${GHOST.bar(w)}</div>`).join("")}</div>`),
};
root.innerHTML = ghostHtml();
refresh();

});
