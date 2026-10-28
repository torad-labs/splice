// Models: every command he runs, what it serves and how it is doing, every model on every plan and key with its window
// and price, and what applies inside a project. Create a command from a template or a custom endpoint, change its model,
// window and system prompt, run the fix splice offers, and choose whether plain `claude` runs through splice.
// BUILT AS DRAWN (Splice-Animator/design/console/models.html), on the daemon's own routes: /api/heads, /api/topology,
// /api/models, /api/projects, /api/add, /api/doctor, /api/logs, /api/claude-head. Where the drawing and the daemon
// disagree on a fact the daemon wins, and the element shows only where the fact exists. Rename is out of 0.4.0, and
// Remove is not on this page yet.
CONSOLE.page("models.html", (scope) => {
  "use strict";
  const { document, window, API, setTimeout, clearTimeout, setInterval, clearInterval, requestAnimationFrame, cancelAnimationFrame, addEventListener, removeEventListener, KNOBS } = scope;

const COMMAND_KNOBS = Object.values(KNOBS.KNOB).filter((k) => k.cmd).map((k) => k.key);
const TIERS = ["opus", "sonnet", "haiku", "fable"];
const tierName = (t) => t[0].toUpperCase() + t.slice(1);
// A model is named the way its catalog names it ("GPT-6 Sol"); a vendor's id with no catalog name is dressed the same
// way, never printed raw ("gpt-6-sol"). Digits that follow a digit join with a dot, those after "GPT" with a dash.
const nameOf = (m) => {
  if (m.label && m.label !== m.id) return m.label;
  const id = String(m.id).split("/").pop();
  if (!/^(gpt|claude|codex|grok|gemini|o\d)/i.test(id)) return m.id; // a local model has no catalog name but its own
  const out = [];
  for (const p of id.split("-")) {
    const prev = out[out.length - 1];
    if (/^\d+$/.test(p) && /^\d+(\.\d+)*$/.test(prev ?? "") && !/^\d{4,}$/.test(p)) out[out.length - 1] = `${prev}.${p}`;
    else if (/^\d/.test(p) && /^GPT$/i.test(prev ?? "")) out[out.length - 1] = `GPT-${p}`;
    else out.push(/^gpt$/i.test(p) ? "GPT" : p[0].toUpperCase() + p.slice(1));
  }
  return out.join(" ");
};
// A command that declares no tier is not left empty: its client plants each tier by position (LaunchService
// positionalTiers): the pinned model's family by name (Sol, Terra, Luna), else catalog order with Haiku preferring a
// mini or fast model, and Fable on Opus's. The pane reads those so an unset tier shows the model that answers.
function defaultTiers(ids, pinned) {
  const all = pinned ? [pinned, ...ids.filter((i) => i !== pinned)] : ids;
  if (!all.length) return {};
  const of = (tier) => all.find((i) => i.split("-").pop().toLowerCase() === tier);
  const frontier = of("sol") ?? all[0], mid = of("terra") ?? all[1] ?? all[0];
  const fast = of("luna") ?? all.find((i) => /mini|fast/i.test(i)) ?? all[1] ?? all[0];
  return { opus: frontier, sonnet: mid, haiku: fast, fable: frontier };
}
const root = document.getElementById("models");
KNOBS.wire(root, () => render());

// ---------- what the daemon says ----------
const data = { heads: [], topo: null, models: [], local: [], projects: [], sessions: [], doctor: null, claude: null, profiles: [], loaded: false, err: null, listsAt: 0, listsTicks: 0, listsShape: "" };
// What he is doing: the open command and its unsaved draft, a new command in progress, the saved words.
const ui = { rm: null, gone: new Set(), restartDue: false, sign: null, keyin: null, view: "commands", open: null, add: null, draft: null, said: null, pick: null, saving: false, restart: null,
  effOpen: false, logs: {}, logPath: {}, logWhy: {}, logq: "", logPaused: false, proj: null, projCmd: null, file: null, files: {}, more: new Set(), q: "", claudeSaid: null, fixing: {}, wide: true,
  // Every model and Projects: the rate cards open, the rows drawn last time (only a row new since then comes in), the
  // one-shot motion of the press just made, the project filter, and where Settings or Sessions sent him
  rates: new Set(), seenRows: null, fx: null, pq: "" };

/** Every read the page draws from. With [draw], the page draws as soon as the commands and their models answer, and
 *  again as each slower read lands: the sessions listing took 4 s and more, doctor 3 s, and Models once sat blank 17 s
 *  waiting on all of them in a row (walk on d0ec523b5). What has not landed reads Reading where it shows ([pending]).
 *  With [lists] false it asks only what moves, /api/heads and /api/sessions, and keeps the other seven as they were read:
 *  topology, models, claude-head, projects, doctor, local runtimes and config change when someone saves or edits a file.
 *  The page's own writes and Read again leave it true. A read of the lists records when it asked them and the commands it
 *  saw, and starts the count of ticks again ([listsAt], [listsShape], [listsTicks]). What failed is not the list: when any
 *  of the seven fails, the time is cleared and the next round reads them all again, as the 5 s tick once did. */
async function readAll({ draw, lists = true } = {}) {
  const asked = Date.now();
  if (lists) data.listsTicks = 0;
  const paths = lists ? ["/api/heads", "/api/topology", "/api/models", "/api/claude-head"] : ["/api/heads"];
  const [heads, topo, models, claude] = await Promise.all(paths.map((p) => API.get(p)));
  if (!heads.ok || (lists && !topo.ok)) {
    if (heads.status !== 0) console.warn(`models: /api/heads answered ${heads.status}`);
    data.err = heads.status === 0 ? "Not answering" : "Not read";
    data.listsAt = 0;
    return;
  }
  data.err = null;
  data.heardAt = Date.now();
  data.heads = heads.body?.heads ?? [];
  if (lists) {
    data.topo = topo.body;
    data.models = models.body?.heads ?? [];
    data.claude = claude.ok ? claude.body : null;
    data.listsShape = shape();
  }
  KNOBS.st.cmds = data.heads.map((h) => h.key);
  KNOBS.st.commandOf = Object.fromEntries(data.heads.map((h) => [h.key, h.label || h.key]));
  KNOBS.st.providerOf = Object.fromEntries(data.heads.map((h) => [h.key, topology().heads?.[h.key]?.provider ?? ""]));
  if (draw && !data.loaded) { data.pending = new Set(["sessions", "projects", "doctor", "local", "knobs"]); data.loaded = true; draw(); }
  data.loaded = true;
  const land = (what) => { data.pending?.delete(what); draw?.(); };
  const sessions = API.get("/api/sessions").then((r) => { data.sessions = r.body?.sessions ?? []; land("sessions"); });
  if (!lists) { await sessions; return; }
  let whole = models.ok && claude.ok;
  await Promise.all([
    sessions,
    // a failed read keeps the list it had and says so: wiping it read as "No projects" for as long as splice failed
    API.get("/api/projects").then((r) => {
      whole &&= r.ok;
      if (r.ok) { data.projects = r.body?.projects ?? []; data.projectsErr = null; } else data.projectsErr = r.status === 0 ? "Not answering" : "Not read";
      land("projects");
    }),
    API.get("/api/doctor").then((r) => { whole &&= r.ok; data.doctor = r.ok ? r.body : null; land("doctor"); }),
    // what each local runtime says about its models, asked of the runtimes themselves (spec 9i)
    API.get("/api/models/local").then((r) => {
      whole &&= r.ok;
      if (!r.ok && r.status !== 0) console.warn(`models: /api/models/local answered ${r.status}`);
      data.local = r.ok ? r.body?.providers ?? [] : [];
      land("local");
    }),
    KNOBS.load().then((err) => { whole &&= !err; data.knobsErr = err; land("knobs"); }),
  ]);
  data.listsAt = whole ? asked : 0;
}
const pending = (what) => !!data.pending?.has(what);
// The 5 s tick reads the lists on the twelfth tick after they were asked, a minute. It counts ticks, because a minute
// timed from when the answers landed, or from a timer that fired a few ms late, fell just short on the twelfth tick and
// moved the read to the thirteenth, 65 s on. A round also reads them when they are a minute old by the clock, as in a tab
// back from a browser that held its timers, after a read that failed, and when its heads show a command start, stop,
// appear or go: a card's word comes from doctor as well as heads, and a command started elsewhere would read Not started
// from doctor's old daemon/head check for up to a minute. The shape is each command's key and whether it runs, and no
// more: /api/heads also carries gate counters and pids that change on every call.
const TICK_MS = 5_000;
const LISTS_MS = 60_000;
const shape = () => data.heads.map((h) => `${h.key}:${Boolean(h.running)}`).join(" ");
const listsDue = () => data.listsTicks >= LISTS_MS / TICK_MS || Date.now() - data.listsAt >= LISTS_MS || shape() !== data.listsShape;

// ---------- the commands: the daemon's heads joined to the topology file and the model roster ----------
const topology = () => data.topo?.topology ?? {};
function window_(row) { return row?.context_window ?? null; } // null: the provider published none (v0.4.0 section 9: never 0)
// a command added beside another on one profile (claude-openrouter-side-project) is that profile's provider, though its
// provider key is the name it was given (marlin p228: its pane read "side-project" where every other names its provider)
function familyByCmd(cmd) {
  const p = (data.profiles ?? []).filter((x) => x.command && (cmd === x.command || cmd.startsWith(`${x.command}-`)))
    .sort((x, y) => y.command.length - x.command.length)[0];
  return p ? famOf(p) : undefined;
}
// a local provider splice has no name for reads as the runtime that answers it, Ollama or LM Studio, never its TOML key
// in lower case (/api/models/local, spec 9i; marlin, Oct 12)
const runtimeName = (provKey) => (data.local ?? []).find((p) => p.provider === provKey)?.runtime ?? undefined;
function commandsOf() {
  const t = topology(), hs = t.heads ?? {}, ps = t.providers ?? {};
  return data.heads.filter((h) => !ui.gone.has(h.key)).map((h) => {
    const key = h.key, cfg = hs[key] ?? {}, provKey = cfg.provider ?? "", pcfg = ps[provKey] ?? {};
    const row = data.models.find((r) => r.head === key);
    const models = (row?.models ?? []).filter((m) => m.resolved).map((m) => ({
      id: m.id, label: nameOf(m), window: window_(m), max: m.max_context_window ?? null, rates: m.rates ?? null, slot: m.slot ?? null,
    }));
    // a declared model that resolved to nothing keeps its row and its tier, with the route's reason, never dropped
    // (web-console.md, GET /api/models): the tier the operator is missing is the one thing this page must show
    const missing = (row?.models ?? []).filter((m) => !m.resolved).map((m) => ({ id: m.id, label: nameOf(m), slot: m.slot ?? null, reason: m.reason ?? "" }));
    const slots = {};
    for (const m of [...models, ...missing]) if (m.slot && TIERS.includes(m.slot)) slots[m.slot] = m.id;
    for (const [slot, id] of Object.entries(cfg.model_slots ?? {})) slots[String(slot).toLowerCase()] ??= id;
    const defaults = defaultTiers(models.map((m) => m.id), row?.pinned_model || null);
    const first = TIERS.map((s) => slots[s]).find((id) => models.some((m) => m.id === id)) ?? defaults.opus ?? row?.pinned_model ?? models[0]?.id;
    const shown = models.find((m) => m.id === first);
    const family = PROVIDER_FAMILY[provKey] ?? familyByCmd(h.label || cfg.claude?.command || key) ?? provKey;
    const lives = data.sessions.filter((s) => s.head === key && (s.status === "working" || s.status === "busy")).length;
    return {
      key, cmd: h.label || cfg.claude?.command || key, provKey, family, mainId: first ?? null, prov: PROVIDER_NAME[family] ?? runtimeName(provKey) ?? provKey,
      url: pcfg.base_url ?? "", extraWindows: pcfg.extra_windows ?? [], windowRules: pcfg.window_rules ?? [], models, missing, slots, defaults, pinned: row?.pinned_model ?? null, model: shown?.label ?? "None", window: shown?.window ?? 0, running: lives,
      prompt: { mode: cfg.system_prompt_mode ?? "append", text: cfg.system_prompt ?? "", file: cfg.system_prompt_file ?? "" },
      up: h.running && h.healthy, live: h.running, auth: h.authKind ?? "",
      // the daemon's own watch of a local runtime's port, the truth Accounts reads as Not answering (HeadProbes)
      down: !!h.runtimeNotAnswering,
      // the watch holds no reading yet (its first probe) or any more (it died and restarts): checking, never Ready
      unread: !!h.runtimeUnread,
    };
  });
}
// a command's state, as the daemon's doctor reports it: unlinked, signed out or not started carry their fix
// doctor's row for a command's own link names the command in quotes, so one command's name inside another's never matches
// (marlin p228: claude-openrouter took claude-openrouter-side-project's row, two lamps named Command on one pane)
const wrapperOf = (x, c) => x.id === "installation/wrapper" && Boolean(x.detail?.startsWith(`'${c.cmd}'`));
function stateOf(c) {
  const checks = data.doctor?.checks ?? [];
  const mine = checks.filter((x) => x.id === `auth/${c.key}` || x.id === `daemon/head ${c.key}` || wrapperOf(x, c));
  const bad = mine.find((x) => x.status === "fail" || x.status === "warn");
  if (!c.live) return { state: "stopped", word: "Not started", fix: bad?.fix_id ? { id: bad.fix_id, label: "Start" } : { head: c.key, label: "Start" } };
  // a local head whose runtime does not answer is not Ready, whatever its checks say: nothing listens on its port
  // (the walk at 3828, Oct 13: four local heads read Ready with 8099-8102 closed)
  if (c.down) return { state: "runtime", word: "Not answering", fix: null };
  if (c.unread) return { state: "checking", word: "Checking runtime", fix: null };
  if (!bad && pending("doctor")) return { state: "checking", word: "Reading" };
  if (!bad) return null;
  const auth = bad.id.startsWith("auth/"), keyed = /key/i.test(c.auth), refused = /refus|reject|invalid|401/i.test(bad.detail ?? "");
  // one word for one state wherever it shows: the card, the header and the Sign-in section all read authWord
  const word = auth ? authWord(c, bad) : bad.id === "installation/wrapper" ? "Command not found" : "Not started";
  const label = auth ? (keyed ? (refused ? "Replace key" : "Add key") : "Sign in") : bad.id === "installation/wrapper" ? "Restore" : "Start";
  return { state: bad.id, word, fix: auth ? { signin: true, label } : bad.fix_id ? { id: bad.fix_id, label } : null };
}
const cmdByKey = (k) => commandsOf().find((c) => c.key === k);
// a model a request names, by the name the command's roster gives it, or dressed the way the catalog would name it
const modelWord = (c, id) => (c.models.find((m) => m.id === id) ?? c.missing.find((m) => m.id === id))?.label ?? nameOf({ id });

// ---------- what a person reads ----------
function big(n) { // a window: 200K, 256K, 1M, 1.05M; none is "", for a draft field left empty
  if (n == null) return "";
  const k = n / 1024;
  if (Number.isInteger(k) && (k & (k - 1)) === 0) return k >= 1024 ? `${k / 1024}M` : `${k}K`;
  return n >= 1e6 ? `${+(n / 1e6).toFixed(2)}M` : `${Math.round(n / 1000)}K`;
}
function parseWindow(v) { // what he types: 200K, 1M, 131072
  const r = /^\s*(\d+(?:\.\d+)?)\s*([km]?)\s*$/i.exec(v);
  if (!r) return null;
  const n = parseFloat(r[1]) * ({ k: 1000, m: 1e6 }[r[2].toLowerCase()] || 1);
  return n > 0 ? Math.round(n) : null;
}
const usd = (x) => `$${Number(x).toFixed(2)}`;
const GL = {
  file: G('<path d="M7 3h7l4 4v14H7z"/><path d="M14 3v4h4"/>', 'aria-hidden="true"'),
  relink: G('<path d="M10 14a4 4 0 0 0 5.66 0l3-3a4 4 0 0 0-5.66-5.66l-1 1"/><path d="M14 10a4 4 0 0 0-5.66 0l-3 3a4 4 0 0 0 5.66 5.66l1-1"/><path d="M4 4l2 2M18 18l2 2"/>', 'aria-hidden="true"'),
  notstarted: G('<circle cx="12" cy="12" r="8"/><path d="M12 7.5v5"/><path d="M12 16h0"/>', 'aria-hidden="true"'),
  close: ICON.close,
  // a wrench: a model that calls tools
  tools: G('<path d="M14.6 3.6a4.6 4.6 0 0 0-5.3 6.2L3.6 15.5a1.9 1.9 0 0 0 2.7 2.7L12 12.5a4.6 4.6 0 0 0 6.2-5.3l-2.8 2.8-2.6-.6-.6-2.6z"/>', 'aria-hidden="true"'),
  // how a template signs in: a key, or a sign-in page in the browser
  key: DRAW.glyph.key,
  browser: G('<rect x="3.5" y="5" width="17" height="14" rx="2"/><path d="M3.5 9h17M7 7h0M9.5 7h0"/>', 'aria-hidden="true"'),
  // a change splice reads only when it starts: the settings kit's own restart mark (knobs.js MARK.restart)
  restart: G('<path d="M20 12a8 8 0 1 1-2.6-5.9"/><path d="M20 4v5h-5"/>', 'aria-hidden="true"'),
  // the way back to the provider's own value
  back: G('<path d="M9 7L4 12l5 5"/><path d="M4 12h10a6 6 0 0 1 0 12h-2" transform="translate(0 -6)"/>', 'aria-hidden="true"'),
  // what one word becomes on the wire: the word Claude Code sends maps to the word the provider is sent
  maps: G('<path d="M5 12h12"/><path d="M13 8l4 4-4 4"/>', 'aria-hidden="true"'),
};
// a file a prompt is read from, by its own name, as the log's file is; where it sits is the name's title
const pfile = (name) => `<span class="pfile" title="${esc(homeRel(name))}">${GL.file}<span>${esc(String(name).split("/").pop())}</span></span>`;
const WAIT = '<svg class="wait" viewBox="0 0 40 40"><circle cx="20" cy="20" r="15"/></svg>';

// ---------- the drawings this page draws with (kit.js's DRAW and GHOST) ----------
// A share of a limit is a meter, a magnitude a bar on its column's shared scale, a time a mark on one log axis, a
// count a tile that exists only above zero, and a figure splice has no reading for a dash. Nothing here invents a
// number: every figure below comes from /api/heads, /api/models, /api/sessions, /api/topology or /api/doctor.
const DASH = DRAW.dash;
/** One count as a tile: its glyph, its figure and its name on one baseline, in the limit red where it is a failure,
 *  and a door to exactly those requests where one exists. A count of zero draws NOTHING, so a strip of tiles says
 *  what happened instead of a row of noughts. [text] writes the figure its own way (a duration, a window). */
function tile({ icon, n, text = null, label, href = null, bad = false, focal = false }) {
  if (!(Number(n) > 0)) return "";
  const inner = `<span class="pd-stat${focal ? " focal" : ""}${bad ? " bad" : ""}">${icon}`
    + `<span><b>${text == null ? DRAW.n(n) : esc(text)}</b><small>${esc(label)}</small></span></span>`;
  return href ? `<a class="tdoor" href="${href}">${inner}${DRAW.go}</a>` : inner;
}
/** Requests on this command, and the ones that failed on it, on the Requests page. */
const cmdDoor = (c) => `requests.html?cmd=${encodeURIComponent(c.cmd)}`;
const failDoor = (c) => `${cmdDoor(c)}&outcome=fail`;
/** The question a press that cuts someone asks once, in place (kit.js's armedAsk, with the names capped): what it
 *  does to them, four sessions as chips that open them, the count of the rest, then the act and the way out. */
function askHtml(said, cut, act, keep, { bad = false } = {}) {
  const shown = cut.slice(0, 4);
  const chips = shown.map((x) => DRAW.chip({ text: x.name || x.id.slice(0, 8), live: true, href: `sessions.html#${encodeURIComponent(x.id)}` })).join("");
  const rest = cut.length - shown.length;
  return `<span class="armed cutask">${said ? `<span class="said${bad ? " limit" : ""}">${esc(said)}</span>` : ""}${chips}`
    + `${rest > 0 ? `<span class="pd-count"><b>${DRAW.n(rest)}</b> more ${rest === 1 ? "session" : "sessions"}</span>` : ""}${act}${keep}</span>`;
}
/** A context window as a bar on the scale every window in the pane shares, with a tick where the model's own ceiling
 *  sits: a window set past what its model serves reads long before its digits do. */
function winBar(win, max, scale, over = false) {
  const w = scale > 0 && win ? Math.min(100, (win / scale) * 100) : 0;
  const tick = scale > 0 && max ? Math.min(100, (max / scale) * 100) : null;
  return `<span class="wbar${over ? " over" : ""}" aria-hidden="true"><i style="width:${w.toFixed(2)}%"></i>`
    + `${tick == null ? "" : `<u style="left:${tick.toFixed(2)}%"></u>`}</span>`;
}

// ---------- a command's lamp and word ----------
// What is not read yet draws as its own shape, never as the word "Reading": the state's place is a striped bar its
// own size ([ghost]), so nothing moves when the word lands (Marcos, Oct 12; the old page said "Reading" on 7 of 11 cards).
function look(c) {
  if (ui.sign?.key === c.key) return { cls: "working", lamp: WAIT, word: "Signing in" };
  if (ui.fixing[c.key]) return { cls: "working", lamp: WAIT, word: ui.fixing[c.key] };
  const s = stateOf(c);
  if (ui.keyin?.key === c.key) return { cls: "working", lamp: ICON.signout, word: "Add key" };
  if (s?.state === "checking") return { cls: "working", lamp: WAIT, word: s.word, ghost: s.word === "Reading" };
  if (s?.state === "stopped" || s?.state === "runtime") return { cls: "stalled", lamp: GL.notstarted, word: s.word };
  if (s?.state === "installation/wrapper") return { cls: "stalled", lamp: GL.relink, word: s.word };
  if (s) return { cls: "stalled", lamp: ICON.signout, word: s.word };
  // a command that is fine says no word: its lamp says it is up, the trace that it is working, and how busy it is is
  // the meter on its card and the figure Right now leads with, so "Ready" and "3 running" were said twice
  if (c.running) return { cls: "working", lamp: ICON.trace, word: "" };
  if (pending("sessions")) return { cls: "working", lamp: WAIT, word: "", ghost: true };
  return { cls: "ready", lamp: "<i></i>", word: "" };
}
// the provider is said beside a command only where the command's own name does not already say it: claude-grok is
// Grok's, claudex is not plainly ChatGPT's
const provSaid = (c) => c.cmd.replace(/^claude-?/i, "").toLowerCase().includes(String(c.prov).toLowerCase());
// where a local command expects its runtime, as host and port: what he has to start when it does not answer
const hostPort = (url) => { try { const u = new URL(url); return u.host; } catch { return url; } };
// the gate's load right now: requests in flight against the most it lets in at once (null when it sets no limit)
function loadOf(c) {
  const g = data.heads.find((x) => x.key === c.key)?.gate;
  if (!g) return null;
  const max = g.max === "unlimited" ? null : Number(g.max) || null;
  return { flight: Number(g.inflight) || 0, max };
}
const order = (cs) => [...cs.filter((c) => stateOf(c)), ...cs.filter((c) => !stateOf(c))];
function signHtml(c) {
  const kf = ui.keyin;
  if (kf?.key === c.key) {
    return `<div class="outbox"><input class="field code-in" id="k-in" type="password" placeholder="Paste key" value="${esc(kf.val ?? "")}" autocomplete="off" aria-label="Paste key">`
      + DRAW.act({ word: "Add", act: "savekey", cls: "primary" }) + DRAW.act({ word: "Cancel", act: "cancel-key", cls: "quiet" })
      + `${kf.said ? DRAW.result(kf.said, "bad") : ""}</div>`;
  }
  const sg = ui.sign;
  if (sg?.key !== c.key) return "";
  return `<div class="outbox"><span class="waitbox" role="status">${ICON.wait}<span class="sr">Signing in</span></span>`
    + `${sg.code ? `<button class="usercode" data-act="copy-code">${esc(sg.code)}</button><span class="code-hint">Enter this code on the sign-in page</span>` : ""}`
    + `${sg.url ? `<button class="act" data-act="open-signin">Open sign-in</button>` : ""}<button class="act quiet" data-act="cancel-signin">Cancel</button></div>`;
}
// A card is the command as an object: its lamp in the socket, its name as the one chip (with the live dot while
// sessions run on it), its provider where the name does not say it, then the model it serves with its window at the
// card's right edge. A command that needs him says its state ONCE, in the card's top-right slot in the text's own ink,
// with the fix under it; one that is working draws its requests in flight against its limit, in its provider's colour.
// The fix and the sign-in box live here only while the command's pane is not open beside it, so no act is drawn twice.
function cardHtml(c) {
  const k = look(c), s = stateOf(c), mine = ui.open === c.key && !ui.add;
  const said = ui.fixSaid?.key === c.key ? DRAW.result(ui.fixSaid.word, "bad") : "";
  const fix = s?.fix && !mine && !ui.fixing[c.key] && ui.sign?.key !== c.key && ui.keyin?.key !== c.key
    ? `<div class="fix"><button class="act primary" data-act="fix" data-h="${esc(c.key)}">${esc(s.fix.label)}</button>${said}</div>` : "";
  // the open command's header beside the rail says its state, so its card keeps the lamp and drops the word
  const slot = k.ghost ? GHOST.state() : k.word && !mine ? `<span class="cstate">${esc(k.word)}</span>` : "";
  const ld = !s && c.live ? loadOf(c) : null;
  const load = ld?.flight
    ? `<div class="cload" data-meter="card:${esc(c.key)}" title="${esc(`${DRAW.n(ld.flight)} requests in flight${ld.max ? ` of ${DRAW.n(ld.max)} allowed at once` : ""}`)}">`
      + `${ld.max ? DRAW.meter(Math.min(100, (ld.flight / ld.max) * 100), ld.flight >= ld.max ? "full" : "") : ""}`
      + `<span class="cfig"><b data-fig="card:${esc(c.key)}">${DRAW.n(ld.flight)}</b>${ld.max ? ` of ${DRAW.n(ld.max)}` : " in flight"}</span></div>` : "";
  // a runtime that does not answer names where splice expects it, which is what he has to start
  const where = c.down && c.url ? `<span class="caddr">${esc(hostPort(c.url))}</span>` : "";
  return `<article class="card h ${k.cls}${ui.shook === c.key ? " shook" : ""}${ui.fx?.landed === c.key ? " landed" : ""}" style="--c:${colorOf(c.family)}" data-open="${esc(c.key)}" aria-current="${mine}">`
    + `<span class="lamp ${k.cls}" aria-hidden="true">${k.lamp}</span>`
    + `<div class="top">${DRAW.chip({ text: c.cmd, live: c.running > 0 && !s })}${provSaid(c) ? "" : `<span class="prov">${esc(c.prov)}</span>`}${slot}</div>`
    + `<div class="meta"><span class="cmodel">${esc(c.model)}</span>${where}`
    + `${c.window ? `<b class="cwin">${big(c.window)}</b>` : ""}</div>${load}${fix}${mine ? "" : signHtml(c)}</article>`;
}

// ---------- the open command: its settings, and its log ----------
const modeSwitch = (id, mode) => `<div class="switch" role="group">${["append", "replace", "strip"].map((x) =>
  `<button data-act="mode" data-id="${esc(id)}" data-v="${x}" aria-pressed="${mode === x}">${x[0].toUpperCase() + x.slice(1)}</button>`).join("")}</div>`;
// the project layers the topology declares for one command: [projects."ROOT".heads.KEY], by project root
const perOf = (c) => Object.fromEntries(Object.entries(topology().projects ?? {}).map(([root, p]) => [root, p?.heads?.[c.key]]).filter(([, l]) => l)
  .map(([root, l]) => [root, { mode: l.system_prompt_mode ?? "append", text: l.system_prompt ?? "", file: l.system_prompt_file ?? "" }]));
const homeRel = (p) => (p ?? "").replace(/^(\/home|\/Users)\/[^/]+/, "~");
const projectOf = (root) => data.projects.find((p) => p.root === root || p.id === root);
const nameOfRoot = (root) => projectOf(root)?.name ?? root.split("/").filter(Boolean).pop();
function draftOf(c) {
  return {
    tiers: Object.fromEntries(TIERS.map((t) => {
      const row = c.models.find((m) => m.id === c.slots[t]), lost = c.missing.find((m) => m.id === c.slots[t]);
      return [t, { model: row?.id ?? lost?.id ?? null, win: row ? big(row.window) : "" }];
    })),
    // with no tier declared, one window serves every default tier: the head's own, shown as the default model's
    headWin: TIERS.some((t) => c.slots[t]) ? "" : big(c.models.find((m) => m.id === c.defaults.opus)?.window ?? null),
    mode: c.prompt.mode, text: c.prompt.text, file: c.prompt.file, per: perOf(c), url: c.url,
    ...fieldsOf(c),
  };
}
// the other commands on this command's provider: an edit to the provider's own fields (its address, a model's window)
// reaches them too, so the pane names them beside the field (spec 9d)
const sharersOf = (c) => commandsOf().filter((x) => x.provKey === c.provKey && x.key !== c.key);
const sharedHtml = (c) => {
  const others = sharersOf(c);
  return others.length ? `<span class="shared">Also ${others.map((x) => `<span class="chip" style="--c:${colorOf(x.family)}">${esc(x.cmd)}</span>`).join("")}</span>` : "";
};
// an address splice can send to: http or https, with a host
const urlOk = (v) => { try { const u = new URL(String(v).trim()); return (u.protocol === "http:" || u.protocol === "https:") && Boolean(u.host); } catch { return false; } };
function draftState(c) {
  const d = ui.draft;
  // a window is a number, and no larger than the most the provider publishes for that model, where it publishes one
  const tiers = Object.fromEntries(TIERS.map((t) => {
    const win = parseWindow(d.tiers[t].win), max = c.models.find((m) => m.id === d.tiers[t].model)?.max ?? null;
    // a tier on a model that resolved to nothing has no window to give; it is kept as declared
    const lost = c.missing.some((m) => m.id === d.tiers[t].model);
    return [t, { ok: !d.tiers[t].model || lost || (Boolean(win) && (max === null || win <= max)), over: Boolean(win) && max !== null && win > max }];
  }));
  // no tier declared: the one head window, capped at the least the provider publishes for the models those tiers answer with
  const unset = !TIERS.some((t) => d.tiers[t].model);
  const hw = parseWindow(d.headWin), caps = unset ? TIERS.map((t) => c.models.find((m) => m.id === c.defaults[t])?.max).filter(Boolean) : [];
  const hmax = caps.length ? Math.min(...caps) : null;
  const head = { ok: !unset || (Boolean(hw) && (hmax === null || hw <= hmax)), over: unset && Boolean(hw) && hmax !== null && hw > hmax, max: hmax };
  const same = JSON.stringify(d) === JSON.stringify(draftOf(c));
  const url = { ok: d.url === c.url || urlOk(d.url) };
  const own = ownState(c, d);
  return { tiers, head, url, own, ok: TIERS.every((t) => tiers[t].ok) && head.ok && url.ok && own.ok, same };
}
const logsOf = (key) => ui.logs[key];
// Remove: two presses, and what stays is said before the second. A command a session is on says who, and each name opens it.
// ---------- one command's Stop and Restart (gap 398), POST /api/heads/{head}/stop and /restart ----------
// The first press reads the sessions mid-request on this command (kit.js's midRequest). Restart with none restarts at
// once, as Restart splice does. Stop always asks, because the command then serves nothing until it is restarted.
const HEAD_ACT = { stop: ["Stop", "Stopped", "Not stopped"], restart: ["Restart", "Restarted", "Not restarted"] };
// An act works where it stands: it spins in its own place while it runs, and its result lands beside it in words.
function headActsHtml(c) {
  const h = ui.headAct, busy = h?.key === c.key && h.busy ? h.act : null;
  const said = ui.headSaid?.key === c.key ? DRAW.result(ui.headSaid.word, ui.headSaid.ok ? "good" : "bad") : "";
  return said
    + DRAW.act({ word: busy === "restart" ? "Restarting" : "Restart", act: "hact", cls: "quiet small", busy: busy === "restart", disabled: Boolean(busy), data: ' data-v="restart"' })
    + DRAW.act({ word: busy === "stop" ? "Stopping" : "Stop", act: "hact", cls: "quiet small", busy: busy === "stop", disabled: Boolean(busy), data: ' data-v="stop"' });
}
function headAskHtml(c) {
  const h = ui.headAct;
  if (h?.key !== c.key || !h.cut) return "";
  const said = h.cut.length ? "Mid-request" : h.act === "stop" ? "Stopped until restarted" : "";
  return askHtml(said, h.cut, DRAW.act({ word: `${HEAD_ACT[h.act][0]} ${c.cmd}`, act: "hact-now", cls: "danger small" }),
    DRAW.act({ word: "Keep running", act: "hact-keep", cls: "quiet small" }), { bad: h.cut.length > 0 });
}
async function pressHeadAct(key, act) {
  const cut = await midRequest([key]);
  if (cut.length || act === "stop") { ui.headAct = { key, act, cut }; ui.fx = { ask: true }; render(); return; }
  runHeadAct(key, act);
}
async function runHeadAct(key, act) {
  ui.headAct = { key, act, busy: true }; ui.headSaid = null; render();
  const res = await API.post(`/api/heads/${encodeURIComponent(key)}/${act}`);
  ui.headAct = null;
  ui.headSaid = { key, ok: res.ok, word: HEAD_ACT[act][res.ok ? 1 : 2] };
  await readAll(); render();
}
/** The wire dialect the command speaks, by the name the provider's own docs give it; splice.toml's own spelling stays
 *  on it as its title, for the one who has to write it there (GET /api/heads' dialect). */
const DIALECT_NAME = { "openai-responses": "OpenAI Responses", "openai-chat": "OpenAI Chat", "anthropic-messages": "Anthropic Messages",
  "anthropic-passthrough": "Anthropic pass-through", anthropic: "Anthropic" };
const dialectHtml = (c) => {
  const d = cmdDialect(c);
  return d ? `<span class="dialect" title="${esc(d)}">${esc(DIALECT_NAME[d] ?? d)}</span>` : "";
};
// Remove: the act stays neutral until it is armed, and the question it asks opens under the acts, never in their row.
function removeHtml(c) {
  const rm = ui.rm?.key === c.key ? ui.rm : null;
  if (rm?.busy) return DRAW.act({ word: "Removing", act: "arm", cls: "quiet small", busy: true, disabled: true });
  if (rm?.armed || rm?.refused) return DRAW.act({ word: "Remove", act: "disarm", cls: "quiet small", data: ' aria-expanded="true"' });
  return DRAW.act({ word: "Remove", act: "arm", cls: "quiet small", data: ' aria-expanded="false"' });
}
function removeAskHtml(c) {
  const rm = ui.rm?.key === c.key ? ui.rm : null;
  if (!rm || rm.busy) return "";
  if (rm.refused) {
    return `<div class="hask unfold">${askHtml("In use", rm.refused, "", DRAW.act({ word: "Close", act: "disarm", cls: "quiet small" }), { bad: true })}</div>`;
  }
  if (rm.armed) {
    // what removing it leaves behind, each count a door to exactly those rows; the counts are still being read at first
    const i = rm.info;
    const stays = i
      ? [i.requests ? `<a class="pd-count" href="${cmdDoor(c)}"><b>${DRAW.n(i.requests)}</b> requests${DRAW.go}</a>` : "",
        i.sessions ? `<a class="pd-count" href="sessions.html"><b>${DRAW.n(i.sessions)}</b> sessions${DRAW.go}</a>` : ""].join("")
      : GHOST.bar(8);
    return `<div class="hask${ui.fx?.ask ? " unfold" : ""}"><span class="said">History stays</span>${stays}`
      + DRAW.act({ word: `Remove ${c.cmd}`, act: "remove", cls: "danger small" })
      + DRAW.act({ word: `Keep ${c.cmd}`, act: "disarm", cls: "quiet small" }) + "</div>";
  }
  if (rm.failed) {
    const door = rm.failed.act === "reread" ? DRAW.act({ word: "Read again", act: "reread", cls: "quiet small" })
      : rm.failed.act === "raw" ? DRAW.act({ word: "Raw log", act: "rawopen", cls: "quiet small" }) : "";
    return `<div class="hask">${DRAW.result(rm.failed.word, "bad")}${door}`
      + `${rm.failed.act === "reread" ? "" : DRAW.act({ word: "Remove", act: "arm", cls: "quiet small" })}</div>`;
  }
  return "";
}
async function armRemove(key) {
  ui.rm = { key, armed: true, info: null };
  ui.fx = { ask: true };
  render();
  const res = await API.get(`/api/heads/${encodeURIComponent(key)}/removal`);
  if (ui.rm?.key === key && res.ok) { ui.rm.info = res.body; render(); }
}
async function removeCommand(key) {
  ui.rm = { key, busy: true };
  render();
  const res = await API.del(`/api/heads/${encodeURIComponent(key)}`);
  if (res.status === 409) { ui.rm = { key, refused: res.body?.live_sessions ?? [] }; render(); return; }
  if (res.status === 404) { ui.rm = null; ui.gone.add(key); Object.assign(ui, { open: null, draft: null, said: null }); render(); return; }
  if (!res.ok) {
    // why it was refused, in the words fin gave each cause; the daemon logged the findings and answers which cause it was
    console.warn(`models: removing ${key} answered ${res.status}: ${(res.body?.findings ?? []).join("; ")}`);
    const failed = res.body?.reason === "changed" ? { word: "Changed on disk", act: "reread" }
      : res.body?.reason === "config" ? { word: "Config error", act: "raw" } : { word: "Not removed", act: "" };
    ui.rm = { key, failed };
    render();
    return;
  }
  ui.rm = null;
  ui.gone.add(key);
  ui.restartDue = true;
  data.listsAt = 0; // the next tick reads the lists again, now without it
  Object.assign(ui, { open: null, draft: null, said: null });
  render();
}
// beside a window field: the most the provider reports for the model, or that it reports none; a window over it turns the note
// a local command's runtime, and what it says about one model: the window it serves now (its most, until it loads the
// model) and whether it calls tools (spec 9i). Null for a command whose runtime lists nothing to read.
const localOf = (c) => data.local.find((p) => p.provider === c.provKey);
// whether the runtime answers is the daemon's watch, the one the card's lamp reads, so the two never disagree;
// /api/models/local only names the runtime and lists its models. It rides the Models kicker as a lamp and a name.
function runtimeAside(c) {
  const r = localOf(c);
  if (!r && !c.down && !c.unread) return "";
  const kind = c.down ? "stalled" : c.unread ? "working" : r && !r.models ? "stalled" : "ready";
  // whether the runtime answers is said once, by the card and the header; here the lamp repeats it as a shape, and
  // the one fact only this list has is said in words: the runtime answers but its model list does not
  const word = !c.down && !c.unread && r && !r.models ? "List not answering" : "";
  const lamp = kind === "ready" ? "<i></i>" : kind === "working" ? WAIT : GL.notstarted;
  const name = [r?.runtime, r?.version].filter(Boolean).join(" ");
  return `<span class="raside"><span class="lamp inline ${kind}" aria-hidden="true">${lamp}</span>`
    + `${name ? `<span class="rname">${esc(name)}</span>` : ""}<span class="rname addr">${esc(hostPort(r?.url || c.url))}</span>`
    + `${word ? `<span class="said limit">${esc(word)}</span>` : ""}</span>`;
}
/** The most one model takes, and the marks worth seeing on it: what the runtime serves for it now, what the provider
 *  publishes as its ceiling, whether it calls tools, and whether the runtime lists it at all (spec 9i). The figure
 *  goes in the row's Most column and the marks under the row; a model splice has no ceiling for is a dash. */
function modelFacts(c, id) {
  const r = localOf(c), m = c.models.find((x) => x.id === id) ?? null, marks = [];
  const listed = r?.models && id
    ? r.models.find((x) => x.id === id) ?? (id.includes(":") ? null : r.models.find((x) => x.id === `${id}:latest`))
    : null;
  let most = null, now = false, tools = null;
  if (r?.models && id && !listed) marks.push(`<span class="said limit">Not listed by the runtime</span>`);
  if (listed) {
    if (listed.served_window) { most = listed.served_window; now = true; } else if (listed.ceiling) most = listed.ceiling;
    // what the runtime says of tool calls is one of three facts: it calls them, it does not, or the runtime says
    // nothing. Only its own "no" is the warning, in the limit colour, since Claude Code on a model that calls no tools
    // reads and edits nothing; its "yes" is the tool glyph on the row; silence draws nothing.
    if (listed.tools === true) tools = true;
    if (listed.tools === false) { tools = false; marks.push(`<span class="said limit">No tool calls</span>`); }
  }
  if (most == null && m?.max != null) most = m.max;
  return { most, now, tools, marks: marks.join("") };
}
// the tool glyph a model the runtime says calls tools carries on its tier, named once on the section's kicker line
const toolsMark = () => `<span class="tools" role="img" aria-label="Calls tools" title="Calls tools">${GL.tools}</span>`;
// ---------- a command just added (spec 1i): how to start it, what doctor says of it, and one test request on offer ----------
// doctor's checks that are this command's own, in his words: its sign-in or key, its port, its command, its runtime
function doctorOf(c) {
  // the runtime row is doctor's configuration/local:<provider>, and only a local command's: an address on loopback is not one
  const runtime = (x) => c.family === "local" && (x.id === `configuration/local:${c.provKey}` || x.id.startsWith(`configuration/local:${c.provKey}/`));
  const name = (x) => x.id.startsWith("auth/") ? (/key/i.test(c.auth) ? "Key" : "Sign-in") : x.id.startsWith("daemon/head") ? "Listening"
    : wrapperOf(x, c) ? "Command linked" : runtime(x) ? "Runtime" : x.id.split("/").slice(1).join("/");
  return (data.doctor?.checks ?? []).filter((x) => x.id === `auth/${c.key}` || x.id === `daemon/head ${c.key}`
    || wrapperOf(x, c) || runtime(x))
    .map((x) => ({ name: name(x), ok: x.status === "ok" || x.status === "info", soft: x.status === "warn" }));
}
const LIVE_PROMPT = "Reply with the single word pong.";
function addedHtml(c) {
  if (ui.added?.key !== c.key) return "";
  const checks = doctorOf(c).map((x) => `<li class="${x.ok ? "pass" : "fail"}"><span class="lamp ${x.ok ? "ready" : x.soft ? "unchecked" : "stalled"}">${x.ok || x.soft ? "<i></i>" : GL.notstarted}</span><span class="cname">${esc(x.name)}</span></li>`).join("");
  const l = ui.added.live;
  const said = l === "sending" ? `<span class="said">${ICON.wait}Sending</span>` : l?.ok ? `<span class="said ok">Answered · ${(l.ms / 1000).toFixed(1)} s</span>`
    : l ? `<span class="said limit"${l.code ? ` title="HTTP ${l.code}"` : ""}>${esc(l.word)}</span>` : "";
  return `<span class="lbl">In a terminal</span><div class="ctl"><code class="launch">${esc(c.cmd)}</code><button class="act quiet small" data-act="copy-launch" data-h="${esc(c.key)}">Copy</button></div>`
    + (checks ? `<span class="lbl">Doctor</span><div class="ctl"><ol class="checks inline">${checks}</ol></div>` : "")
    + `<span class="lbl">Test</span><div class="ctl"><button class="act quiet" data-act="try-live" data-h="${esc(c.key)}"${l === "sending" ? " disabled" : ""}>Send one request</button>${said}</div>`;
}
// one short request through the command itself (POST /api/playground), only when pressed: it spends a little, so it is
// offered and never sent on its own (v0.4.0 section 1, Resolved: the one live check, offered, skipped by default)
async function tryLive(key) {
  const c = cmdByKey(key);
  if (!c || ui.added?.key !== key) return;
  ui.added.live = "sending"; render();
  const began = performance.now();
  const res = await API.post("/api/playground", { head: key, prompt: LIVE_PROMPT, model: c.pinned || c.models[0]?.id });
  const ms = Math.round(performance.now() - began), r = res.body?.response;
  const raw = typeof r?.body?.raw === "string" ? r.body.raw : JSON.stringify(r?.body ?? "");
  const streamErr = /event:\s*error|"type"\s*:\s*"error"/.test(raw);
  if (ui.added?.key !== key) return;
  if (res.ok && r && r.status < 400 && !streamErr) ui.added.live = { ok: true, ms };
  else {
    if (res.body?.error) console.warn(`add test: ${res.body.error}`);
    // the provider's status in words, its number on the word's title
    ui.added.live = { ok: false, word: res.status === 0 ? "Not answering" : r?.status >= 400 ? httpWord(r.status) : "Failed", code: r?.status >= 400 ? r.status : null };
  }
  render();
}
/** THE OPEN COMMAND IS THE OBJECT OPENED UP. Its header says what it is and what can be done to it; its body is its
 *  sections as plates on the kit's balanced board (DRAW.masonry), each plate one section under its two-word kicker, and
 *  each section that holds a change ends in its own Save. The page is the one scroll: the pane is in its flow, with no
 *  height and no scroll of its own (console.css, ONE SCROLL PER PAGE).
 *  HOW MANY COLUMNS is the pane's own measured width over PANE_COL_REM a column (kit colsFit, the rule Accounts' board
 *  uses), never the viewport: three where the pane is about 180rem wide, two at about 120rem, one below. The sections
 *  keep the order they are read in and each goes where the board is shortest, measured on what is drawn, so no column
 *  stands as a tall plate beside empty ones and a command with little to run does not leave one column long beside an
 *  empty one. Only Every setting, when opened, goes on under them in the same columns. */
const PANE_COL_REM = 58;
const PANE_ORDER = ["added", "now", "log", "models", "client", "prices", "where", "signin", "running", "settings", "prompt", "projects"];
// a section's own name rides its element, so an edit knows which section's Save it fills and #log lands on the log
const tagSec = (id, html) => (html ? html.replace(/^<section /, `<section data-sec="${id}" `) : "");
const SECTION_DRAW = {
  added: (c) => addedSection(c), now: (c) => nowSection(c), log: (c) => logSection(c),
  models: (c, d, st) => modelsSection(c, d, st), client: (c) => clientSection(c), prices: (c, d, st) => pricesSection(c, d, st),
  where: (c, d, st) => whereSection(c, d, st), signin: (c, d, st) => signInSection(c, d, st), running: (c, d, st) => runningSection(c, d, st),
  settings: (c) => settingsSection(c), prompt: (c, d, st) => promptSection(c, d, st), projects: (c, d, st) => projectsSection(c, d, st),
};
// The draft the pane edits, and the shape it was built in: his own typing is the difference between the two, which is
// what holds the page's reads back (mayAsk) and what the Save bar lists. A draft nobody has touched is rebuilt from
// each round that lands, so an open pane never freezes the page under it.
function takeDraft(c) {
  ui.draft = draftOf(c);
  ui.draftBase = JSON.stringify(ui.draft);
  return ui.draft;
}
const typedIn = () => Boolean(ui.draft) && JSON.stringify(ui.draft) !== ui.draftBase;
function paneHtml() {
  if (ui.add) return addPaneHtml();
  const c = cmdByKey(ui.open);
  if (!c) return "";
  if (!ui.draft) takeDraft(c);
  const d = ui.draft, st = draftState(c);
  const n = Math.min(3, Math.max(1, ui.cols || 1));
  // a section with nothing in it draws nothing, so no plate stands empty
  const plates = PANE_ORDER.map((id) => tagSec(id, SECTION_DRAW[id](c, d, st))).filter((html) => html.trim()).map((html) => DRAW.plate(html, "pcol"));
  // every setting, opened from Settings' disclosure, takes the pane's own columns under the sections: forty-odd rows in
  // one column would stand beside two that end far above it
  const every = ui.effOpen && !data.knobsErr && !pending("knobs") ? `<div class="everywrap${ui.fx?.eff ? " unfold" : ""}" id="every-settings">${everySetHtml(c, n)}</div>` : "";
  return `<div class="pane${ui.grow ? " unfold" : ""}" style="--c:${colorOf(c.family)}">${paneHead(c)}`
    + `<div class="body">${DRAW.masonry(plates, 0, { cls: "pcols", gap: "var(--gap-col)", n })}${every}</div></div>`;
}
/** How many columns the open pane's body holds now, and how wide each is in rem; true when the count, or whether a
 *  column has room for the settings two to a row, changed since the last draw. */
function fitCols() {
  const pc = root.querySelector(".cmds .pane .pcols");
  if (!pc) return false;
  const n = Math.min(3, DRAW.colsFit(pc, PANE_COL_REM));
  const rem = parseFloat(getComputedStyle(document.documentElement).fontSize) || 16;
  const gap = parseFloat(getComputedStyle(pc).columnGap) || 0;
  const colW = (pc.clientWidth - gap * (n - 1)) / n / rem;
  const roomy = (w) => (w ?? 0) >= 66;
  const moved = n !== ui.cols || roomy(colW) !== roomy(ui.colW);
  ui.cols = n; ui.colW = colW;
  return moved;
}

function paneHead(c) {
  const k = look(c), s = stateOf(c);
  const fix = s?.fix && !ui.fixing[c.key] && ui.sign?.key !== c.key && ui.keyin?.key !== c.key
    ? DRAW.act({ word: s.fix.label, act: "fix", cls: "primary small", data: ` data-h="${esc(c.key)}"` }) : "";
  const ask = headAskHtml(c), sign = signHtml(c);
  // the state is said only when something is wrong, as on the card; how busy it is leads Right now
  const word = k.ghost ? GHOST.state() : k.word ? `<span class="word ${k.cls}">${esc(k.word)}</span>` : "";
  const said = ui.fixSaid?.key === c.key ? DRAW.result(ui.fixSaid.word, "bad") : "";
  return `<header style="--c:${colorOf(c.family)}"><span class="lamp ${k.cls}" aria-hidden="true">${k.lamp}</span>`
    + `<div class="who"><div class="top"><span class="name">${esc(c.cmd)}</span>${provSaid(c) ? "" : `<span class="prov">${esc(c.prov)}</span>`}</div>`
    + `${word || fix || said ? `<div class="meta">${word}${fix}${said}</div>` : ""}</div>`
    + `<div class="acts">${headActsHtml(c)}${removeHtml(c)}<button class="icon close" data-act="close" aria-label="Close">${GL.close}</button></div>`
    + `${sign ? `<div class="hask signrow">${sign}</div>` : ""}${ask ? `<div class="hask${ui.fx?.ask ? " unfold" : ""}">${ask}</div>` : ""}${removeAskHtml(c)}</header>`;
}
/** RIGHT NOW: what the command's gate holds this second, who is on it, and what it has taken since it started (GET
 *  /api/heads' gate and health). The hero is the requests in flight against the limit, over its meter; each session on
 *  it is one row, its oldest request's time held as a range bar on one log axis with a tick per request it has in
 *  flight; and everything it took since it started is ONE outcome bar, answered, in flight, errors in splice, errors
 *  from the provider and refused, each failure a door to those requests. A count that is zero draws nothing. */
function nowSection(c) {
  const h = data.heads.find((x) => x.key === c.key), g = h?.gate, e = h?.health ?? {};
  const max = !g || g.max === "unlimited" ? null : Number(g.max) || null;
  // a command that takes nothing now (its runtime does not answer, or it is not running) is a dash over a hatched
  // track, against the limit it would hold when one is set: the header already names what is wrong with it, so the
  // figure does not say it a third time. A running command with no gate read yet is the unread track.
  // The hero says what it counts on its own baseline, "4 in flight of 100 allowed at once", so the meter under it needs
  // no label of its own and runs the column's full width, ending where the outcome bar under it ends.
  const heroOf = (figure, level, tail) => `<div class="pd-top"><b class="pd-rate lv-${level}" data-fig="now:${esc(c.key)}">${esc(figure)}</b>`
    + `<span class="pd-failed">in flight${tail}</span></div>`;
  const limitWords = max ? ` of <b>${DRAW.n(max)}</b> allowed at once` : "";
  const still = (cls) => DRAW.section("Right now", heroOf("—", "ok", max ? limitWords : cls ? "" : `<span>, nothing read yet</span>`)
    + `<div class="flow">${DRAW.meter(null, cls)}</div>`, { cls: "snow" });
  if (c.down || !c.live) return still("down");
  if (!g) return still("");
  const flight = Number(g.inflight) || 0, frac = max ? Math.min(1, flight / max) : 0;
  const level = max && frac >= 0.8 ? "bad" : max && frac >= 0.5 ? "watch" : "ok";
  const hero = heroOf(DRAW.n(flight), level, max ? limitWords : "<span>, no limit set</span>");
  const meter = max
    ? `<div class="flow" data-meter="now:${esc(c.key)}">${DRAW.meter(frac * 100, frac >= 1 ? "full" : "")}</div>` : "";
  // the requests the gate holds, one row per session that sent them, the session holding the oldest first
  const bySess = new Map();
  for (const t of g.live ?? []) {
    const parts = String(t.label ?? "").split(" "), who = parts[0] || "";
    if (!bySess.has(who)) bySess.set(who, []);
    bySess.get(who).push({ ...t, age: Number(t.age_ms) || 0, model: parts.slice(1).join(" ") });
  }
  const groups = [...bySess.entries()].map(([who, ts]) => ({ who, ts, old: ts.reduce((a, b) => (b.age > a.age ? b : a)), compact: ts.some((t) => t.compact) }))
    .sort((a, b) => b.old.age - a.old.age);
  const lid = `live:${c.key}`, lopen = ui.more.has(lid), shown = lopen ? groups : groups.slice(0, 4);
  const ax = groups.length ? DRAW.axis(groups.flatMap((x) => x.ts.map((t) => t.age)), groups.map((x) => x.old.age)) : null;
  const rows = shown.map((x) => {
    const sess = x.who ? data.sessions.find((s) => String(s.session_id).startsWith(x.who)) : null;
    const who = DRAW.chip({ text: sess ? sess.name || sess.title || x.who : x.who || "Session not known", live: true,
      href: sess ? `sessions.html#${encodeURIComponent(sess.session_id)}` : null });
    const n = x.ts.length > 1 ? `<b class="cnt" title="${x.ts.length} requests in flight">${x.ts.length}</b>` : "";
    // one tick per request in flight from this session, at how long each has been held
    const ticks = x.ts.length > 1 ? x.ts.map((t) => `<u style="left:${ax.at(t.age).toFixed(2)}%"></u>`).join("") : "";
    // ONE LINE A SESSION. Streaming is the ordinary state and the bar already says it is running; only compacting and
    // the moment before the first byte are worth a word, a muted one, never the limit red. The model is named, by its
    // catalog name, only where it is not the one the command leads with, which its card and Models already name.
    const other = x.old.model && x.old.model !== c.mainId ? modelWord(c, x.old.model) : "";
    const word = [x.compact ? "Compacting" : x.old.phase === "connect" ? "no output yet" : "", other].filter(Boolean).join(" · ");
    return `<div class="pd-grid trow"><span class="pd-sl"><span class="who">${who}${n}</span></span>`
      + `<span>${DRAW.range(ax, x.old.age, x.old.age).replace(/<\/span>$/, `${ticks}</span>`)}</span><span class="pd-n">${DRAW.ms(x.old.age)}</span>`
      + `<span class="pd-n soft">${esc(word)}</span></div>`;
  }).join("");
  const rest = groups.length - 4;
  const tail = rest > 0 ? DRAW.more(rest, { word: rest === 1 ? "more session" : "more sessions", act: "more", data: ` data-m="${esc(lid)}"`, open: lopen }) : "";
  const onIt = groups.length ? `<div class="nowon${ui.fx?.more === lid ? " unfold" : ""}">${rows}${DRAW.axisRow(ax, "Held for")}${tail}</div>` : "";
  // SINCE IT STARTED, as one whole: every request it took, split by how it ended. A request in flight now has not
  // ended, so it is its own quiet part; the failures are the limit red by size, each a door to the failed requests.
  const acq = Number(g.acquired) || 0, refused = Number(g.refused) || 0;
  const le = Number(e.localOriginErrors) || 0, pe = Number(e.providerErrors) || 0;
  const served = Math.max(acq, le + pe + flight), total = served + refused;
  const fails = [{ n: le, word: "Errors in splice" }, { n: pe, word: "Errors from the provider" }, { n: refused, word: "Refused" }]
    .sort((a, b) => b.n - a.n).map((s, i) => ({ ...s, cls: `f${i}`, href: failDoor(c) }));
  const whole = total > 0
    ? `<div class="pd-sub"><b data-fig="total:${esc(c.key)}">${DRAW.n(total)}</b>${total === 1 ? "request since it started" : "requests since it started"}</div>`
      + `<div data-stack="out:${esc(c.key)}">` + DRAW.stack([{ n: Math.max(0, served - le - pe - flight), word: "Answered", cls: "ok", href: cmdDoor(c) },
        { n: flight, word: "In flight", cls: "nt" }, ...fails], total, { grow: Boolean(ui.grow), label: `${DRAW.n(total)} requests since it started` }) + "</div>"
    :`<div class="pd-pop"><div class="pd-bar none"></div><div class="pd-legend">${DRAW.nothing("No requests yet")}</div></div>`;
  // what waited and what was held back, as tiles that exist only above zero; the turn counts are turns, not requests,
  // so they stay tiles beside the whole rather than parts of it
  const since = DRAW.tiles([
    tile({ icon: DRAW.glyph.gap, n: g.queued, label: "waiting now" }),
    tile({ icon: DRAW.glyph.gap, n: g.waited, label: "waited in line" }),
    tile({ icon: DRAW.glyph.gap, n: g.avg_wait_ms, text: DRAW.ms(g.avg_wait_ms), label: "typical wait" }),
    tile({ icon: DRAW.glyph.cold, n: e.provider_rate_limit_turns, label: "turns rate limited", bad: true, href: failDoor(c) }),
    tile({ icon: DRAW.glyph.cold, n: e.cooldown_held_turns, label: "turns held in cooldown", bad: true, href: failDoor(c) }),
  ]);
  return DRAW.section("Right now", `${hero}${meter}${onIt}<div class="nowall">${whole}</div>${since}`, { cls: "snow" });
}
/** MODELS: which model answers each of Claude Code's four tiers, the window each is given drawn against the most that
 *  model takes, and the provider's other declared windows under them, all on ONE scale: the largest window or ceiling
 *  among the rows drawn here, so a window set past what its model serves is red at a glance and no model nobody sees
 *  sets the length. Each tier is one row, a group named by its tier: its picker, the tool glyph where the runtime says
 *  the model calls tools, its window, the bar and the most the model takes, and what is wrong with it under the row. */
function modelsSection(c, d, st) {
  const unset = !TIERS.some((x) => d.tiers[x].model);
  const bare = (id) => String(id).replace(/\[[^\]]*\]$/, "");
  const nameOfId = (id) => (c.models.find((m) => m.id === id) ?? c.models.find((m) => bare(m.id) === bare(id)))?.label ?? id;
  const was = draftOf(c), hw = parseWindow(d.headWin);
  const rows = TIERS.map((t) => {
    const sel = d.tiers[t], ts = st.tiers[t];
    const picked = c.models.find((x) => x.id === sel.model), dm = c.models.find((x) => x.id === c.defaults[t]);
    const lost = picked ? null : c.missing.find((x) => x.id === sel.model) ?? null;
    const asDefault = !picked && !lost && unset && dm ? dm : null;
    const id = picked?.id ?? asDefault?.id ?? null;
    const facts = id ? modelFacts(c, id) : { most: null, now: false, tools: null, marks: "" };
    const most = asDefault && st.head.max != null ? st.head.max : facts.most;
    const drawn = picked ? parseWindow(sel.win) : asDefault ? hw : null;
    return { t, sel, ts, picked, dm, lost, asDefault, id, facts, most, drawn };
  });
  // the provider's other windows: the models it declares that no tier answers with, each once, then its id rules
  const onTier = new Set(rows.map((r) => r.id).filter(Boolean).map(bare)), seenName = new Set();
  const declared = [...c.extraWindows.filter((w) => !onTier.has(bare(w.id))).map((w) => ({ name: nameOfId(w.id), win: w.context_window, max: w.max_context_window ?? null })),
    ...c.windowRules.map((w) => ({ name: `Ids starting ${w.prefix}`, win: w.context_window, max: null }))]
    .filter((x) => !seenName.has(x.name) && seenName.add(x.name));
  const did = `decl:${c.key}`, dopen = ui.more.has(did), dshown = dopen ? declared : declared.slice(0, 6);
  const all = [...rows.flatMap((r) => [r.drawn, r.most]), ...dshown.flatMap((x) => [x.win, x.max])].filter((v) => v > 0);
  const scale = all.length ? Math.max(...all) : 0;
  // a ceiling every named tier shares is said once, in its column's head; each row keeps its tick on the bar
  const mosts = rows.filter((r) => r.id).map((r) => r.most);
  const oneMost = mosts.length > 1 && mosts[0] != null && mosts.every((v) => v === mosts[0]) && !rows.some((r) => r.facts.now) ? mosts[0] : null;
  const sorted = [...c.models].sort((a, b) => (b.window ?? 0) - (a.window ?? 0) || a.label.localeCompare(b.label));
  // FOUR TIERS ON ONE MODEL ARE ONE ROW. Where every tier answers with the same model at the same window (a local
  // runtime serving one model, or a command set that way), the tiers read as one row, Every tier, whose picker and
  // window set all four; Set each tier splits it back into four rows, which is how one tier is given another model.
  const winOf = (r) => (r.picked ? `set:${r.sel.win}` : "default");
  const merged = !ui.split?.has(c.key) && rows.every((r) => r.id && r.id === rows[0].id && winOf(r) === winOf(rows[0]));
  const tierRow = (r) => {
    const { sel, picked, dm, lost, asDefault, id, facts, most, drawn } = r;
    const t = merged ? "all" : r.t, ts = r.ts, wasWin = was.tiers[r.t].win;
    const open = ui.pick === `tier:${t}`;
    // clearing this tier gives its default back when the other three are unset too, else no model (a tier left out is a
    // 400); clearing every tier gives each its default
    const alone = merged || TIERS.every((x) => x === t || !d.tiers[x].model);
    const oneDefault = merged ? new Set(TIERS.map((x) => c.defaults[x])).size === 1 : true;
    const clear = alone && dm ? `<span>${oneDefault ? esc(dm.label) : "Each tier's own"}</span><span class="dflt">Default</span>` : "<span>No model</span>";
    const q = (ui.mq ?? "").trim().toLowerCase();
    const list = q ? sorted.filter((x) => x.label.toLowerCase().includes(q) || x.id.toLowerCase().includes(q)) : sorted;
    // a roster of hundreds (OpenRouter lists 361) is found by typing, with the count of what matches said once
    const filter = c.models.length > 12
      ? `<div class="mfilter"><input class="field" id="mq" value="${esc(ui.mq ?? "")}" placeholder="Find a model" autocomplete="off" spellcheck="false" aria-label="Find a model">`
        + `<span class="pd-count"><b>${DRAW.n(list.length)}</b> of ${DRAW.n(c.models.length)}</span></div>` : "";
    // the menu: the models by window, largest first, each window a bar on this section's scale, the one in use marked.
    // A roster from many makers ("Maker: Model") reads under one head per maker, a to z, each name without the
    // maker it repeats; a roster from one maker stays one list.
    const inUse = picked?.id ?? asDefault?.id ?? null;
    const mrow = (x, name) => `<button role="menuitem" data-act="tier" data-t="${t}" data-v="${esc(x.id)}"${x.id === inUse ? ' aria-current="true"' : ""} title="${esc(x.label)}">`
      + `<span class="mname">${esc(name)}</span><span class="mwin">${winBar(x.window, x.max, scale)}</span><span class="mmeta">${x.window ? big(x.window) : DASH}</span></button>`;
    const makerOf = (x) => /^([^:]{1,32}):\s+(.+)$/.exec(x.label);
    const byMaker = list.filter(makerOf).length >= Math.max(2, list.length / 2);
    let rowsHtml = list.map((x) => mrow(x, x.label)).join("");
    if (byMaker) {
      const by = new Map();
      for (const x of list) {
        const m = makerOf(x), k = m ? m[1] : "Others";
        if (!by.has(k)) by.set(k, []);
        by.get(k).push(mrow(x, m ? m[2] : x.label));
      }
      rowsHtml = [...by.entries()].sort((a, b) => a[0].localeCompare(b[0]))
        .map(([k, xs]) => `<div class="mgroup" role="group" aria-label="${esc(k)}"><span class="mhead">${esc(k)}</span>${xs.join("")}</div>`).join("");
    }
    const items = `${filter}<button role="menuitem" data-act="tier" data-t="${t}" data-v=""${inUse ? "" : ' aria-current="true"'}>${clear}</button>`
      + `${rowsHtml || (q ? DRAW.nothing("No model matches") : "")}`;
    // the name fills the picker and is cut with an ellipsis rather than wrapping a row three lines tall; the whole
    // name is on the button as its title and in the menu it opens. A tier left out reads None behind the lock.
    const named = picked ?? lost ?? asDefault;
    const face = named ? `<span class="pf">${esc(named.label)}</span>${asDefault ? '<span class="dflt">Default</span>' : ""}`
      : `<span class="pf">${ICON.lock}None</span>`;
    const label = merged ? "Every tier" : tierName(t);
    const win = picked ? `<input class="field win${dirt(sel.win !== wasWin)}" id="h-win-${t}" data-t="${t}" value="${esc(sel.win)}" spellcheck="false" autocomplete="off" aria-label="${label} window">`
      : asDefault ? `<input class="field win${dirt(d.headWin !== was.headWin)}" id="h-win-${t}" data-hw="1" value="${esc(d.headWin)}" spellcheck="false" autocomplete="off" aria-label="${label} window">` : "";
    const wrong = picked ? !(ts.ok || ts.over) : asDefault && !st.head.ok && !st.head.over;
    const over = picked ? ts.over : asDefault && st.head.over;
    const marks = [facts.marks, lost ? notServed(lost) : "",
      // a tier left out is never sent to Claude Code, which then asks for its own built-in model; the command refuses it
      // with a 400, so a subagent or tool call on this tier fails (LaunchModelEnvironment.kt:21-23, gap 472)
      !named ? `<span class="said limit">Subagents on it fail</span>` : "",
      wrong ? `<span class="said limit">Not a window</span>` : "",
      merged ? DRAW.act({ word: "Set each tier", act: "splittiers", cls: "quiet small" }) : ""].filter(Boolean).join("");
    const mostCell = oneMost != null ? "" : most ? `${big(most)}${facts.now ? '<small class="srv">now</small>' : ""}` : id ? DASH : "";
    return `<div class="tier" role="group" aria-labelledby="tl-${t}"><span class="lbl" id="tl-${t}">${label}</span>`
      + `<div class="ctl menuwrap"><button class="pickbtn${picked || lost ? "" : asDefault ? " dflt-tier" : " empty"}" type="button" data-act="tierpick" data-t="${t}" aria-haspopup="menu" aria-expanded="${open}" style="--c:${colorOf(c.family)}"${named ? ` title="${esc(named.label)}"` : ""}${lost || !named ? ' aria-invalid="true"' : ""}>${face}${ICON.caret}</button>`
      + `${open ? `<div class="menu mpick${ui.fx?.menu ? " unfold" : ""}" role="menu">${items}</div>` : ""}</div>`
      + `<span class="tglyph">${facts.tools ? toolsMark() : ""}</span>`
      + `<div class="ctl wincell">${win}</div>`
      + `<span class="wcell">${id || drawn ? winBar(drawn, most, scale, Boolean(over)) : ""}</span>`
      + `<span class="pd-n${over ? " lv-bad" : ""}">${mostCell}</span>`
      + `${marks ? `<span class="marks">${marks}</span>` : ""}</div>`;
  };
  const dRows = dshown.map((x) => `<div class="tier other"><span class="lbl oname">${esc(x.name)}</span><span class="pd-n wv">${x.win ? big(x.win) : DASH}</span>`
    + `<span class="wcell">${winBar(x.win, x.max, scale)}</span><span class="pd-n">${oneMost != null && x.max === oneMost ? "" : x.max ? big(x.max) : DASH}</span></div>`).join("");
  const drest = declared.length - dshown.length;
  const dmore = drest > 0 || dopen ? DRAW.more(drest, { word: drest === 1 ? "more model" : "more models", act: "more", data: ` data-m="${esc(did)}"`, open: dopen }) : "";
  const heads = `<div class="tier thead"><span></span><span class="chead">Model</span><span></span><span class="chead">Window</span><span></span>`
    + `<span class="chead">Most it takes${oneMost != null ? ` <b>${big(oneMost)}</b>` : ""}</span></div>`;
  // the windows are the provider's, which every command on it shares: the others are named once, under the tiers
  const shared = rows.some((r) => r.picked) ? sharedHtml(c) : "";
  const other = dRows ? `<div class="tier thead osub"><span class="chead">Other models</span></div>${dRows}` : "";
  const body = `<div class="tiers">${heads}${(merged ? rows.slice(0, 1) : rows).map(tierRow).join("")}${shared ? `<div class="tier tfoot"><span class="marks">${shared}</span></div>` : ""}${other}</div>${dmore}`;
  const legend = rows.some((r) => r.facts.tools) ? `<span class="pd-leg tleg">${GL.tools}Calls tools</span>` : "";
  return DRAW.section("Models", body + secSave(c, "models", st), { aside: `${runtimeAside(c)}${legend}` });
}
/** CLAUDE CODE'S WINDOW: the window Claude Code itself counts against, as each session on this command reported it
 *  (GET /api/sessions' client_context_window). Grouped by window, the window the focal figure with its sessions as
 *  chips, one chip per name with a count where sessions share it, four shown and twenty more per press; a session
 *  that reported none is left out, never guessed. */
function clientSection(c) {
  const seen = data.sessions.filter((x) => x.head === c.key && x.client_context_window > 0);
  if (!seen.length) {
    return pending("sessions") ? DRAW.section("Claude Code's window", GHOST.focal(8) + `<div class="chips">${GHOST.chip()}${GHOST.chip(7)}${GHOST.chip(5)}</div>`) : "";
  }
  const by = new Map();
  for (const s of seen) {
    const w = Number(s.client_context_window);
    if (!by.has(w)) by.set(w, []);
    by.get(w).push(s);
  }
  const live = (s) => s.status === "working" || s.status === "busy";
  // each window is a bar on one scale with the window splice gives the command's main model, its tick, so a session
  // counting against less than the model is given reads short of the tick before a figure is read
  const main = c.models.find((m) => m.id === c.mainId) ?? null;
  const scale = Math.max(...by.keys(), main?.window ?? 0, main?.max ?? 0);
  const body = [...by.entries()].sort((a, b) => b[0] - a[0]).map(([w, ss]) => {
    // sessions of one name are one chip with their count; the chip opens the first of them, a live one first
    const names = new Map();
    for (const s of [...ss].sort((a, b) => Number(live(b)) - Number(live(a)))) {
      const name = s.name || s.title || String(s.session_id).slice(0, 8);
      const g = names.get(name) ?? { name, n: 0, live: false, s };
      g.n += 1; g.live ||= live(s);
      names.set(name, g);
    }
    const list = [...names.values()];
    const id = `win:${c.key}:${w}`, cap = ui.moreN?.[id] ?? 4, shown = list.slice(0, cap), rest = list.length - shown.length;
    const chips = shown.map((g) => `<span class="cgrp">${DRAW.chip({ text: g.name, live: g.live, href: `sessions.html#${encodeURIComponent(g.s.session_id)}` })}`
      + `${g.n > 1 ? `<b class="cnt" title="${g.n} sessions">${g.n}</b>` : ""}</span>`).join("");
    const pill = rest > 0 ? DRAW.more(rest, { word: rest === 1 ? "more session" : "more sessions", act: "moren", data: ` data-m="${esc(id)}"` })
      : cap > 4 ? DRAW.more(0, { act: "moren", data: ` data-m="${esc(id)}" data-less="1"`, open: true }) : "";
    return `<div class="wgroup"><div class="wline"><b class="wfig">${esc(big(w))}</b><span class="wcell">${winBar(w, main?.window ?? null, scale)}</span>`
      + `<span class="pd-count"><b>${DRAW.n(ss.length)}</b> ${ss.length === 1 ? "session" : "sessions"}</span></div>`
      + `<div class="chips${ui.fx?.more === id ? " unfold" : ""}">${chips}${pill}</div></div>`;
  }).join("");
  return DRAW.section("Claude Code's window", body);
}
/** PRICES: what splice charges a token at on this command, each price a bar on its column's scale so the dearest
 *  model is found by eye. The models its tiers answer with and the priced ones lead; the rest wait behind one count,
 *  a roster past twelve models is found by typing, and a model with no price is a dash, never a zero. */
function pricesSection(c, d, st) {
  if (!c.models.length) return "";
  const s = st.own, was = fieldsOf(c).rates;
  const num = (v) => (Number(v) > 0 ? Number(v) : 0);
  const scale = Math.max(0, ...c.models.flatMap((m) => [num(d.rates[m.id]?.i), num(d.rates[m.id]?.o)]));
  const tiers = new Set(TIERS.map((t) => d.tiers[t].model).filter(Boolean));
  const q = (ui.prq ?? "").trim().toLowerCase();
  const pool = q ? c.models.filter((m) => m.label.toLowerCase().includes(q) || m.id.toLowerCase().includes(q)) : c.models;
  const lead = pool.filter((m) => tiers.has(m.id) || num(d.rates[m.id]?.i) || num(d.rates[m.id]?.o));
  const ordered = [...lead, ...pool.filter((m) => !lead.includes(m))];
  // the rest come twenty a press, never all of a roster of hundreds at once
  const id = `prices:${c.key}`, cap = ui.moreN?.[id] ?? 6;
  const shown = ordered.slice(0, cap), rest = ordered.length - shown.length;
  const cell = (m, k) => `<div class="ctl pcell"><input class="field price${dirt(d.rates[m.id][k] !== was[m.id][k])}" id="pr-${k}-${esc(m.id)}" data-rate="${esc(m.id)}" data-rk="${k}" value="${esc(d.rates[m.id][k])}" placeholder="—" inputmode="decimal" spellcheck="false" autocomplete="off" aria-label="${esc(m.label)}, ${k === "i" ? "in" : "out"} per 1M tokens">${bad(s.rate[m.id][k], "Not a price")}</div>`;
  const rows = shown.map((m) => {
    const cache = cacheShown(c, m.id, d.rates[m.id])
      ? `<span class="marks"><span class="lbl">Cached</span><input class="field price dirty" id="pr-c-${esc(m.id)}" data-rate="${esc(m.id)}" data-rk="c" value="${esc(d.rates[m.id].c ?? d.rates[m.id].i)}" inputmode="decimal" spellcheck="false" autocomplete="off" aria-label="${esc(m.label)}, cached per 1M tokens">${bad(s.rate[m.id].c, "Not a price")}</span>`
      : "";
    // each price is a bar on its column's one scale with its figure after it, so the figures end on one edge per column
    return `<span class="lbl mname">${esc(m.label)}</span>`
      + `<span class="wcell">${DRAW.vbar(scale ? (num(d.rates[m.id].i) / scale) * 100 : 0)}</span>${cell(m, "i")}`
      + `<span class="wcell">${DRAW.vbar(scale ? (num(d.rates[m.id].o) / scale) * 100 : 0)}</span>${cell(m, "o")}${cache}`;
  }).join("");
  const pill = rest > 0 ? DRAW.more(rest, { word: rest === 1 ? "more model" : "more models", act: "moren", data: ` data-m="${esc(id)}" data-base="6"` })
    : cap > 6 ? DRAW.more(0, { act: "moren", data: ` data-m="${esc(id)}" data-less="1"`, open: true }) : "";
  // a roster past twelve models is found by typing, the count of what matches said once beside the field
  const find = c.models.length > 12
    ? `<div class="search pfind">${SEARCH_GLYPH}<input class="field" id="prq" value="${esc(ui.prq ?? "")}" placeholder="Find a model" autocomplete="off" spellcheck="false" aria-label="Find a model's price">`
      + `<span class="pd-count"><b>${DRAW.n(pool.length)}</b> of ${DRAW.n(c.models.length)}</span></div>` : "";
  const heads = `<span class="chead"></span><span class="chead"></span><span class="chead fig">In</span><span class="chead"></span><span class="chead fig">Out</span>`;
  const none = q && !pool.length ? DRAW.nothing("No model matches") : "";
  return DRAW.section("Prices", `${find}${rows ? `<div class="prices${ui.fx?.more === id ? " unfold" : ""}">${heads}${rows}</div>` : none}${pill}${secSave(c, "prices", st)}`,
    { aside: `<span class="pd-count">$ per 1M tokens</span>` });
}
/** WHERE IT GOES: the address this command sends to, the port Claude Code reaches it on here, and the prefix each of
 *  its models wears in Claude Code's picker, with the name it will read. The ports every other command holds are
 *  drawn beside it, so "each command needs its own" needs no sentence, and a clash names the command that holds it. */
function whereSection(c, d, st) {
  const was = fieldsOf(c), s = st.own;
  const others = commandsOf().filter((x) => x.key !== c.key)
    .map((x) => ({ cmd: x.cmd, fam: x.family, port: fieldsOf(x).port })).filter((x) => x.port);
  const clash = others.find((x) => x.port === String(d.port).trim());
  const pid = `ports:${c.key}`, popen = ui.more.has(pid);
  const seen = popen ? others : others.slice(0, 3), prest = others.length - seen.length;
  const taken = clash
    ? `<span class="said limit">Taken by</span>${DRAW.chip({ text: clash.cmd, data: ` style="--c:${colorOf(clash.fam)}"` })}`
    : `${seen.map((x) => DRAW.chip({ text: `${x.cmd} ${x.port}`, cls: "flat", data: ` style="--c:${colorOf(x.fam)}"` })).join("")}`
      + `${prest > 0 || popen ? DRAW.more(prest, { word: prest === 1 ? "more port" : "more ports", act: "more", data: ` data-m="${esc(pid)}"`, open: popen }) : ""}`;
  const preview = c.mainId
    ? `<span class="prev"><span class="plbl">Claude Code shows</span><code>${esc(d.prefix)}${esc(c.mainId)}</code></span>` : "";
  return DRAW.section("Where it goes", '<div class="form">'
    + `<span class="lbl">Sends to</span><div class="ctl"><input class="field url${dirt(d.url !== c.url)}" id="h-url" value="${esc(d.url)}" spellcheck="false" autocomplete="off" aria-label="Sends to"${st.url.ok ? "" : ' aria-invalid="true"'}>`
    + `${st.url.ok ? "" : `<span class="said limit">Not an address</span>`}${sharedHtml(c)}</div>`
    + `<span class="lbl">Port on this computer</span><div class="ctl"><input class="field num${dirt(d.port !== was.port)}" id="h-port" value="${esc(d.port)}" inputmode="numeric" spellcheck="false" autocomplete="off" aria-label="Port"${s.port ? "" : ' aria-invalid="true"'}>${bad(s.port, "Not a port")}<span class="ports">${taken}</span></div>`
    + `<span class="lbl">Model prefix</span><div class="ctl"><input class="field mono prefix${dirt(d.prefix !== was.prefix)}" id="h-prefix" value="${esc(d.prefix)}" spellcheck="false" autocomplete="off" aria-label="Model prefix"${s.prefix ? "" : ' aria-invalid="true"'}>${bad(s.prefix, "Needs a prefix")}${preview}</div>`
    + `</div>${secSave(c, "where", st)}`);
}
/** SIGN-IN: whether this command can reach its provider, read from doctor's own check, then how it signs in, the
 *  variable its key is read from and the headers it sends. The sign-in box and the key form open in the header,
 *  where Sign in was pressed. */
function authLook(c) {
  if (pending("doctor")) return { ghost: true };
  const row = (data.doctor?.checks ?? []).find((x) => x.id === `auth/${c.key}`);
  if (!row) return null;
  const ok = row.status === "ok" || row.status === "info";
  return { ok, word: authWord(c, row) };
}
/** doctor's sign-in check for a command, in his words: the same word on the card, in the header and in Sign-in. */
function authWord(c, row) {
  const ok = row.status === "ok" || row.status === "info", keyed = /key/i.test(c.auth);
  if (ok) return keyed ? "Key in place" : "Signed in";
  return /refus|reject|invalid|401/i.test(row.detail ?? "") ? "Credentials refused" : keyed ? "No key" : "Signed out";
}
function signInSection(c, d, st) {
  const was = fieldsOf(c), s = st.own;
  const kinds = vocab().auth_kinds ?? [];
  const open = ui.pick === "kind";
  const items = kinds.map((x) => `<button role="menuitem" data-act="kind" data-v="${esc(x.kind)}"${x.kind === d.kind ? ' aria-current="true"' : ""}><span>${esc(x.label)}</span></button>`).join("");
  const a = authLook(c);
  // its state leads the section on the kicker's line, in words, with its lamp
  const lamp = !a ? "" : a.ghost
    ? `<span class="raside"><span class="lamp inline working" aria-hidden="true">${WAIT}</span>${GHOST.state()}</span>`
    : `<span class="raside"><span class="lamp inline ${a.ok ? "ready" : "stalled"}" aria-hidden="true">${a.ok ? "<i></i>" : ICON.signout}</span><span class="said${a.ok ? "" : " limit"}">${esc(a.word)}</span></span>`;
  const env = d.kind === "api-key"
    ? `<span class="lbl">Key variable</span><div class="ctl"><input class="field mono env${dirt(d.env !== was.env)}" id="h-env" value="${esc(d.env)}" placeholder="${esc(`${c.provKey.toUpperCase()}_API_KEY`)}" spellcheck="false" autocomplete="off" aria-label="Key variable"${s.env ? "" : ' aria-invalid="true"'}>${bad(s.env, "Not a variable name")}</div>` : "";
  const rows = d.headers.map((x, i) => `<div class="hdr">${x.fresh ? `<input class="field mono" id="hn-${i}" data-hn="${i}" value="${esc(x.name)}" placeholder="Name" spellcheck="false" autocomplete="off" aria-label="Header name">` : `<span class="hname">${esc(x.name)}</span>`}`
    + `<input class="field mono${dirt(x.fresh || x.value !== MASKED)}" type="password" id="hv-${i}" data-hv="${i}" value="${x.value === MASKED ? "" : esc(x.value)}" placeholder="${x.value === MASKED ? "••••" : "Value"}" autocomplete="off" aria-label="${esc(x.name || "Header")} value">`
    + `<button class="icon" data-act="hdr-rm" data-i="${i}" aria-label="Remove ${esc(x.name || "header")}">${GL.close}</button>${bad(s.header[i], "Needs a name and a value")}</div>`).join("");
  return DRAW.section("Sign-in", '<div class="form">'
    + `<span class="lbl">Signs in with</span><div class="ctl menuwrap"><button class="pickbtn kindbtn" type="button" data-act="kindpick" aria-haspopup="menu" aria-expanded="${open}">${esc(kindName(d.kind))}${ICON.caret}</button>`
    + `${open ? `<div class="menu kmenu${ui.fx?.menu ? " unfold" : ""}" role="menu">${items}</div>` : ""}${sharedHtml(c)}</div>`
    + env
    + `<span class="lbl">Headers</span><div class="ctl col">${rows}${DRAW.addTile({ word: "Add header", act: "hdr-add", cls: "row" })}</div>`
    + `</div>${secSave(c, "signin", st)}`, { aside: lamp });
}
/** INSTRUCTIONS: the system prompt this command adds to, replaces or strips, from a file or written here. */
function promptSection(c, d, st) {
  return DRAW.section("Instructions", '<div class="form">'
    + `<span class="lbl">System prompt</span><div class="ctl col">${modeSwitch("head", d.mode)}`
    + `${d.file ? pfile(d.file) : `<textarea class="field ptext" id="h-text" rows="3" placeholder="Nothing added">${esc(d.text)}</textarea>`}</div></div>`
    + secSave(c, "prompt", st));
}
/** PROJECTS: the prompt this command adds inside one project, by project, and the dashed place the next one lands. */
function projectsSection(c, d, st) {
  // each field carries an id, as every other field in the pane does, so the redraw every few seconds gives the focus and
  // the caret back to it: without one, a layer being typed lost its focus at the next redraw
  // each layer is its project: its name, where it sits, its live sessions, then what the layer does and its remove
  // together at the row's end, and the text it adds
  const pst = Object.keys(d.per).length ? projectStats() : null;
  const per = Object.entries(d.per).map(([root, l], i) => {
    const pr = data.projects.find((x) => samePath(x.root, root)), s = (pr && pst.get(pr.id)) || NO_STATS;
    return `<div class="per"><div class="per-top"><span class="pname">${esc(nameOfRoot(root))}</span><span class="ppath" title="${esc(root)}"><bdo dir="ltr">${esc(homeRel(root))}</bdo></span>`
      + `${s.live ? `<span class="plive"><i aria-hidden="true"></i><b>${DRAW.n(s.live)}</b></span>` : ""}<span class="pend">${modeSwitch(`per:${root}`, l.mode)}`
      + `<button class="icon" data-act="unper" data-p="${esc(root)}" aria-label="Remove ${esc(nameOfRoot(root))}">${GL.close}</button></span></div>`
      + `${l.file ? pfile(l.file) : `<textarea class="field ptext" id="pt-${i}" data-ptext="${esc(root)}" rows="2" placeholder="Nothing added" aria-label="${esc(nameOfRoot(root))}: system prompt here">${esc(l.text)}</textarea>`}</div>`;
  }).join("");
  const free = data.projects.filter((p) => !d.per[p.root]);
  const open = ui.pick === "per";
  // a long list of projects is found by typing, as every long menu on the page is
  const q = (ui.ppq ?? "").trim().toLowerCase();
  const list = q ? free.filter((p) => `${p.name} ${homeRel(p.root)}`.toLowerCase().includes(q)) : free;
  const find = free.length > 12
    ? `<div class="mfilter"><input class="field" id="ppq" value="${esc(ui.ppq ?? "")}" placeholder="Find a project" autocomplete="off" spellcheck="false" aria-label="Find a project">`
      + `<span class="pd-count"><b>${DRAW.n(list.length)}</b> of ${DRAW.n(free.length)}</span></div>` : "";
  // the projects as the Projects tab lists them: under the folder they share, the ones with live sessions first, each
  // with its live sessions as the live dot and its count, and its sessions in all, so where he works is found by eye
  const st0 = open ? projectStats() : null;
  const groups = open ? projectFolders(list, st0).map((g) => {
    const at = g.dir ? homeRel(g.dir) : "Elsewhere";
    return `<div class="mgroup" role="group" aria-label="${esc(at)}"><span class="mhead">${esc(at)}</span>`
      + g.list.map((p) => {
        const s = st0.get(p.id) ?? NO_STATS;
        return `<button role="menuitem" data-act="per" data-p="${esc(p.root)}"><span>${esc(p.name)}</span><span class="pmeta">`
          + `${s.live ? `<span class="plive"><i aria-hidden="true"></i><b>${DRAW.n(s.live)}</b></span>` : ""}<span class="mmeta">${s.total ? DRAW.n(s.total) : ""}</span></span></button>`;
      }).join("") + "</div>";
  }).join("") : "";
  const menu = open ? `<div class="menu ppick${ui.fx?.menu ? " unfold" : ""}" role="menu">${find}${groups || DRAW.nothing("No project matches")}`
    + `${list.length ? `<div class="mfoot"><span class="plive"><i aria-hidden="true"></i>live now</span><span class="mmeta">sessions in all</span></div>` : ""}</div>` : "";
  const add = free.length
    ? `<div class="menuwrap">${DRAW.addTile({ word: "Add project", act: "perpick", cls: "row", data: ` aria-haspopup="menu" aria-expanded="${open}"` })}${menu}</div>`
    : "";
  if (!per && !add) return "";
  return DRAW.section("Projects", `<div class="pers">${per}${add}</div>${secSave(c, "projects", st)}`,
    { aside: Object.keys(d.per).length ? `<span class="pd-count"><b>${DRAW.n(Object.keys(d.per).length)}</b> with their own</span>` : "" });
}
/** A command just added (spec 1i): how to start it, what doctor says of it, and the one live request on offer. */
function addedSection(c) {
  const rows = addedHtml(c);
  return rows ? DRAW.section("Just added", `<div class="form">${rows}</div>`) : "";
}
/** SETTINGS: the settings this command holds its own value for, each with its control and the way back to the value
 *  every command uses, then every setting as this command runs it, opened in place under one disclosure and grouped
 *  by the job Settings draws it under. */
function settingsSection(c) {
  if (data.knobsErr || pending("knobs")) {
    return DRAW.section("Settings", data.knobsErr ? DRAW.result("Not read", "bad") : GHOST.lines([14, 11]));
  }
  // what this command holds its own value for, with the marks those values carry said once on the kicker's line
  const own = COMMAND_KNOBS.filter((k) => KNOBS.KNOB[k] && KNOBS.ownValue(k, c.key) !== undefined);
  const aside = own.length ? `<span class="kn">${KNOBS.legendHtml(own)}</span>` : "";
  return DRAW.section("Settings", `<div class="form kn">${KNOBS.commandRowsHtml(c.key, COMMAND_KNOBS)}</div>${effHtml()}`, { aside });
}
/** LOG: this command's own lines from the daemon's log, as events in his words with their times and repeats, a search
 *  that counts the lines it matched of all of them, and the raw lines behind one disclosure: the last hundred, older
 *  ones a press away. A search that matches lines but no event opens those lines, with the term marked in each. */
function logSection(c) {
  const q = ui.logq.trim().toLowerCase(), hit = (l) => !q || l.raw.toLowerCase().includes(q);
  const read = logsOf(c.key), held = read ?? [];
  const lines = held.filter(hit);
  const ev = eventsOf(lines);
  const raw = lines.filter((l) => l.raw);
  const path = ui.logPath[c.key], why = ui.logWhy[c.key];
  const most = Math.max(1, ...ev.map((e) => e.n));
  // the count follows the query: the lines it matched of every line read, and Clear beside it when it matched none
  const top = `<div class="loghead"><div class="search">${SEARCH_GLYPH}<input class="field" id="logq" placeholder="Search the log" value="${esc(ui.logq)}" autocomplete="off" spellcheck="false" aria-label="Search the log"></div>`
    + `${q ? `<span class="pd-count"><b>${DRAW.n(raw.length)}</b> of ${DRAW.n(held.length)} lines</span>` : ""}`
    + `${q && !raw.length ? DRAW.act({ word: "Clear", act: "clearlogq", cls: "quiet small" }) : ""}`
    + DRAW.act({ word: ui.logPaused ? "Resume" : "Pause", act: "logpause", cls: "quiet small", data: ` aria-pressed="${ui.logPaused}"` })
    + "</div>";
  // a search that finds lines and no event shows the lines it found, so "no match" is never said beside a count of matches
  const rawOpen = Boolean(ui.rawOpen) || (Boolean(q) && !ev.length && raw.length > 0);
  const body = why ? DRAW.result(why, "bad")
    : !read ? GHOST.lines([22, 17, 19])
    : ev.length ? `<ol class="events">${ev.map((e) => `<li><time>${esc(ampm(e.at))}</time><span class="etext">${esc(e.text)}</span>`
      + `<span class="ecount">${e.n > 1 ? DRAW.barCell({ w: (e.n / most) * 100, text: `${DRAW.n(e.n)}×` }) : ""}</span></li>`).join("")}</ol>`
    : q ? (raw.length ? "" : DRAW.nothing("No lines match"))
    : DRAW.nothing("No events");
  const nraw = ui.rawN ?? 100;
  const tail = raw.slice(-nraw), left = raw.length - tail.length;
  const inner = `<ol class="rawlines${ui.fx?.raw ? " unfold" : ""}">${left > 0 ? `<li>${DRAW.more(left, { word: left === 1 ? "older line" : "older lines", act: "rawmore" })}</li>` : ""}`
    + `${tail.map((l) => `<li><span>${marked(l.raw, q)}</span></li>`).join("")}</ol>`;
  const disc = `<div class="rawwrap">${DRAW.disc({ open: rawOpen, act: "rawtoggle",
    inner: `<span>Raw log</span><span class="mmeta">${DRAW.n(raw.length)} ${raw.length === 1 ? "line" : "lines"}</span>` })}`
    + `${rawOpen ? inner : ""}</div>`;
  // the file the lines come from is named on the kicker's line by its own name, its full place on the name's title
  const file = path ? `<span class="pfile logfile" title="${esc(homeRel(path))}">${GL.file}<span>${esc(path.split("/").pop())}</span></span>` : "";
  return DRAW.section("Log", `${top}${body}${raw.length ? disc : ""}`, { cls: "hlog", aside: file });
}
/** The term he searched for, marked in a line of the raw log. */
function marked(text, q) {
  if (!q) return esc(text);
  const low = text.toLowerCase();
  let out = "", i = 0;
  for (let at = low.indexOf(q); at >= 0; at = low.indexOf(q, i)) {
    out += `${esc(text.slice(i, at))}<mark>${esc(text.slice(at, at + q.length))}</mark>`;
    i = at + q.length;
  }
  return out + esc(text.slice(i));
}
// ---------- Save, in each section: its own changes, its own Save, its own result ----------
// A SECTION'S SAVE WRITES ONLY THAT SECTION (the per-section pass, Oct 27). One bar at the pane's foot listed every
// change in the pane and saved them all at once, far from the field each came from. Now a section that holds a change
// ends in its own row: each change with the value it had, struck, and the one it takes, a restart glyph where splice
// reads it only when it starts, and Save and Discard. A change in another section waits there, listed in its own row.
const SECTION_FIELDS = {
  where: ["url", "port", "prefix"], signin: ["kind", "env", "headers"], models: ["tiers", "headWin"], prices: ["rates"],
  running: ["local", "isolate", "quirks"], prompt: ["mode", "text", "file"], projects: ["per"],
};
const fieldsJson = (d, sec) => JSON.stringify(SECTION_FIELDS[sec].map((f) => d[f]));
const secDirty = (c, sec) => Boolean(ui.draft) && fieldsJson(ui.draft, sec) !== fieldsJson(draftOf(c), sec);
const dirtySecs = (c) => Object.keys(SECTION_FIELDS).filter((s) => secDirty(c, s));
// what stops one section's Save: a field in that section the daemon would refuse
function secOk(st, sec) {
  const o = st.own;
  if (sec === "where") return st.url.ok && o.port && o.prefix;
  if (sec === "signin") return o.env && o.header.every(Boolean);
  if (sec === "models") return TIERS.every((t) => st.tiers[t].ok) && st.head.ok;
  if (sec === "prices") return Object.values(o.rate).every((x) => x.i && x.o && x.c);
  if (sec === "running") return Object.values(o.quirk).every(Boolean);
  return true;
}
function secSave(c, sec, st) {
  // saved but read only when splice starts: the section that saved it keeps saying so, with the act that brings it in
  // beside the word, until splice restarts, whatever is typed in the meantime
  const due = ui.pending && ui.pendingAt?.key === c.key && ui.pendingAt.sec === sec;
  const dirty = secDirty(c, sec), said = ui.said?.sec === sec ? ui.said.word : due ? "Saved" : null;
  const restart = due && (said === "Saved" || said === "Not restarted") ? restartBtn() : "";
  if (!dirty && !said) return "";
  // a row only begun (a header with no name yet) is no change to list until it holds one
  const list = dirty ? changesOf(c, sec) : [], n = list.length;
  if (!n && !said) return "";
  const later = list.some((x) => !x.live);
  const rows = list.map((x) => `<li><span class="cname">${esc(x.name)}</span><span class="was">${esc(x.was)}</span>`
    + `<span class="now">${esc(x.now)}</span><span class="when">${x.live ? "" : `<span class="later" role="img" aria-label="Takes a restart" title="Takes a restart">${GL.restart}</span>`}</span></li>`).join("");
  const busy = ui.saving === sec, lit = ui.lastSec === sec || dirtySecs(c).length === 1;
  const acts = n
    ? DRAW.act({ word: busy ? "Saving" : `Save ${n === 1 ? "1 change" : `${n} changes`}`, act: "save", cls: `${lit ? "primary" : ""}${ui.shook === `save:${sec}` ? " shook" : ""}`,
      busy, disabled: !secOk(st, sec) || Boolean(ui.saving), data: ` data-sec="${sec}"` })
      + DRAW.act({ word: "Discard", act: "discard", cls: "quiet", data: ` data-sec="${sec}"` })
    : "";
  const word = !said ? "" : said === "Restarting" ? `<span class="said">${ICON.wait}Restarting</span>`
    : DRAW.result(said, said === "Applied" || said === "Saved" ? "good" : "bad");
  const legend = later ? `<span class="leg">${GL.restart}Takes a restart</span>` : "";
  const fx = ui.fx?.saveRow === sec ? " unfold" : ui.fx?.landed === sec ? " landed" : "";
  return `<div class="secsave${fx}">${n ? `<ul class="changes">${rows}</ul>` : ""}`
    + `<div class="sacts">${acts}${word}${restart}${legend}</div></div>`;
}
// daemon.log lines: "[2026-10-13 15:52:04.118] [claudex] text" (no milliseconds before Oct 13); the log is already this
// command's, so its tag drops
function parseLog(lines) {
  return lines.map((raw) => {
    const m = /^\[(\d{4}-\d\d-\d\d) (\d\d:\d\d:\d\d)(?:\.\d{3})?\]\s*(?:\[([^\]]+)\]\s*)?(.*)$/.exec(raw);
    return m ? { at: m[2], tag: m[3] ?? "", text: m[4], raw } : { at: "", tag: "", text: raw, raw };
  });
}
// splice's own lines, by the words the mock's table gives them; a line no word names stays in the raw log.
// A local runtime is never asked for a list, so its hourly "lists the file it loaded" line is no event.
const EVENTS = [
  [/^models: (\d+) listed at /, (m) => `Model list read · ${m[1]} models`],
  [/^models: .*(HTTP \d{3} from|nothing answers at|no answer within|could not ask|did not answer with)/, () => "Model list not read"],
  [/signed out|not signed in|login required/i, () => "Signed out"],
  [/credentials? (refused|rejected)/i, () => "Credentials refused"],
  [/rate.?limited/i, () => "Rate limited"],
  [/command not found/i, () => "Command not found"],
  [/wrapper (restored|relinked)|restored/i, () => "Restored"],
  [/settings re-read|config(uration)? re-read/i, () => "Settings re-read"],
];
const REQUEST_LINE = /\b(turn|perf|cache|count_tokens|account|activity|re-anchor|mid-stream)\b|upstream \d+\/\d+/i;
const ampm = (hms) => { // 8:02 AM, the way a message's time is written; the raw log keeps its seconds
  const [h, m] = hms.split(":").map(Number);
  return `${h % 12 || 12}:${String(m).padStart(2, "0")} ${h < 12 ? "AM" : "PM"}`;
};
function eventsOf(lines) {
  const folded = new Map();
  for (const l of lines) {
    if (REQUEST_LINE.test(l.text)) continue;
    const hit = EVENTS.map(([re, word]) => [re.exec(l.text), word]).find(([m]) => m);
    if (!hit) continue;
    const text = hit[1](hit[0]);
    const e = folded.get(text) ?? { text, at: l.at, n: 0 };
    e.n += 1; e.at = l.at;
    folded.set(text, e);
  }
  return [...folded.values()].sort((a, b) => b.at.localeCompare(a.at));
}
// The route sends the file it read (HeadOperations.kt:57), and a file it could not read comes back as one reason line,
// "[logs unavailable: <file> unreadable (<why>)]" (LogFileSource.kt:48), which carries no command tag: it is kept and said.
async function readLog(key) {
  const res = await API.get(`/api/logs/${encodeURIComponent(key)}?tail=10000`);
  const parsed = res.ok ? parseLog(res.body?.lines ?? []) : [];
  ui.logs[key] = parsed.filter((l) => l.tag === key);
  ui.logPath[key] = res.body?.path ?? null;
  const why = parsed.find((l) => l.raw.startsWith("[logs unavailable:"))?.raw.slice(1, -1);
  ui.logWhy[key] = why ? why[0].toUpperCase() + why.slice(1) : res.ok ? null : "Log not read";
  render();
}
// ---------- every setting as this command runs it (gaps 459 and 457) ----------
// Each setting with the value this command runs: its own where it holds one, else the shared one, as the daemon folds
// them for a turn (ConfigRoutes.kt). One a command may hold for itself is its control, applied like Settings' rows, with
// the way back to the shared value; Settings' command chips open these same rows. A daemon-wide one shows its value.
// A setting whose name in splice.toml is all it has reads as words here, so no camelCase key reaches the screen.
const settingName = (k) => (k.label && k.label !== k.key ? k.label
  : k.key.replace(/([a-z0-9])([A-Z])/g, "$1 $2").replace(/[_-]+/g, " ").replace(/^./, (x) => x.toUpperCase()));
// WHERE A VALUE COMES FROM IS SAID ONCE PER GROUP, NOT ON EVERY ROW (the old page carried "This command's own" and "Applies
// on restart" on each of 31 rows): a change read only when splice starts wears the restart glyph, named once in its
// group's legend, and a value this command holds for itself carries the way back to the shared one on its own row
// (v0.4.0 section 9, Resolved: source and disposition inline).
function effHtml() {
  return `<div class="everyset">${DRAW.disc({ open: ui.effOpen, act: "eff", controls: "every-settings", inner: "<span>Every setting as this command runs it</span>" })}</div>`;
}
// The jobs Settings draws its settings under, in its order and its words (settings.js TOPICS), so a setting is found
// here under the same name it has there.
const TOPIC_NAME = { silent: "Stalled answers", busy: "Many agents at once", reasoning: "Reasoning", plan: "Plan limits",
  mcp: "MCP servers", projects: "Projects", data: "Your data", version: "Version" };
const BUSY_KEYS = new Set(["maxInflight", "maxQueued"]);
const topicOfKnob = (k) => k?.home || k?.adv || (BUSY_KEYS.has(k?.key) ? "busy" : "other");
// A provider's own plumbing, which no command on another provider reads: Grok's port, model, address and sign-in file
// belong to the provider splice calls xai, ChatGPT's address and sign-in file to codex. Every other command's list
// leaves them out, so a command never reads as running on another provider's settings.
const PLUMBING = { grokPort: "xai", grokModel: "xai", grokAuthPath: "xai", xaiApiBase: "xai", chatgptApiBase: "codex", codexAuthPath: "codex" };
// Retired in 0.4.0: splice decides when to send a failed request again and when to continue a cut answer from what
// happened, so these have nothing to set. A daemon from before 0.4.0 still reports them; the pane draws no row for them.
const RETIRED = new Set(["upstreamRetries", "retryBackoffBaseMs", "retryBackoffCapMs", "retryBackoffJitterPct", "foldReasoningModels",
  "foldMaxContinue", "foldMarkerText", "foldMaxTier"]);
/** Every setting as this command runs it, opened in place under its disclosure: grouped by the job Settings draws it
 *  under, each group under its kicker with its legend, ruled off from the next, one setting to a row so no control
 *  wraps under its own value. A group that holds times draws each as a bar on ONE log axis shared by the whole list,
 *  with the axis once under the group, so 20 s and 30 min read as lengths before their figures do. */
function everySetHtml(c, n = 1) {
  const K = KNOBS, wire = K.st.cfg?.knobs;
  if (!wire) return DRAW.plate(DRAW.result("Not read", "bad"));
  const keys = [...K.LIST.map((k) => k.key), ...K.others().map((k) => k.key)]
    .filter((key, i, all) => wire[key] && !RETIRED.has(key) && all.indexOf(key) === i && (!PLUMBING[key] || PLUMBING[key] === c.provKey));
  const own = (key) => K.ownValue(key, c.key) !== undefined;
  const valueOfKey = (key) => (own(key) ? K.ownValue(key, c.key) : K.valueOf(K.KNOB[key]).v);
  const timeOf = (key) => { const k = K.KNOB[key], v = Number(valueOfKey(key)); return k?.c?.k === "dur" && v > 0 ? v : null; };
  const times = keys.map(timeOf).filter((v) => v != null);
  const ax0 = times.length ? DRAW.axis(times, times) : null;
  // the shared axis spaces its labels for a wide track; this one is a single column of a group, so only the labels that
  // fit it stay: the first and the last (the axis always ends on its longest time) always, and none within a few percent
  // of another
  const fitTicks = (a, perChar = 3.3, gap = 3) => {
    const w = (v) => DRAW.tickWord(v).length * perChar, kept = [];
    a.ticks.forEach((v, i, all) => {
      if (i === 0) { kept.push({ v, r: w(v) }); return; }
      if (i === all.length - 1) { const l = 100 - w(v); while (kept.length > 1 && l - kept.at(-1).r < gap) kept.pop(); kept.push({ v, r: 100 }); return; }
      if (a.at(v) - w(v) / 2 - kept.at(-1).r >= gap) kept.push({ v, r: a.at(v) + w(v) / 2 });
    });
    return kept.map((k) => k.v);
  };
  const ax = ax0 && { ...ax0, ticks: fitTicks(ax0) };
  const row = (key) => {
    const k = K.KNOB[key], id = `${key}@${c.key}`, mine = own(key);
    // the marks the foundation gives a value a reader could not guess at (takes a restart); their words are said once
    // in the group's legend, never on every row
    const marks = K.needsRestart(key) ? `<span class="marks">${K.markHtml("restart")}</span>` : "";
    // a value with no control of its own, and one the daemon sends empty, is still read: the word, or a dash, never
    // a hole in the column
    const said = `<span class="fixed">${esc(K.word(k, K.valueOf(k).v)) || DASH}</span>`;
    const editable = wire[key].per_command && wire[key].editable !== false
      ? `${K.ctlHtml(k, mine ? K.ownValue(key, c.key) : K.valueOf(k).v, c.key)}${K.saidHtml(id, { rest: false })}${mine ? K.backHtml(k, c.key) : ""}`
      : "";
    const ctl = editable.trim() ? editable : said;
    const t = timeOf(key);
    // a text field with no time of its own takes the time column too, so an address or a path is read whole
    const wide = t == null && editable.trim() !== "" && k?.c?.k === "text";
    return `<div class="lbl" data-row="${key}">${esc(settingName(k))}</div><div class="ctl${wide ? " span" : ""}"><div class="line">${ctl}${marks}</div></div>`
      + `${wide ? "" : `<span class="etime">${t != null ? DRAW.range(ax, t, t) : ""}</span>`}`;
  };
  const groups = new Map();
  for (const key of keys) {
    const t = topicOfKnob(K.KNOB[key]);
    if (!groups.has(t)) groups.set(t, []);
    groups.get(t).push(key);
  }
  const order = [...Object.keys(TOPIC_NAME), "other"].filter((t) => groups.has(t));
  const blocks = order.map((t) => {
    const list = groups.get(t), timed = list.some((key) => timeOf(key) != null);
    const axis = timed ? `<span></span><span></span><div class="eaxis">${DRAW.axisRow(ax, "")}</div>` : "";
    // its height in rows: the head, each setting, the axis when it draws one
    return { w: 2 + list.length + (timed ? 2 : 0), html: `<div class="egroup"><div class="sechead sub"><h4 class="kicker">${esc(TOPIC_NAME[t] ?? "More")}</h4>`
      + `<span class="pd-count"><b>${DRAW.n(list.length)}</b></span></div>`
      + `<div class="effgrid${timed ? " timed" : ""}">${list.map(row).join("")}${axis}</div></div>` };
  });
  // the groups go down as many plates as the sections above stand in, each into the shortest so far, so the plates line
  // up under the ones above and end near one height
  const plates = DRAW.packCols(blocks, n, (b) => b.w).map((col) => (col.items.length
    ? DRAW.plate(`<div class="kn everycol">${col.items.map((b) => b.html).join("")}</div>`, "pcol") : ""));
  const head = `<div class="everyhead"><p class="kicker on-wall">Every setting as this command runs it</p>`
    // THE MARKS ARE SAID ONCE, FOR THE WHOLE VIEW (Oct 28): they were said on every group's kicker ("Takes a restart" on eight
    // of them), so the one legend of every mark any group draws stands here, over the groups
    + `<span class="pd-count"><b>${DRAW.n(keys.length)}</b></span>${K.legendHtml(keys, { one: false })}${DRAW.act({ word: "Hide", act: "eff", cls: "quiet small" })}</div>`;
  return `${head}<div class="ecols" style="--n:${n}">${plates.join("")}</div>`;
}

// ---------- a command's own fields, edited in place (gaps 461/462; marlin's four groups) ----------
// Where it goes and Running write the command's own table; Sign-in, Runs on this computer and the quirks write its provider's,
// which every command on the provider shares, so those rows name the others (sharedHtml). Prices write the command's own
// card per model, [heads.KEY.rates], which wins over the provider's row. Every value reaches the daemon in the one Save.
const MASKED = "********"; // GET /api/topology serves each extra_headers value as this; sent back, it keeps the stored one
const vocab = () => data.topo?.vocab ?? {};
const cmdDialect = (c) => data.heads.find((x) => x.key === c.key)?.dialect ?? "";
const numText = (n) => (n == null ? "" : String(n));
function fieldsOf(c) {
  const t = topology(), h = t.heads?.[c.key] ?? {}, p = t.providers?.[c.provKey] ?? {};
  return {
    port: numText(h.port), prefix: h.discovery_prefix ?? "", kind: p.auth?.kind ?? "", env: p.auth?.env ?? "",
    headers: Object.entries(p.extra_headers ?? {}).map(([name, value]) => ({ name, value })),
    local: p.local ?? null, isolate: [...(h.claude?.isolate ?? [])],
    rates: Object.fromEntries(c.models.map((m) => [m.id, { i: numText(m.rates?.input), o: numText(m.rates?.output), c: null }])),
    quirks: { ...(p.quirks ?? {}) },
  };
}
// what a local provider is when splice.toml does not say: a provider /api/models/local answers for is local
const autoLocal = (c) => (data.local ?? []).some((x) => x.provider === c.provKey);
// a press that matches what splice already decides on its own leaves the line out of splice.toml
const localPick = (c, yes) => (fieldsOf(c).local === null && yes === autoLocal(c) ? null : yes);
const portOk = (v) => /^\d+$/.test(v) && +v >= 1 && +v <= 65535;
const priceOk = (v) => v.trim() !== "" && Number.isFinite(Number(v)) && Number(v) >= 0;
const HEADER_NAME = /^[!#$%&'*+.^_`|~0-9A-Za-z-]+$/;
// the quirks this command's dialect reads, each with its kind and words, as GET /api/topology serves them
const quirksOf = (c) => (vocab().quirks ?? []).filter((q) => q.dialects.includes(cmdDialect(c)));
function quirkOk(q, v) {
  if (v === undefined) return true;
  if (q.kind === "int") return Number.isInteger(v) && v >= 0;
  if (q.words) return q.words.includes(v);
  return true;
}
function ownState(c, d) {
  const was = fieldsOf(c);
  const names = d.headers.map((x) => x.name.trim());
  const header = d.headers.map((x, i) => (x.fresh && !names[i] && !x.value) || (HEADER_NAME.test(names[i]) && names.indexOf(names[i]) === i && x.value !== ""));
  const rate = Object.fromEntries(Object.entries(d.rates).map(([id, x]) => {
    const w = was.rates[id], moved = x.i !== w?.i || x.o !== w?.o || x.c !== w?.c;
    return [id, { i: !moved || priceOk(x.i), o: !moved || priceOk(x.o), c: !moved || !cacheShown(c, id, x) || priceOk(x.c ?? x.i) }];
  }));
  const quirk = Object.fromEntries(quirksOf(c).map((q) => [q.name, quirkOk(q, d.quirks[q.name])]));
  const s = {
    port: portOk(d.port) || d.port === was.port, prefix: d.prefix.trim() !== "",
    env: d.env === "" || /^[A-Za-z_][A-Za-z0-9_]*$/.test(d.env), header, rate, quirk,
  };
  s.ok = s.port && s.prefix && s.env && header.every(Boolean) && Object.values(rate).every((x) => x.i && x.o) && Object.values(quirk).every(Boolean);
  return s;
}
const bad = (ok, word) => (ok ? "" : `<span class="said limit">${word}</span>`);
// the names of what a command can share with your own Claude Code, by the item splice.toml spells (claude.share, isolate)
const SHARE_NAME = { settings: "Settings", mcps: "MCP servers", skills: "Skills", hooks: "Hooks", agents: "Agents", commands: "Commands",
  plugins: "Plugins", claude_md: "CLAUDE.md", sessions: "Sessions", projects: "Transcripts" };
const shareName = (x) => SHARE_NAME[x] ?? x;
const kindName = (k) => (vocab().auth_kinds ?? []).find((x) => x.kind === k)?.label ?? k;
// a model priced nowhere has no cache price either: once its row is priced, Cache read shows beside it, filled with the In
// price splice charges a cache read at until a real one is typed (marlin, Oct 13: overcount, but never hide it)
const unpriced = (c, id) => !c.models.find((m) => m.id === id)?.rates && !topology().heads?.[c.key]?.rates?.[id];
const cacheShown = (c, id, x) => unpriced(c, id) && Boolean(x.i || x.o || x.c);
/** RUNNING: whether the runtime is on this computer, what this command shares with his own Claude Code, and the
 *  quirks of the wire it speaks. A share is a chip, solid where it is shared and hollow where the command keeps its
 *  own, with the count said once; a quirk splice.toml sets shows with its control and the way back, and the ones
 *  that follow the provider wait behind one disclosure, their switches unlit. Rows are grouped by what splice.toml
 *  holds, so a row being changed stays where it is until its Save lands. */
function runningSection(c, d, st) {
  const s = st.own, was = fieldsOf(c).quirks;
  const local = cmdDialect(c) === "openai-chat" ? (() => {
    const on = d.local ?? autoLocal(c);
    return `<span class="lbl${dirt(d.local !== fieldsOf(c).local)}">Runs on this computer</span><div class="ctl">`
      + DRAW.switcher([["yes", "Yes"], ["no", "No"]], on ? "yes" : "no", "local", "Runs on this computer")
      + `${sharedHtml(c)}</div>`;
  })() : "";
  const items = vocab().share ?? [];
  const shares = items.filter((x) => !d.isolate.includes(x)).length;
  const share = items.length
    ? `<div class="sechead sub"><h4 class="kicker">Shares with Claude Code</h4><span class="pd-count"><b>${DRAW.n(shares)}</b> of ${DRAW.n(items.length)}</span></div>`
      + `<div class="chips tog" role="group" aria-label="Shares with Claude Code">${items.map((x) => `<button class="chip tg" type="button" data-act="share" data-v="${esc(x)}" aria-pressed="${!d.isolate.includes(x)}">${esc(shareName(x))}</button>`).join("")}</div>`
    : "";
  const moved = (q) => JSON.stringify(was[q.name]) !== JSON.stringify(d.quirks[q.name]);
  // the way back to the provider's own value is the back glyph at the row's end, named once on the kicker's line
  const back = (q) => (d.quirks[q.name] === undefined || q.kind === "table" ? ""
    : `<button class="icon qback" type="button" data-act="qback" data-q="${esc(q.name)}" title="Back to the provider's own" aria-label="${esc(quirkName(q.name))}: back to the provider's own">${GL.back}</button>`);
  const qrow = (q) => `<span class="lbl" title="${esc(q.name)}">${esc(quirkName(q.name))}</span><div class="ctl">`
    + `${quirkCtl(q, d.quirks[q.name], moved(q))}`
    + `${QUIRK_UNIT[q.name] ? `<span class="unit">${QUIRK_UNIT[q.name]}</span>` : ""}${bad(s.quirk[q.name], "Not a value")}${back(q)}</div>`;
  const qs = quirksOf(c);
  const set = qs.filter((q) => was[q.name] !== undefined), rest = qs.filter((q) => was[q.name] === undefined);
  const mine = set.length ? `<div class="form">${set.map(qrow).join("")}</div>` : "";
  const theirs = rest.length
    ? `<div class="qrest">${DRAW.disc({ open: ui.qOpen, act: "qopen", inner: `<span><b>${DRAW.n(rest.length)}</b> follow the provider</span>` })}`
      + `${ui.qOpen ? `<div class="form${ui.fx?.q ? " unfold" : ""}">${rest.map(qrow).join("")}</div>` : ""}</div>`
    : "";
  const backs = qs.some((q) => back(q));
  const quirks = qs.length
    ? `<div class="sechead sub"><h4 class="kicker">${esc(DIALECT_NAME[cmdDialect(c)] ?? cmdDialect(c) ?? "")} quirks</h4>`
      + `${set.length ? `<span class="pd-count"><b>${DRAW.n(set.length)}</b> set here</span>` : ""}`
      + `${backs ? `<span class="leg qleg">${GL.back}Provider's own</span>` : ""}</div>${mine}${theirs}`
    : "";
  // the wire it speaks is named once: on its quirks' kicker where it has quirks, else beside Running
  return DRAW.section("Running", `${local ? `<div class="form">${local}</div>` : ""}${share}${quirks}${secSave(c, "running", st)}`, { aside: qs.length ? "" : dialectHtml(c) });
}
const dirt = (on) => (on ? " dirty" : "");
// fin's name for each quirk, from its doc comment in QuirksConfig.kt; a key with none reads as itself
const QUIRK_NAME = {
  store: "Provider keeps responses", account_id_header: "Send account ID", cache_key: "Prompt cache key",
  summary_field: "Ask for reasoning summaries", tool_choice: "Send tool choice", reasoning_cache: "Keep reasoning between tool calls",
  parallel_tool_calls: "Parallel tool calls", websocket: "Use WebSocket", summary_delivery: "How summaries stream",
  tool_surface: "Load tools on demand", code_mode: "Code mode", code_mode_workers: "Code mode workers",
  code_mode_timeout_ms: "Code mode step timeout", code_mode_heap: "Memory per worker", code_mode_memory_mb: "Code mode memory in all",
  code_mode_models: "Code mode models", reasoning_effort: "Send reasoning effort", chat_effort_vocabulary: "Provider's effort words",
  stream_usage: "Ask for token usage", slot_affinity: "One slot per conversation", mfjs: "Kimi tool schemas",
  block_allowlist: "Block types allowed", strip_cache_control: "Remove cache markers", synthesize_signatures: "Sign thinking blocks",
  map_thinking_adaptive: "Map thinking to Kimi's effort", strip_sampling_params: "Remove sampling settings",
  reanchor_prefill: "Resume a broken stream", tool_name_cap: "Longest tool name", zstd_request_body: "Compress requests",
};
const QUIRK_UNIT = { code_mode_timeout_ms: "ms", code_mode_heap: "MB", code_mode_memory_mb: "MB" };
const quirkName = (k) => QUIRK_NAME[k] ?? k;
const quirkWord = (w) => String(w).replace(/[_-]+/g, " ").replace(/^./, (x) => x.toUpperCase());
// a value inside a quirk's table, nested or not, read as words: an entry's key and its value, a list as its items
const valueWords = (x) => (Array.isArray(x) ? x.map(valueWords).join(", ")
  : x && typeof x === "object" ? Object.entries(x).map(([k, y]) => `${quirkWord(k)} ${valueWords(y)}`).join(" · ")
    : String(x));
// A switch carries only the values splice.toml can hold: a quirk left at the provider's own lights none of them, and
// the way back to it is the back glyph beside a value set here, as a setting's own value carries its way back on
// Settings. An empty field is a dash: the group it stands in already says whose value it follows.
function quirkCtl(q, v, moved) {
  const opt = (val, word) => `<button type="button" data-act="quirk" data-q="${esc(q.name)}" data-v="${esc(JSON.stringify(val))}" aria-pressed="${JSON.stringify(v) === JSON.stringify(val)}">${esc(word)}</button>`;
  const sw = (opts) => `<div class="switch${v === undefined ? " unlit" : ""}${dirt(moved)}" role="group" aria-label="${esc(quirkName(q.name))}">${opts}</div>`;
  if (q.kind === "bool") return sw(opt(true, "On") + opt(false, "Off"));
  if (q.words) return sw(q.words.map((w) => opt(w, quirkWord(w))).join(""));
  // a list reads as its items, a chip each, by the names he knows them by (a model by its catalog name); Edit opens the
  // field the list is typed in, and the field stays open while it holds a change or a value that is not one
  if (q.kind === "list" && ui.qedit !== q.name && !moved && Array.isArray(v) && v.length) {
    const word = (x) => (cmdByKey(ui.open)?.models.find((m) => m.id === x)?.label ?? nameOf({ id: x }));
    return `<span class="chips qitems">${v.map((x) => DRAW.chip({ cls: "flat", text: word(x), title: x })).join("")}</span>`
      + DRAW.act({ word: "Edit", act: "qedit", cls: "quiet small", data: ` data-q="${esc(q.name)}"` });
  }
  if (q.kind === "list") return `<input class="field mono qlist${dirt(moved)}" id="q-${esc(q.name)}" data-ql="${esc(q.name)}" value="${esc((v ?? []).join(", "))}" placeholder="—" spellcheck="false" autocomplete="off" aria-label="${esc(quirkName(q.name))}">`;
  if (q.kind === "table") {
    if (v === undefined) return DRAW.nothing("Provider's own");
    // a table is what one word becomes on the wire: each entry one chip, the word Claude Code sends, the mapping
    // glyph, and the word the provider is sent, a nested table its own row under its name
    const pair = (k, x) => `<span class="chip flat qpair"><span class="qk">${esc(quirkWord(k))}</span>${GL.maps}<span class="qv">${esc(valueWords(x))}</span></span>`;
    const entries = v && typeof v === "object" && !Array.isArray(v) ? Object.entries(v) : [["", v]];
    const nested = (x) => x && typeof x === "object" && !Array.isArray(x);
    const flat = entries.filter(([, x]) => !nested(x)), deep = entries.filter(([, x]) => nested(x));
    return `<div class="qtable">${flat.length ? `<span class="chips">${flat.map(([k, x]) => (k ? pair(k, x) : DRAW.chip({ cls: "flat", text: valueWords(x) }))).join("")}</span>` : ""}`
      + deep.map(([k, x]) => `<div class="qgroup"><span class="qgk">${esc(quirkWord(k))}</span><span class="chips">${Object.entries(x).map(([k2, y]) => pair(k2, y)).join("")}</span></div>`).join("")
      + `</div>`;
  }
  return `<input class="field mono${q.kind === "int" ? " num" : ""}${dirt(moved)}" id="q-${esc(q.name)}" data-q="${esc(q.name)}" data-qk="${q.kind}" value="${esc(v ?? "")}" placeholder="—" spellcheck="false" autocomplete="off" aria-label="${esc(quirkName(q.name))}">`;
}
function ownInput(t, d) {
  if (t.id === "h-port") d.port = t.value;
  else if (t.id === "h-prefix") d.prefix = t.value;
  else if (t.id === "h-env") d.env = t.value.trim();
  else if (t.dataset.hn) d.headers[+t.dataset.hn].name = t.value;
  else if (t.dataset.hv) { const x = d.headers[+t.dataset.hv]; x.value = t.value || (x.fresh ? "" : MASKED); }
  else if (t.dataset.rate) d.rates[t.dataset.rate][t.dataset.rk] = t.dataset.rk === "c" && !t.value.trim() ? null : t.value.trim();
  else if (t.dataset.ql) { const v = t.value.split(",").map((x) => x.trim()).filter(Boolean); d.quirks[t.dataset.ql] = v.length ? v : undefined; }
  else if (t.dataset.q) {
    const v = t.value.trim();
    d.quirks[t.dataset.q] = v === "" ? undefined : t.dataset.qk === "int" ? (/^\d+$/.test(v) ? Number(v) : v) : v;
  } else return false;
  return true;
}
// each change in the draft as the Save row lists it: what it is, the value it had, struck, and the value it takes
const clip = (v) => { const x = String(v ?? ""); return x.length > 40 ? `${x.slice(0, 39)}…` : x; };
function changesOf(c, only = null) {
  const a = draftOf(c), d = ui.draft, out = [];
  // each change is listed in the section whose Save writes it
  let sec = "";
  // a context window is the one field the running daemon re-reads (TopologyRoutes, V4-162); every other field here is
  // read when splice starts, so its change wears the restart glyph before it is saved (spec 9)
  const add = (name, was, now, live = false) => {
    if ((!only || only === sec) && JSON.stringify(was) !== JSON.stringify(now)) out.push({ name, was: clip(was), now: clip(now), live, sec });
  };
  const label = (id) => c.models.find((m) => m.id === id)?.label ?? c.missing.find((m) => m.id === id)?.label ?? id ?? "None";
  sec = "where";
  add("Sends to", a.url, d.url.trim());
  add("Port", a.port, d.port);
  add("Model prefix", a.prefix, d.prefix.trim());
  sec = "signin";
  add("Signs in with", kindName(a.kind), kindName(d.kind));
  add("Key variable", a.env || "Default", d.env || "Default");
  for (const x of a.headers) if (!d.headers.some((y) => y.name === x.name)) add(x.name, "Set", "Removed");
  for (const y of d.headers) {
    if (y.fresh) { if (y.name.trim()) add(y.name.trim(), "None", "Set"); continue; }
    if (y.value !== MASKED) add(y.name, "Set", "New value");
  }
  sec = "models";
  for (const t of TIERS) {
    add(`${tierName(t)} model`, label(a.tiers[t].model), label(d.tiers[t].model));
    if (d.tiers[t].model && a.tiers[t].model === d.tiers[t].model) add(`${tierName(t)} window`, a.tiers[t].win, d.tiers[t].win, true);
  }
  add("Window", a.headWin, d.headWin, true);
  sec = "prompt";
  add("System prompt", { append: "Append", replace: "Replace" }[a.mode] ?? a.mode, { append: "Append", replace: "Replace" }[d.mode] ?? d.mode);
  add("System prompt text", a.file || a.text, d.file || d.text);
  sec = "projects";
  // a project's layer reads as what it does and the start of what it says: "Append: Use the house style", or No layer
  const layer = (l) => {
    if (!l) return "No layer";
    const said = l.file || String(l.text ?? "").trim();
    return `${{ append: "Append", replace: "Replace", strip: "Strip" }[l.mode] ?? l.mode}${said ? `: ${said}` : ""}`;
  };
  for (const root of new Set([...Object.keys(a.per), ...Object.keys(d.per)])) add(`Prompt in ${nameOfRoot(root)}`, layer(a.per[root]), layer(d.per[root]));
  sec = "running";
  const yn = (v) => (v ? "Yes" : "No");
  add("Runs on this computer", yn(a.local ?? autoLocal(c)), yn(d.local ?? autoLocal(c)));
  for (const x of vocab().share ?? []) add(`Shares ${shareName(x)}`, yn(!a.isolate.includes(x)), yn(!d.isolate.includes(x)));
  const qv = (v) => (v === undefined ? "Provider's own" : valueWords(v));
  for (const q of quirksOf(c)) add(quirkName(q.name), qv(a.quirks[q.name]), qv(d.quirks[q.name]));
  sec = "prices";
  for (const m of c.models) {
    add(`${m.label} in`, a.rates[m.id].i || "No price", d.rates[m.id].i || "No price");
    add(`${m.label} out`, a.rates[m.id].o || "No price", d.rates[m.id].o || "No price");
    if (cacheShown(c, m.id, d.rates[m.id])) add(`${m.label} cached`, "No price", d.rates[m.id].c ?? d.rates[m.id].i);
  }
  return out;
}
// a model's own price card: the card it has, else the one it is priced at now, whole, with the cell's new number in it
function cardOf(h, m, i, o, cache) {
  const was = h.rates?.[m.id], r = m.rates;
  const lc = r?.long_context;
  const card = was ? { ...was } : r ? {
    input: r.input, cache_read: r.cache_read, output: r.output,
    ...(r.cache_write != null ? { cache_write: r.cache_write } : {}), ...(r.cache_write_1h != null ? { cache_write_1h: r.cache_write_1h } : {}),
    ...(lc ? { long_context_over_input_tokens: lc.over_input_tokens, long_context_input: lc.input, long_context_cache_read: lc.cache_read, long_context_output: lc.output,
      ...(lc.cache_write != null ? { long_context_cache_write: lc.cache_write } : {}), ...(lc.cache_write_1h != null ? { long_context_cache_write_1h: lc.cache_write_1h } : {}) } : {}),
  } : {};
  card.input = Number(i); card.output = Number(o);
  // a model with no price has no cache price either: a cache read is priced as an input token until one is set
  card.cache_read ??= cache != null && cache !== "" ? Number(cache) : card.input;
  return card;
}
function applyOwn(t, c, d, has) {
  const a = fieldsOf(c), head = t.heads[c.key], prov = t.providers[c.provKey];
  if (has("where")) {
    if (d.port !== a.port && portOk(d.port)) head.port = Number(d.port);
    if (d.prefix.trim() !== a.prefix) head.discovery_prefix = d.prefix.trim();
  }
  if (has("running") && JSON.stringify(d.isolate) !== JSON.stringify(a.isolate)) {
    head.claude ??= {};
    if (d.isolate.length) head.claude.isolate = [...d.isolate]; else delete head.claude.isolate;
    if (!Object.keys(head.claude).length) delete head.claude;
  }
  if (has("prices")) {
    for (const m of c.models) {
      const x = d.rates[m.id], w = a.rates[m.id];
      if (x.i === w.i && x.o === w.o && x.c === w.c) continue;
      head.rates ??= {};
      head.rates[m.id] = cardOf(head, m, x.i, x.o, x.c);
    }
  }
  if (!prov) return;
  if (has("signin")) {
    if (d.kind !== a.kind || d.env !== a.env) {
      prov.auth = { ...prov.auth, kind: d.kind };
      if (d.env && d.kind === "api-key") prov.auth.env = d.env; else delete prov.auth.env;
    }
    if (JSON.stringify(d.headers) !== JSON.stringify(a.headers)) {
      const kept = d.headers.filter((x) => x.name.trim());
      if (kept.length) prov.extra_headers = Object.fromEntries(kept.map((x) => [x.name.trim(), x.value])); else delete prov.extra_headers;
    }
  }
  if (has("running")) {
    if (d.local !== a.local) { if (d.local === null) delete prov.local; else prov.local = d.local; }
    if (JSON.stringify(d.quirks) !== JSON.stringify(a.quirks)) {
      const q = Object.fromEntries(Object.entries(d.quirks).filter(([, v]) => v !== undefined));
      if (Object.keys(q).length) prov.quirks = q; else delete prov.quirks;
    }
  }
}

// ---------- Save: one PUT of the topology, the way Settings writes it, carrying one section's changes ----------
/** The topology with the draft's changes in the sections named, and nothing else of the draft: a section's Save writes
 *  that section, and a change waiting in another section stays in the draft, unsent. */
function applyDraft(tree, c, d, secs) {
  const has = (s) => secs.has(s);
  const t = JSON.parse(JSON.stringify(tree));
  const head = t.heads[c.key], prov = t.providers?.[c.provKey];
  if (has("models")) {
    // tiers: the head's models carry their slot; a head with no allowlist uses model_slots
    if (head.models) {
      for (const m of head.models) delete m.slot;
      for (const tier of TIERS) {
        const id = d.tiers[tier].model;
        if (!id) continue;
        const row = head.models.find((m) => m.id === id);
        if (row) row.slot = tier; else head.models.push({ id, slot: tier });
      }
    } else {
      head.model_slots = Object.fromEntries(TIERS.filter((tier) => d.tiers[tier].model).map((tier) => [tier, d.tiers[tier].model]));
      if (!Object.keys(head.model_slots).length) delete head.model_slots;
    }
    // windows: the provider's own row for that model, which every command on the provider shares. A window is written
    // only when its field reads other than the model's window does now: the field shows 131072 as 128K, which would
    // come back as 128000 and shrink a window nobody touched.
    for (const tier of TIERS) {
      const sel = d.tiers[tier], w = parseWindow(sel.win);
      const row = prov?.models?.find((m) => m.id === sel.model);
      const now = c.models.find((m) => m.id === sel.model)?.window ?? null;
      if (row && w && sel.win !== big(now)) row.context_window = w;
    }
    // no tier declared: the one window is the head's own, which the head's models all follow
    if (!TIERS.some((tier) => d.tiers[tier].model)) {
      const w = parseWindow(d.headWin);
      if (w && d.headWin !== draftOf(c).headWin) head.context_window = w;
    }
  }
  // the provider's address, which every command on the provider shares too (spec 9d)
  if (has("where") && prov && d.url !== c.url && urlOk(d.url)) prov.base_url = d.url.trim();
  applyOwn(t, c, d, has);
  const set = (o, p) => {
    delete o.system_prompt; delete o.system_prompt_file; delete o.system_prompt_mode;
    if (p.file) o.system_prompt_file = p.file; else if (p.text) o.system_prompt = p.text;
    if ((p.file || p.text || p.mode !== "append") && p.mode) o.system_prompt_mode = p.mode;
  };
  // the head's prompt
  if (has("prompt")) set(head, d);
  // the project layers for this command
  if (has("projects")) {
    t.projects ??= {};
    for (const root of Object.keys(t.projects)) { if (t.projects[root]?.heads?.[c.key]) delete t.projects[root].heads[c.key]; }
    for (const [root, l] of Object.entries(d.per)) {
      t.projects[root] ??= {}; t.projects[root].heads ??= {};
      t.projects[root].heads[c.key] = {}; set(t.projects[root].heads[c.key], l);
    }
    for (const root of Object.keys(t.projects)) {
      const p = t.projects[root];
      if (p.heads && !Object.keys(p.heads).length) delete p.heads;
      if (!Object.keys(p).length) delete t.projects[root];
    }
  }
  return t;
}
/** The draft rebuilt from what splice.toml holds now, with the edits still waiting in the sections named carried over,
 *  so a Save or a restart in one section never throws away what was typed in another. */
function rebase(key, kept) {
  ui.draft = null;
  const c = cmdByKey(key);
  if (!c || !Object.keys(kept).length) return;
  takeDraft(c);
  Object.assign(ui.draft, structuredClone(kept));
}
const waiting = (c, but) => Object.fromEntries(dirtySecs(c).filter((s) => s !== but).flatMap((s) => SECTION_FIELDS[s].map((f) => [f, ui.draft[f]])));
async function save(sec) {
  const c = cmdByKey(ui.open), d = ui.draft;
  if (!c || !d || !SECTION_FIELDS[sec] || ui.saving) return;
  ui.saving = sec; ui.said = null; ui.shook = null; render();
  const next = applyDraft(topology(), c, d, new Set([sec]));
  const res = await API.put("/api/topology", { topology: next });
  ui.saving = null;
  if (!res.ok || !res.body?.ok) {
    // the daemon's sentences are for the log; the page says a config finding in fin's word for it, never the sentence
    const found = res.body?.findings ?? [];
    if (found.length) console.warn(`models: saving ${c.key} refused: ${found.map((x) => x.message).join("; ")}`);
    ui.said = { sec, word: found.length ? "Config error" : "Not saved" };
    ui.shook = `save:${sec}`;
    render();
    return;
  }
  const kept = waiting(c, sec);
  if (res.body.restart_required) { ui.pending = true; ui.pendingAt = { key: c.key, sec }; }
  ui.said = { sec, word: res.body.restart_required ? "Saved" : "Applied" };
  ui.fx = { landed: sec };
  ui.draft = null;
  await readAll();
  rebase(c.key, kept);
  render();
}
/** A section's changes back to what splice.toml holds, the other sections' edits kept. */
function discard(sec) {
  const c = cmdByKey(ui.open);
  if (!c || !ui.draft || !SECTION_FIELDS[sec]) return;
  const a = draftOf(c);
  for (const f of SECTION_FIELDS[sec]) ui.draft[f] = structuredClone(a[f]);
  if (ui.said?.sec === sec) ui.said = null;
  if (ui.shook === `save:${sec}`) ui.shook = null;
}
// an edit takes back what the last Save said, never the word of a restart under way
const edited = () => { if (ui.said?.word !== "Restarting") ui.said = null; };
/** Restart splice, or, armed, the sessions it would cut with the act and the way out (kit.js's midRequest); while
 *  splice restarts, the wait ring in its place, so it is never pressed twice. */
const restartBtn = () => (ui.said?.word === "Restarting" ? `<span class="said">${ICON.wait}Restarting</span>`
  : ui.cut ? askHtml("Mid-request", ui.cut, DRAW.act({ word: "Restart splice", act: "restart-now", cls: "danger" }),
    DRAW.act({ word: "Keep running", act: "keep-running", cls: "quiet" }), { bad: true })
    : DRAW.act({ word: "Restart splice", act: "restart-splice" }));
async function pressRestart() {
  const cut = await midRequest(data.heads.map((h) => h.key));
  if (!cut.length) { restartSplice(); return; }
  ui.cut = cut; render();
}
async function restartSplice() {
  const key = ui.open, sec = ui.pendingAt?.key === key ? ui.pendingAt.sec : ui.said?.sec ?? null;
  const c = key ? cmdByKey(key) : null, kept = c && ui.draft ? waiting(c, null) : {};
  ui.cut = null; ui.said = { sec, word: "Restarting" }; render();
  const res = await API.post("/api/daemon/restart");
  if (!res.ok) { ui.said = { sec, word: "Not restarted" }; render(); return; }
  for (let i = 0; i < 90; i++) {
    await new Promise((r) => setTimeout(r, 1000));
    const up = await API.get("/api/heads");
    if (!up.ok) continue;
    ui.pending = false; ui.pendingAt = null; ui.restartDue = false; ui.gone.clear(); ui.said = { sec, word: "Applied" }; ui.draft = null;
    await readAll();
    if (key) rebase(key, kept);
    // an add that was saved while splice did not come back opens its command's pane once it is listed
    const added = ui.add?.saved && commandsOf().find((x) => x.cmd === ui.add.savedCmd);
    if (added) { ui.add = null; ui.open = added.key; ui.said = null; }
    render(); return;
  }
  ui.said = { sec, word: "Not restarted" }; render();
}

// a declared model the provider does not serve, in the route's own words (ModelsRoute.unresolvedReason)
const notServed = (m) => `<span class="said limit">Not served${m.reason ? `: ${esc(m.reason)}` : ""}</span>`;

// ---------- Every model: each command's models, their windows and their prices, on the board's own two scales ----------
// WHAT IS DRAWN IS COMPARED BY EYE ACROSS THE BOARD. Each provider is a titled group of its commands' cards, the groups
// packed into as many columns as the board's width holds, and each model a row of two drawings: its window as a bar on
// ONE linear scale the whole board shares, with a lighter run on to the most it takes and a notch where its price rises;
// its price as a track on ONE log scale the board shares, a band to In and a line on to Out. Each card heads its
// columns once, with the scales' ends over the tracks, and the marks are named once on the legend line. A figure the
// route does not send is a dash, never a 0, and a model with no price keeps its row's shape with a hatched track.
const EVERY_SHOWN = 6;      // a card's first models at full size; the rest twenty at a time behind one pill
const EVERY_STEP = 20;
const EVERY_FIND = 24;      // a search shows every match up to this many rows on a card before it folds them
const EVERY_COL_REM = 56;   // a board column: a model's name, its window and its price side by side at full size
const PRICE_STOPS = [0.01, 0.1, 1, 10, 100, 1000];
// a price per 1M tokens as a rate card writes it: cents, and two significant digits under a dime ("$0.075")
const per1m = (x) => (x == null ? DASH : x === 0 ? "$0" : x < 0.1 ? `$${+x.toPrecision(2)}` : usd(x));
// a scale's end as its tick says it: "1¢", "10¢", "$1", "$100"
const priceTick = (v) => (v < 1 ? `${Math.round(v * 100)}¢` : `$${v}`);
const rateOf = (r, k) => r?.[k] ?? (k === "input" ? r?.i : k === "output" ? r?.o : null) ?? null;
// the model each tier sends to: the tiers it declares, or with none declared, the ones its client plants by position
function tierMap(c) {
  const declared = TIERS.some((t) => c.slots[t]);
  return Object.fromEntries(TIERS.map((t) => [t, (declared ? c.slots[t] : c.defaults[t]) ?? null]));
}
function tagWords(id, tiers) {
  const mine = TIERS.filter((t) => tiers[t] === id);
  return !mine.length ? [] : mine.length === TIERS.length ? ["Every tier"] : mine.map(tierName);
}
function tierTags(id, tiers) {
  const words = tagWords(id, tiers);
  return words.length ? `<span class="tags">${words.map((w) => `<i class="tag">${esc(w)}</i>`).join("")}</span>` : "";
}
// A row's height in rows, as its name and tier tags will wrap in the name column (one line holds about LINE_CHARS
// characters of the name face, a tag about its letters and a little more): a second line takes 0.4 of a row. It only
// packs the board's columns by what each group will take; the drawing itself wraps by its own rules.
const LINE_CHARS = 36;
function rowUnits(label, words) {
  const tags = words.reduce((a, w) => a + w.length * 0.9 + 2.5, 0), long = label.length > LINE_CHARS;
  const lines = Math.ceil(label.length / LINE_CHARS) + (words.length && (long || label.length + 1 + tags > LINE_CHARS) ? 1 : 0);
  return 1 + 0.4 * (lines - 1);
}
// the maker a router's roster names before a colon ("Maker: Model"), and the model's own name after it
const makerSplit = (label) => { const m = /^([^:]{1,32}):\s+(.+)$/.exec(label ?? ""); return m ? { maker: m[1], name: m[2] } : null; };
// the words searched for, marked in neutral ink where they stand in a name
function marked(text, terms) {
  const s = String(text ?? "");
  if (!terms.length) return esc(s);
  const re = new RegExp(terms.map((t) => t.replace(/[.*+?^${}()|[\]\\]/g, "\\$&")).join("|"), "gi");
  let out = "", at = 0;
  for (const m of s.matchAll(re)) { if (!m[0]) continue; out += `${esc(s.slice(at, m.index))}<mark>${esc(m[0])}</mark>`; at = m.index + m[0].length; }
  return out + esc(s.slice(at));
}
/** The board's two scales: windows on one linear scale to the largest the board holds, with a faint tick at each round
 *  step, and prices on one log scale between round figures under the cheapest and over the dearest, a tick each tenfold. */
function boardScales(cs) {
  const ms = cs.flatMap((c) => c.models);
  const win = Math.max(1, ...ms.map((m) => Math.max(m.window ?? 0, m.max ?? 0)));
  const prices = ms.flatMap((m) => [rateOf(m.rates, "input"), rateOf(m.rates, "output")]).filter((x) => x > 0);
  const lo = [...PRICE_STOPS].reverse().find((v) => v <= Math.min(...prices)) ?? PRICE_STOPS[0];
  const hi = PRICE_STOPS.find((v) => v >= Math.max(...prices)) ?? PRICE_STOPS.at(-1);
  const span = Math.log(hi) - Math.log(lo) || 1;
  const at = (x) => (x > 0 ? Math.max(1, Math.min(100, ((Math.log(x) - Math.log(lo)) / span) * 100)) : 0);
  const step = [100_000, 250_000, 500_000, 1_000_000, 2_000_000].find((s) => win / s <= 6) ?? win / 4;
  return { win, at, lo, hi, wtick: (step / win) * 100, ptick: 100 / Math.max(1, Math.round(span / Math.LN10)) };
}
const pctOf = (n, of) => (n > 0 && of > 0 ? Math.min(100, (n / of) * 100) : 0);
function winTrack(m, sc) {
  const w = pctOf(m.window, sc.win), most = m.max > (m.window ?? 0) ? pctOf(m.max, sc.win) : 0;
  const over = m.rates?.long_context?.over_input_tokens;
  const notch = over > 0 && over < Math.max(m.window ?? 0, m.max ?? 0) ? `<u style="left:${pctOf(over, sc.win).toFixed(2)}%"></u>` : "";
  return `<span class="wtrk" aria-hidden="true">${most ? `<i class="most" style="width:${most.toFixed(2)}%"></i>` : ""}`
    + `${w ? `<i class="win" style="width:${w.toFixed(2)}%"></i>` : ""}${notch}</span>`;
}
// a price track: the band to [lo], and the line on to [hi] ending in its tick (Out on a row, the dearer rate on a card)
const priceTrack = (lo, hi, sc) => `<span class="ptrk" aria-hidden="true">${hi != null ? `<i class="out" style="width:${sc.at(hi).toFixed(2)}%"></i>` : ""}`
  + `<i class="in" style="width:${sc.at(lo).toFixed(2)}%"></i></span>`;
/** The marks, named once for the whole board; the unit of every price figure is said here. */
const everyLegend = () => `<div class="em-legend"><span class="lg"><i class="sw main"></i>Main model</span>`
  + `<span class="lg"><i class="sw win"></i>Window<i class="sw most"></i>Most it takes<i class="sw notch"></i>Price rises</span>`
  + `<span class="lg"><i class="sw in"></i>In<i class="sw out"></i>Out, $ per 1M tokens</span><span class="lg"><i class="sw none"></i>No price</span></div>`;
/** A card's column head, once over its rows: the window scale's end over its track, the price scale's two ends over its
 *  own, and the two figures each names. */
const everyHead = (sc) => `<div class="em-row em-colhead" aria-hidden="true"><span></span>`
  + `<span class="axh"><i>${big(sc.win)}</i></span><span class="ch">Window</span>`
  + `<span class="axh two"><i>${priceTick(sc.lo)}</i><i>${priceTick(sc.hi)}</i></span><span class="ch">In</span><span class="ch">Out</span></div>`;
const rateId = (c, m) => `rate-${`${c.key}-${m.id}`.replace(/[^A-Za-z0-9_-]/g, "_")}`;
/** A model's rate card, opened under its row: each rate its card sends as a band on the board's price scale with its
 *  figure; past the long-context threshold the dearer rate is the line on to its tick and a second figure. */
function rateCard(m, id, grow, sc) {
  const r = m.rates, lc = r?.long_context;
  const KINDS = [["input", "In"], ["output", "Out"], ["cache_read", "Cache read"], ["cache_write", "Cache write"], ["cache_write_1h", "Cache write, 1 h"]];
  const cols = r ? KINDS.filter(([k]) => rateOf(r, k) != null || lc?.[k] != null) : [];
  const most = m.max > (m.window ?? 0) ? `<span class="rwins">Most it takes <b>${big(m.max)}</b></span>` : "";
  const over = lc ? big(lc.over_input_tokens) : "";
  const head = `<span class="rk">$ per 1M tokens</span><span></span>${lc ? `<span class="rh">Up to ${over}</span><span class="rh">Past ${over}</span>` : `<span class="rh"></span>`}`;
  const row = ([k, w]) => {
    const low = rateOf(r, k), high = lc?.[k] ?? null;
    return `<span class="rk">${esc(w)}</span>${low != null || high != null ? priceTrack(low ?? high, high, sc) : `<span class="ptrk none"></span>`}`
      + `<b class="v">${per1m(low)}</b>${lc ? `<b class="v">${per1m(high)}</b>` : ""}`;
  };
  const table = cols.length ? `<div class="rtab${lc ? " lc" : ""}">${head}${cols.map(row).join("")}</div>` : DRAW.nothing("No price");
  return `<div class="em-rate${grow ? " grow" : ""}" id="${id}">${most}${table}</div>`;
}
function everyRow(c, m, tiers, sc, fresh, i, terms, name = m.label, maker = "") {
  const k = `${c.key}|${m.id}`, open = ui.rates.has(k), id = rateId(c, m), turned = ui.fx?.turn === k;
  const disc = DRAW.disc({ open, controls: id, act: "rate", data: ` data-r="${esc(k)}"`, cls: `em-name${turned ? (open ? " turn-on" : " turn-off") : ""}`,
    inner: `<span class="nm">${maker ? `<span class="mk">${esc(maker)}</span>` : ""}<span class="mname" title="${esc(m.label)}">${marked(name, terms)}</span>${tierTags(m.id, tiers)}</span>` });
  const r = m.rates, i0 = rateOf(r, "input"), o0 = rateOf(r, "output");
  const price = r && (i0 != null || o0 != null) ? `${priceTrack(i0 ?? o0, o0, sc)}<b class="v">${per1m(i0)}</b><b class="v">${per1m(o0)}</b>`
    : `<span class="ptrk none" aria-hidden="true"></span><b class="v">${DASH}</b><b class="v">${DASH}</b>`;
  return `<div class="em-item${open ? " open" : ""}"><div class="em-row${m.id === c.pinned ? " main" : ""}${fresh ? " arrive" : ""}" style="--i:${Math.min(i, 8)}">`
    + `${disc}${winTrack(m, sc)}<b class="v">${m.window ? big(m.window) : DASH}</b>${price}</div>${open ? rateCard(m, id, turned, sc) : ""}</div>`;
}
// a model the provider does not serve leads its card: its name, and the lock where its price would be; the route's
// reason opens under it, as a rate card does
function goneRow(c, m, tiers, fresh, i, terms) {
  const k = `${c.key}|${m.id}`, open = ui.rates.has(k), id = rateId(c, m), turned = ui.fx?.turn === k;
  const name = m.reason
    ? DRAW.disc({ open, controls: id, act: "rate", data: ` data-r="${esc(k)}"`, cls: `em-name${turned ? (open ? " turn-on" : " turn-off") : ""}`,
      inner: `<span class="nm"><span class="mname">${marked(m.label, terms)}</span>${tierTags(m.id, tiers)}</span>` })
    : `<span class="em-name"><span class="nm"><span class="mname">${marked(m.label, terms)}</span>${tierTags(m.id, tiers)}</span></span>`;
  const why = open && m.reason ? `<div class="em-rate${turned ? " grow" : ""}" id="${id}"><p class="gonewhy">${esc(m.reason)}</p></div>` : "";
  return `<div class="em-item"><div class="em-row gone${fresh ? " arrive" : ""}" style="--i:${Math.min(i, 8)}">${name}`
    + `<span class="wtrk none" aria-hidden="true"></span><b class="v">${DASH}</b><span class="gonelock">${DRAW.lock("Not served")}</span></div>${why}</div>`;
}
function everyCard(c, hits, gone, sc, drawn, terms) {
  const tiers = tierMap(c), place = new Map(c.models.map((m, i) => [m.id, i]));
  const chosen = (m) => m.id === c.pinned || TIERS.some((t) => tiers[t] === m.id);
  // the main model, then the tiers' models in tier order, then the rest in the provider's own order
  const rank = (m) => (m.id === c.pinned ? -1 : TIERS.some((t) => tiers[t] === m.id) ? TIERS.findIndex((t) => tiers[t] === m.id) : TIERS.length + (place.get(m.id) ?? 0));
  const rows = [...hits].sort((a, b) => rank(a) - rank(b));
  // a roster from many makers: after the models the command uses, one head per maker, a to z, each name without the
  // maker it repeats; the models it uses keep the maker in muted ink before their name
  const makers = rows.filter((m) => makerSplit(m.label)).length >= Math.max(3, rows.length / 2);
  const top = makers ? rows.filter(chosen) : rows, rest = makers ? rows.filter((m) => !chosen(m)) : [];
  const byMaker = new Map();
  for (const m of rest) { const s = makerSplit(m.label), k = s?.maker ?? "Others"; if (!byMaker.has(k)) byMaker.set(k, []); byMaker.get(k).push({ m, name: s?.name ?? m.label }); }
  const groups = [...byMaker.entries()].sort((a, b) => (a[0] === "Others") - (b[0] === "Others") || a[0].localeCompare(b[0]));
  const flat = [...gone.map((m) => ({ m, gone: true })), ...top.map((m) => ({ m, name: m.label })),
    ...groups.flatMap(([maker, xs]) => xs.map((x, j) => ({ ...x, maker: j === 0 ? { word: maker, n: xs.length } : null })))];
  const all = flat.length, k = `every:${c.key}`;
  // a pill that would hide three rows or fewer takes about the room those rows take: they are drawn instead; a search
  // shows every match up to EVERY_FIND, so its result never takes a second press
  const folds = all > (terms.length ? EVERY_FIND : EVERY_SHOWN + 3);
  const cap = folds ? Math.min(all, ui.moreN?.[k] ?? EVERY_SHOWN) : all;
  const seen = (id) => { const key = `${c.key}|${id}`; drawn.add(key); return !ui.seenRows?.has(key); };
  const body = flat.slice(0, cap).map((x, i) => {
    const head = x.maker ? `<p class="em-maker">${esc(x.maker.word)}<b>${DRAW.n(x.maker.n)}</b></p>` : "";
    if (x.gone) return goneRow(c, x.m, tiers, seen(x.m.id), i, terms);
    const s = makers && !x.maker && x.name === x.m.label ? makerSplit(x.m.label) : null;
    return `${head}${everyRow(c, x.m, tiers, sc, seen(x.m.id), i, terms, s ? s.name : x.name, s?.maker ?? "")}`;
  }).join("");
  const n = c.models.length + c.missing.length;
  const count = `<span class="pd-count">${terms.length ? `<b>${DRAW.n(all)}</b> of ${DRAW.n(n)}` : `<b>${DRAW.n(n)}</b>`} ${n === 1 ? "model" : "models"}</span>`;
  const opened = folds && cap > EVERY_SHOWN;
  const less = (cls = "") => DRAW.act({ word: "Fewer", act: "moren", cls: `quiet small${cls}`, data: ` data-m="${esc(k)}" data-less="1"` });
  const next = folds && cap < all
    ? `<button class="morepill pill" type="button" data-act="moren" data-m="${esc(k)}" data-base="${EVERY_SHOWN}" data-step="${EVERY_STEP}">Next <b>${DRAW.n(Math.min(EVERY_STEP, all - cap))}</b> of <b>${DRAW.n(all - cap)}</b></button>` : "";
  const foot = next || opened ? `<div class="em-foot">${next}${opened ? less() : ""}</div>` : "";
  // its height in rows, folded as drawn: the head and column head, each row as its name wraps, a maker's head, the foot
  const units = 3 + flat.slice(0, folds ? EVERY_SHOWN : all).reduce((a, x) => a + rowUnits(x.name ?? x.m.label, tagWords(x.m.id, tiers)) + (x.maker ? 0.6 : 0), 0)
    + (folds ? 1.4 : 0);
  const html = `<article class="card em-card" style="--c:${colorOf(c.family)}" data-cmd="${esc(c.key)}">`
    + `<header class="em-head">${DRAW.chip({ text: c.cmd, live: c.running > 0 })}${count}${opened ? less(" headless") : ""}</header>`
    + `${body ? `<div class="em-rows">${everyHead(sc)}${body}</div>` : `<div class="em-rows">${DRAW.nothing("No models")}</div>`}${foot}</article>`;
  return { html, units };
}
// the provider a card stands under: the kit's one name for it, a local runtime under This computer, else the provider's
const groupOf = (c) => ({ key: PROVIDER_NAME[c.family] ? c.family : autoLocal(c) ? "local" : c.provKey || c.key,
  name: PROVIDER_NAME[c.family] ?? (autoLocal(c) ? PROVIDER_NAME.local : c.prov), c: colorOf(PROVIDER_NAME[c.family] ? c.family : autoLocal(c) ? "local" : c.family) });
// the board's columns before the board itself has been measured: the page's width in board columns and their gaps
const boardN = () => ui.everyN ?? Math.max(1, Math.floor((root.clientWidth / (parseFloat(getComputedStyle(document.documentElement).fontSize) || 16) + 2) / (EVERY_COL_REM + 2)));
/** How many board columns the board's own width holds; true when that changed since the last draw. */
function fitEvery() {
  const b = root.querySelector(".every > .em-board");
  if (!b) return false;
  const n = DRAW.colsFit(b, EVERY_COL_REM), moved = n !== ui.everyN;
  ui.everyN = n;
  return moved;
}
const SEARCH_GLYPH = G('<circle cx="11" cy="11" r="6.5"/><path d="M16 16l4.5 4.5"/>');
// a search field with its clear cross inside it while something is typed, so the field is emptied where it is read
const searchBox = (id, value, word, clear) => `<div class="search">${SEARCH_GLYPH}`
  + `<input class="field" id="${id}" placeholder="${esc(word)}" value="${esc(value)}" autocomplete="off" spellcheck="false" aria-label="${esc(word)}">`
  + `${value ? `<button class="sclear" type="button" data-act="${clear}" aria-label="Clear">${ICON.close}</button>` : ""}</div>`;
function everyHtml() {
  const cs = commandsOf(), sc = boardScales(cs);
  if (!cs.length) return `<div class="every">${DRAW.addTile({ word: "New command", act: "everynew", cls: "em-new" })}</div>`;
  const terms = ui.q.trim().toLowerCase().split(/\s+/).filter(Boolean);
  const hit = (c, m) => terms.every((t) => `${m.label} ${m.id} ${c.cmd} ${c.prov}`.toLowerCase().includes(t));
  const total = cs.reduce((a, c) => a + c.models.length + c.missing.length, 0);
  const drawn = new Set(), groups = new Map();
  let found = 0;
  for (const c of [...cs].sort((a, b) => a.cmd.localeCompare(b.cmd))) {
    const hits = c.models.filter((m) => hit(c, m)), gone = c.missing.filter((m) => hit(c, m));
    found += hits.length + gone.length;
    if (terms.length && !hits.length && !gone.length) continue;
    const g = groupOf(c);
    if (!groups.has(g.key)) groups.set(g.key, { ...g, cards: [], weight: 2, gone: 0 });
    const x = groups.get(g.key);
    const card = everyCard(c, hits, gone, sc, drawn, terms);
    x.cards.push(card.html);
    // its height as drawn folded, so opening a card never moves another group to another column
    x.weight += card.units;
    x.gone += gone.length;
  }
  ui.seenRows = drawn;
  // the one that hurts first: a group holding a model its provider no longer serves; then a to z, This computer last
  const order = [...groups.values()].sort((a, b) => (b.gone > 0) - (a.gone > 0) || (a.key === "local") - (b.key === "local") || a.name.localeCompare(b.name));
  const count = terms.length ? `<span class="pd-count"><b>${DRAW.n(found)}</b> of ${DRAW.n(total)} models</span>`
    : `<span class="pd-count"><b>${DRAW.n(total)}</b> models on <b>${DRAW.n(cs.length)}</b> commands</span>`;
  const bar = `<div class="em-bar">${searchBox("q", ui.q, "Search models", "clearq")}${count}${order.length ? everyLegend() : ""}</div>`;
  if (!order.length) return `<div class="every">${bar}<div class="nomatch"><span class="state">No model matches <b>${esc(ui.q.trim())}</b></span>${DRAW.act({ word: "Clear", act: "clearq", cls: "quiet" })}</div></div>`;
  // the board keeps its columns while a search narrows it, so a card keeps its width as he types
  const n = boardN();
  // the groups are packed tallest first, each into the shortest column so far, so no column stands as a tall wall beside
  // short ones; inside a column they keep the order above. Those holding a model no longer served are placed first.
  const packed = [...order.filter((g) => g.gone > 0), ...order.filter((g) => !g.gone).sort((a, b) => b.weight - a.weight)];
  const cols = DRAW.packCols(packed, n, (g) => g.weight).map((col) => ({
    body: col.items.sort((a, b) => order.indexOf(a) - order.indexOf(b)).map((g) => DRAW.group({ name: g.name, c: g.c, key: g.key, body: DRAW.cards(g.cards) })).join(""),
  }));
  return `<div class="every" style="--wtk:${sc.wtick.toFixed(3)}%;--ptk:${sc.ptick.toFixed(3)}%">${bar}<div class="board em-board">${DRAW.boardCols(cols)}</div></div>`;
}

// ---------- Projects: compaction's rules for this project and command, edited in place (spec 7 Resolved) ----------
// Four rules reach a compaction here, widest first: everywhere, the command's model, this project, this project with that
// model. Each is left to the rule above it (its entry absent), set to Claude Code's own instructions (an empty text, which
// opts out of every wider one), or given text, inline or from a file. The whole topology goes back through PUT
// /api/topology with only [compaction] changed; a compaction-only edit serves the next compaction with no restart.
// What each choice does is drawn, not said: the rail lights the rule in force and moves the light as he chooses, so
// Inherit lights the rule above and a text or a file lights its own rung (fin's hint words of Oct 12 became that light).
const CX_MODES = { inherit: "Inherit", off: "Claude Code's own", text: "Text", file: "File" };
const samePath = (written, root) => written === root || written === homeRel(root) || written === `${homeRel(root)}/`;
function cxEntries(t, root, model) {
  const cx = t.compaction ?? {};
  return {
    g: cx,
    m: model ? (cx.model ?? []).find((x) => x.model === model) : null,
    p: (cx.project ?? []).find((x) => samePath(x.path, root) && !x.model),
    pm: model ? (cx.project ?? []).find((x) => samePath(x.path, root) && x.model === model) : null,
  };
}
const cxState = (e) => (e?.file != null ? { mode: "file", text: "", file: e.file } : e?.instructions === "" ? { mode: "off", text: "", file: "" }
  : e?.instructions != null ? { mode: "text", text: e.instructions, file: "" } : { mode: "inherit", text: "", file: "" });
function openComp(p, c) {
  const e = cxEntries(topology(), p.root, c.mainId);
  const rows = Object.fromEntries(Object.entries(e).map(([k, v]) => [k, cxState(v)]));
  if (rows.g.mode === "inherit") rows.g.mode = "off"; // everywhere has no wider rule: absent is Claude Code's own
  ui.comp = { root: p.root, head: c.key, model: c.mainId, rows, base: structuredClone(rows), said: null, finding: null, bad: null, saving: false };
}
// a rung he changed from what splice.toml holds; the count of them is what Save says it will write
const cxDirty = (cx, k) => ["mode", "text", "file"].some((f) => cx.rows[k][f] !== cx.base[k][f]);
// the rule as written: nothing for inherit, an empty text for Claude Code's own, else the text or the file
const cxFields = (st) => (st.mode === "off" ? { instructions: "" } : st.mode === "text" ? { instructions: st.text } : st.mode === "file" ? { file: st.file.trim() } : null);
function compTable(t, cx) {
  const was = t.compaction ?? {}, rows = cx.rows, root = cx.root, model = cx.model;
  const g = rows.g.mode === "off" ? {} : cxFields(rows.g); // everywhere's own words are no rule at all
  const keep = (list, mine) => (list ?? []).filter((x) => !mine(x));
  const writtenRoot = (was.project ?? []).find((x) => samePath(x.path, root))?.path ?? root;
  const models = keep(was.model, (x) => model && x.model === model);
  const projects = keep(was.project, (x) => samePath(x.path, root) && (x.model ?? null) === (null));
  const both = model ? keep(projects, (x) => samePath(x.path, root) && x.model === model) : projects;
  if (model && cxFields(rows.m)) models.push({ model, ...cxFields(rows.m) });
  if (cxFields(rows.p)) both.push({ path: writtenRoot, ...cxFields(rows.p) });
  if (model && cxFields(rows.pm)) both.push({ path: writtenRoot, model, ...cxFields(rows.pm) });
  return { ...g, model: models, project: both };
}
// what stops a save before it is sent: an empty text or file, or a table that holds a rule twice, which splice would
// write and then keep its old rules over ("[compaction] unchanged", splice-turns)
function compRefusal(table) {
  const rows = Object.entries(ui.comp.rows);
  const empty = rows.find(([, st]) => st.mode === "text" && !st.text.trim());
  if (empty) return { word: "Write the text, or choose Claude Code's own", at: empty[0] };
  const nameless = rows.find(([, st]) => st.mode === "file" && !st.file.trim());
  if (nameless) return { word: "Name the file", at: nameless[0] };
  const twice = (keys) => keys.some((k, i) => keys.indexOf(k) !== i);
  if (twice(table.model.map((x) => x.model)) || twice(table.project.map((x) => `${x.path}\n${x.model ?? ""}`))) return { word: "splice.toml holds one rule twice; fix it there first", at: null };
  return null;
}
/** Save: refused in place before it is sent (the word beside Save, the rung's field in the limit red, one shake), or
 *  sent whole through PUT /api/topology. splice's own finding is said in its words under Save, never the bare "Config
 *  error" it used to be with the reason only in the browser's console. A save that lands pulses the rail once. */
async function compSave() {
  const cx = ui.comp, t = structuredClone(topology());
  const table = compTable(t, cx);
  const no = compRefusal(table);
  Object.assign(cx, { said: no?.word ?? null, bad: no?.at ?? null, finding: null });
  if (no) { ui.shook = "csave"; render(); return; }
  t.compaction = table;
  cx.saving = true; render();
  const res = await API.put("/api/topology", { topology: t });
  cx.saving = false;
  if (!res.ok || !res.body?.ok) {
    const found = (res.body?.findings ?? []).map((x) => x.message).filter(Boolean);
    // why, in splice's own words: its findings on the config, else the error it answered with, else that it is silent
    const why = found.length ? found.join("; ") : res.status === 0 ? "splice is not answering" : res.body?.error ?? null;
    Object.assign(cx, { said: found.length ? "Config error" : "Not saved", finding: why });
    ui.shook = "csave"; render(); return;
  }
  ui.comp = null; ui.files = {};
  ui.pending = Boolean(res.body.restart_required); ui.restartDue = ui.pending;
  await readAll();
  ui.fx = { landed: "comp" };
  render();
}

// ---------- Projects: what Claude Code reads in a repo, what each command sends there, and what compaction uses ----------
// THE WALL AND THE PANE (the tabs' pass, Oct 26). Projects was a column of 35 identical buttons beside a pane capped at
// 100rem, and its stack, ladder and files read as lists of words. Every project is now a tile in the folder it sits in,
// with its sessions drawn; the open one is its files, then the commands as chips, then three plates that follow the
// chip picked: the system prompt as a rail of layers, compaction as a rail of rules with the one in force lit, and its
// memory here. The same markup is a wall across the top at his width and a rail beside the pane at 1440: the tab's own
// width decides (models.css @container), never the screen's.
async function loadProject(id) {
  if (ui.files[id]) return;
  ui.files[id] = { reading: true };
  const res = await API.get(`/api/projects/${encodeURIComponent(id)}/files`);
  ui.files[id] = res.ok ? { files: res.body?.files ?? [] } : { files: [], err: res.status === 0 ? "Not answering" : "Not read" };
  render();
}
// the [projects."ROOT"] table splice.toml holds for a project, however its path was written there
const projTopo = (root) => Object.entries(topology().projects ?? {}).find(([k]) => samePath(k, root))?.[1] ?? null;
const ownPrompt = (p) => { const l = projTopo(p.root); return Boolean(l && (l.system_prompt || l.system_prompt_file || Object.keys(l.heads ?? {}).length)); };
const ownComp = (p) => (topology().compaction?.project ?? []).some((x) => samePath(x.path, p.root));
/** Each project's sessions from /api/sessions: all of them, the live ones, and both again per command. */
function projectStats() {
  const by = new Map();
  for (const s of data.sessions) {
    const id = s.project?.id;
    if (!id) continue;
    const x = by.get(id) ?? { total: 0, live: 0, heads: new Map(), lives: new Map() };
    const on = s.availability === "live";
    x.total++; if (on) x.live++;
    x.heads.set(s.head, (x.heads.get(s.head) ?? 0) + 1);
    if (on) x.lives.set(s.head, (x.lives.get(s.head) ?? 0) + 1);
    by.set(id, x);
  }
  return by;
}
const NO_STATS = { total: 0, live: 0, heads: new Map(), lives: new Map() };
/** The folders the projects sit in: each project joins the deepest folder that holds three projects or more, so
 *  temple's nine read as one group and a repo alone in its folder joins Elsewhere with its whole place on its tile.
 *  The groups with live sessions lead, then the busiest; inside a group, the same. */
function projectFolders(ps, st) {
  const ups = (root) => { const parts = String(root ?? "").split("/").filter(Boolean); return parts.slice(0, -1).map((_, i) => `/${parts.slice(0, i + 1).join("/")}`); };
  const n = new Map();
  for (const p of ps) for (const d of ups(p.root)) n.set(d, (n.get(d) ?? 0) + 1);
  const by = new Map();
  for (const p of ps) {
    const dir = ups(p.root).reverse().find((d) => n.get(d) >= 3) ?? "";
    by.set(dir, [...(by.get(dir) ?? []), p]);
  }
  const groups = [], loose = [];
  for (const [dir, list] of by) { if (dir && list.length > 1) groups.push({ dir, list }); else loose.push(...list); }
  const of = (p) => st.get(p.id) ?? NO_STATS;
  const rank = (a, b) => b.live - a.live || b.total - a.total;
  for (const g of groups) { g.list.sort((a, b) => rank(of(a), of(b)) || a.name.localeCompare(b.name)); g.live = g.list.reduce((s, p) => s + of(p).live, 0); g.total = g.list.reduce((s, p) => s + of(p).total, 0); }
  groups.sort((a, b) => rank(a, b) || a.dir.localeCompare(b.dir));
  if (loose.length) groups.push({ dir: "", list: loose.sort((a, b) => rank(of(a), of(b)) || a.name.localeCompare(b.name)) });
  return groups;
}
const PG = {
  prompt: G('<path d="M5 6h14M5 10.5h14M5 15h8"/>', 'aria-hidden="true"'),
  squeeze: G('<path d="M12 3v6.5M8.8 6.5 12 9.5l3.2-3M12 21v-6.5M8.8 17.5l3.2-3 3.2 3M4 12h16"/>', 'aria-hidden="true"'),
  draft: G('<path d="M4 20h4L19 9l-4-4L4 16z"/><path d="M13.5 6.5l4 4"/>', 'aria-hidden="true"'),
};
// a project's sessions as a length every tile shares: on a log scale, so 28 sessions and 1 differ by eye beside 616
const shareOf = (n, max) => (n > 0 ? Math.max(4, (Math.log1p(n) / Math.log1p(Math.max(1, max))) * 100) : 0);
// the tiles' marks: its own system prompt, its own compaction rule, and an edit in hand on a project not open
const marksOf = (p, on) => ({ prompt: ownPrompt(p), squeeze: ownComp(p), draft: ui.comp?.root === p.root && !on });
const MARK_WORDS = { prompt: "Its own system prompt", squeeze: "Its own compaction rule", draft: "A compaction edit not saved yet" };
/** A project: one line of its name, its marks, its live sessions and its sessions drawn and counted; its place under it
 *  only where the folder and the name do not already say it (the whole place is the tile's title). */
function projTile(p, rel, s, maxTotal, on) {
  const mk = marksOf(p, on);
  const marks = Object.keys(MARK_WORDS).filter((k) => mk[k]).map((k) => `<span class="pmark${k === "draft" ? " draft" : ""}" title="${MARK_WORDS[k]}">${PG[k]}</span>`).join("");
  return `<button class="proj-tab" type="button" data-act="proj" data-p="${esc(p.id)}" aria-current="${on}" title="${esc(homeRel(p.root))}">`
    + `<span class="pname">${marked(p.label || p.name, ui.pq.trim().toLowerCase().split(/\s+/).filter(Boolean))}</span>${marks}`
    + `${s.live ? `<span class="pt-live"><i></i><b>${DRAW.n(s.live)}</b></span>` : ""}`
    + `<span class="pt-share" aria-hidden="true">${s.total ? `<i style="width:${shareOf(s.total, maxTotal).toFixed(1)}%"></i>` : ""}</span><b class="pt-n">${s.total ? DRAW.n(s.total) : DASH}</b>`
    + `${rel ? `<span class="ppath"><bdo dir="ltr">${esc(rel)}</bdo></span>` : ""}</button>`;
}
// the key to the tiles: the sessions bar every tile draws, and each mark only while a tile on screen carries it
const projLegend = (has = {}) => `<div class="pj-legend"><span class="lg"><i class="sw share"></i>Sessions</span>`
  + Object.keys(MARK_WORDS).filter((k) => has[k]).map((k) => `<span class="lg">${PG[k]}${MARK_WORDS[k]}</span>`).join("") + `</div>`;

// ----- a project's files: a tile each, and the one open rendered under them -----
const lineCount = (text) => String(text ?? "").replace(/\s+$/, "").split("\n").length;
const FILE_FOLD = 40;
// an instructions or memory file as it reads: headings, lists, quotes, code and rules, with `code` and **bold** inline
const inl = (s) => esc(s).replace(/`([^`]+)`/g, "<code>$1</code>").replace(/\*\*([^*]+)\*\*/g, "<b>$1</b>");
function mdDoc(text) {
  const out = []; let para = [], list = null, code = null;
  const flushP = () => { if (para.length) out.push(`<p>${inl(para.join(" "))}</p>`); para = []; };
  const flushL = () => { if (list) out.push(`<${list.tag}>${list.items.map((x) => `<li>${inl(x)}</li>`).join("")}</${list.tag}>`); list = null; };
  for (const line of String(text ?? "").split("\n")) {
    if (code) { if (/^\s*```/.test(line)) { out.push(`<pre><code>${esc(code.join("\n"))}</code></pre>`); code = null; } else code.push(line); continue; }
    if (/^\s*```/.test(line)) { flushP(); flushL(); code = []; continue; }
    const h = /^(#{1,6})\s+(.*)$/.exec(line);
    if (h) { flushP(); flushL(); out.push(`<h${Math.min(6, h[1].length + 2)}>${inl(h[2])}</h${Math.min(6, h[1].length + 2)}>`); continue; }
    const li = /^\s*([-*+]|\d+[.)])\s+(.*)$/.exec(line);
    if (li) { flushP(); const tag = /\d/.test(li[1]) ? "ol" : "ul"; if (list?.tag !== tag) { flushL(); list = { tag, items: [] }; } list.items.push(li[2]); continue; }
    if (!line.trim()) { flushP(); flushL(); continue; }
    if (list && /^\s{2,}\S/.test(line)) { list.items[list.items.length - 1] += ` ${line.trim()}`; continue; }
    if (/^\s*(-{3,}|\*{3,}|_{3,})\s*$/.test(line)) { flushP(); flushL(); out.push("<hr>"); continue; }
    if (/^>\s?/.test(line)) { flushP(); flushL(); out.push(`<blockquote>${inl(line.replace(/^>\s?/, ""))}</blockquote>`); continue; }
    flushL(); para.push(line.trim());
  }
  if (code) out.push(`<pre><code>${esc(code.join("\n"))}</code></pre>`);
  flushP(); flushL();
  return out.join("");
}
function fileTile(f, max) {
  const id = `${f.kind}:${f.path}`, open = ui.shownFile === id, lines = lineCount(f.text);
  return `<button class="ftile" type="button" data-act="file" data-f="${esc(id)}" aria-expanded="${open}" title="${esc(homeRel(f.path))}">${GL.file}`
    + `<span class="fname">${esc(f.path.split("/").pop())}</span><span class="fsize"><span class="fbar" aria-hidden="true"><i style="width:${pctOf(lines, max).toFixed(1)}%"></i></span>`
    + `<b>${DRAW.n(lines)}</b> ${lines === 1 ? "line" : "lines"}</span></button>`;
}
/** A file's front matter (a memory file's name, description and type between two --- lines) as its fields, and the
 *  text after it. A value's quotes are its spelling, not its content; a key with no value of its own heads the nested
 *  keys under it, which are drawn as fields of their own. */
function frontOf(text) {
  const m = /^---\r?\n([\s\S]*?)\r?\n---[ \t]*(\r?\n|$)/.exec(String(text ?? ""));
  if (!m) return { front: [], body: String(text ?? "") };
  const front = [];
  for (const line of m[1].split(/\r?\n/)) {
    const kv = /^\s*([A-Za-z0-9_.-]+):\s*(.*?)\s*$/.exec(line);
    if (kv && kv[2]) front.push([kv[1], kv[2].replace(/^(["'])(.*)\1$/, "$2")]);
  }
  return { front, body: String(text).slice(m[0].length) };
}
function filePreview(f) {
  const { front, body } = frontOf(f.text);
  const lines = body.replace(/\s+$/, "").split("\n"), all = ui.fileAll === ui.shownFile;
  const folds = lines.length > FILE_FOLD + 8;
  const shown = folds && !all ? lines.slice(0, FILE_FOLD) : lines;
  const more = folds ? DRAW.more(lines.length - FILE_FOLD, { word: "more lines", act: "fileall", open: all, less: "Fewer lines" }) : "";
  const fields = front.length ? `<dl class="ffront">${front.map(([k, v]) => `<dt>${esc(k)}</dt><dd>${inl(v)}</dd>`).join("")}</dl>` : "";
  return `<div class="fprev${ui.fx?.file ? " pj-grow" : ""}"><div class="fdoc">${fields}${mdDoc(shown.join("\n"))}</div>${more}</div>`;
}
/** A plate of files: ghosts the size of its tiles while the project's files are read, the same shapes held still when
 *  that read failed (the pane's head says so once, with Read again), its word when it has none, else a tile per file
 *  with its length drawn on the project's one scale, and the open one rendered under the tiles. */
function filesSection(kicker, fs, got, empty, max, aside = "") {
  const ghosts = (cls = "") => `<div class="ftiles${cls}" aria-hidden="true">${GHOST.bar(13, "ftile-g")}${GHOST.bar(13, "ftile-g")}</div>`;
  if (!got || got.reading) return DRAW.section(kicker, ghosts(), { aside });
  if (got.err) return DRAW.section(kicker, ghosts(" held"), { aside });
  if (!fs.length) return DRAW.section(kicker, DRAW.nothing(empty), { aside });
  const open = fs.find((f) => `${f.kind}:${f.path}` === ui.shownFile);
  return DRAW.section(kicker, `<div class="ftiles">${fs.map((f) => fileTile(f, max)).join("")}</div>${open ? filePreview(open) : ""}`, { aside });
}

// ----- the rails: the system prompt's layers and compaction's rules, widest first -----
const cmdChip = (c, text = c.cmd) => `<span class="chip" style="--c:${colorOf(c.family)}">${esc(text)}</span>`;
const pfileOf = (path) => `<span class="pfile" title="${esc(path)}">${GL.file}<span>${esc(String(path).split("/").pop())}</span></span>`;
/** A layer's or a rule's own text: its first three lines, and the rest behind a count that opens them in place. */
function textOf(text, key) {
  const lines = String(text).replace(/\s+$/, "").split("\n"), open = ui.more.has(key);
  const shown = open || lines.length <= 4 ? lines : lines.slice(0, 3);
  const more = lines.length > 4 ? DRAW.more(lines.length - 3, { word: "more lines", act: "more", data: ` data-m="${esc(key)}"`, open, less: "Fewer lines" }) : "";
  return `<div class="rtext">${shown.map((l) => `<span>${esc(l) || "&nbsp;"}</span>`).join("")}</div>${more}`;
}
const RAIL_DASH = `<span class="rnone">${DASH}</span>`;
/** SYSTEM PROMPT: what Claude Code sends as its system prompt here, layer on layer: its own, then the command's, then
 *  the project's, then the project's for this command. Under a Replace the layers above are not sent, so they dim and
 *  their stretch of the rail turns dashed; the dash is named once on the kicker line. */
function promptRail(p, c) {
  const pt = projTopo(p.root) ?? {};
  const per = Object.entries(perOf(c)).find(([root]) => samePath(root, p.root))?.[1] ?? null;
  const name = `<span class="pname">${esc(p.label || p.name)}</span>`;
  const L = [
    { k: "base", who: `<span class="rbase">Claude Code's own</span>` },
    { k: "cmd", who: cmdChip(c), mode: c.prompt.mode, text: c.prompt.text, file: c.prompt.file },
    { k: "proj", who: name, mode: pt.system_prompt_mode ?? "append", text: pt.system_prompt ?? "", file: pt.system_prompt_file ?? "" },
    { k: "pc", who: `${name}${cmdChip(c)}`, mode: per?.mode ?? "append", text: per?.text ?? "", file: per?.file ?? "", door: true },
  ];
  const set = (l) => l.k === "base" || Boolean(l.text || l.file || (l.mode && l.mode !== "append"));
  const cut = L.reduce((at, l, i) => (l.k !== "base" && set(l) && l.mode === "replace" ? i : at), -1);
  const rungs = L.map((l, i) => {
    const hidden = i < cut, on = set(l);
    const tag = l.k !== "base" && on ? `<i class="mtag">${esc(tierName(l.mode))}</i>` : "";
    const val = l.k === "base" ? "" : !on ? RAIL_DASH : l.file ? pfileOf(l.file) : l.text ? textOf(l.text, `pt:${p.root}:${c.key}:${l.k}`) : "";
    const door = l.door ? DRAW.act({ word: per ? "Edit" : "Add", act: "perdoor", cls: "quiet small", data: ` data-h="${esc(c.key)}" data-p="${esc(p.root)}"` }) : "";
    return `<li class="rung${on && !hidden ? " on" : ""}${hidden ? " cut" : ""}${on ? "" : " unset"}"><span class="rdot" aria-hidden="true"></span>`
      + `<div class="rwho">${l.who}${tag}${door}</div>${val ? `<div class="rval">${val}</div>` : ""}</li>`;
  }).join("");
  const aside = cut > 0 ? `<span class="pd-leg"><i class="rl-cut"></i>Not sent</span>` : "";
  return DRAW.section("System prompt", `<ol class="rrail">${rungs}</ol>`, { aside });
}
/** COMPACTION: the instructions a compaction here is written with, from the widest rule to the narrowest, and the one in
 *  force lit: the narrowest rule set, else Claude Code's own. Edit turns each rung into its switch and its field in
 *  place; the light follows the draft, a changed rung carries a mark, and Save counts them. */
function compRail(p, c) {
  const model = c.mainId, mlabel = c.models.find((m) => m.id === model)?.label ?? model;
  const cx = ui.comp && ui.comp.root === p.root && ui.comp.head === c.key ? ui.comp : null;
  const e = cxEntries(topology(), p.root, model);
  const st = cx ? cx.rows : Object.fromEntries(Object.entries(e).map(([k, v]) => [k, cxState(v)]));
  const keys = model ? ["g", "m", "p", "pm"] : ["g", "p"];
  // Everywhere's "Claude Code's own" is no rule at all, as an absent one is: the light stays on Claude Code's own
  const rules = (k) => st[k].mode !== "inherit" && !(k === "g" && st[k].mode === "off");
  const lit = [...keys].reverse().find(rules) ?? "base";
  const name = `<span class="pname">${esc(p.label || p.name)}</span>`;
  // a model is named in words: a chip is a command's shape, and this rung is the model's, whichever command sends to it
  const mword = model ? `<span class="rmodel">${esc(mlabel)}</span>` : "";
  const who = { base: `<span class="rbase">Claude Code's own</span>`, g: `<span class="rscope">Everywhere</span>`, m: mword, p: name, pm: `${name}${mword}` };
  // what splice reads for each rule (GET /api/projects compaction): a file it cannot read says so in the limit red
  const rows = p.compaction ?? [];
  const rowOf = { g: rows.find((r) => r.scope === "global"), m: rows.find((r) => r.scope === "model" && r.source?.includes(`model:${model}`)),
    p: rows.find((r) => r.scope === "project"), pm: rows.find((r) => r.scope === "project-model" && r.source?.includes(`model:${model}`)) };
  const value = (k) => {
    const s = st[k];
    if (s.mode === "inherit") return RAIL_DASH;
    if (s.mode === "off") return `<span class="rown">Claude Code's own</span>`;
    if (s.mode === "file") {
      const r = rowOf[k], bad = r && (r.chars == null || / unreadable$/.test(r.source ?? ""));
      // a file splice read with nothing in it is a rule that says nothing: a compaction here runs on Claude Code's own
      // instructions, so the rung says Empty beside the name instead of reading as a rule with text in it
      const said = bad ? `<span class="said limit">Unreadable</span>` : r?.chars === 0 ? `<span class="said">Empty</span>`
        : r?.chars ? `<span class="pd-count"><b>${DRAW.n(r.chars)}</b> characters</span>` : "";
      // the name and what splice read in it are two words of one line, read and copied as "compact.md Empty"
      return `${pfileOf(s.file)}${said ? ` ${said}` : ""}`;
    }
    return textOf(s.text, `cx:${p.root}:${k}`);
  };
  const field = (k) => {
    const s = cx.rows[k], modes = k === "g" ? ["off", "text", "file"] : ["inherit", "off", "text", "file"], bad = cx.bad === k;
    const sw = `<div class="switch" role="group" aria-label="Compaction rule">${modes.map((v) => `<button type="button" data-act="cmode" data-s="${k}" data-v="${v}" aria-pressed="${s.mode === v}">${CX_MODES[v]}</button>`).join("")}</div>`;
    const box = s.mode === "text" ? `<textarea class="field ptext" id="cx-${k}" data-cx="${k}" rows="3" aria-label="Compaction instructions"${bad ? ' aria-invalid="true"' : ""}>${esc(s.text)}</textarea>`
      : s.mode === "file" ? `<input class="field" id="cxf-${k}" data-cxf="${k}" value="${esc(s.file)}" placeholder="~/notes/compact.md" spellcheck="false" autocomplete="off" aria-label="Instructions file"${bad ? ' aria-invalid="true"' : ""}>` : "";
    return `${sw}${box}`;
  };
  const rung = (k) => {
    const on = k === lit, dirty = cx && k !== "base" && cxDirty(cx, k);
    const body = k === "base" ? "" : cx ? `<div class="rval redit">${field(k)}</div>` : `<div class="rval">${value(k)}</div>`;
    return `<li class="rung${on ? " on lit" : ""}${k !== "base" && !rules(k) ? " unset" : ""}${dirty ? " dirty" : ""}" data-k="${k}">`
      + `<span class="rdot" aria-hidden="true"></span><div class="rwho">${who[k]}${dirty ? `<i class="dmark" title="Changed">Changed</i>` : ""}</div>${body}</li>`;
  };
  const rail = `<ol class="rrail cx${ui.fx?.landed === "comp" ? " landed" : ""}">${["base", ...keys].map(rung).join("")}</ol>`;
  const changed = cx ? keys.filter((k) => cxDirty(cx, k)).length : 0;
  const foot = cx ? `<div class="cfoot">${DRAW.act({ word: cx.saving ? "Saving" : changed ? `Save ${changed === 1 ? "1 change" : `${changed} changes`}` : "Save", act: "csave", cls: `primary${ui.shook === "csave" ? " shook" : ""}`, busy: cx.saving, disabled: !changed || cx.saving })}`
    + `${DRAW.act({ word: "Cancel", act: "ccancel", cls: "quiet" })}${cx.said ? DRAW.result(cx.said, "bad") : ""}</div>${cx.finding ? `<p class="cfind">${esc(cx.finding)}</p>` : ""}` : "";
  // where Claude Code compacts on this command's main model, about 85% of its window (splice CLAUDE.md, section 22): the
  // window drawn on the one scale of every command's window, so a small one reads small, the point marked on it, and
  // the two figures said as a figure of its whole
  const top = Math.max(1, ...commandsOf().map((x) => x.window || 0)), at = Math.round(c.window * 0.85);
  const win = c.window ? `<div class="cwin" title="Compacts near ${big(at)} of a ${big(c.window)} window"><span class="cglyph" aria-hidden="true">${PG.squeeze}</span>`
    + `<span class="ctrk" style="--w:${pctOf(c.window, top).toFixed(2)}%" aria-hidden="true"><i></i><u></u></span>`
    + `<span class="pd-count"><b>${big(at)}</b> of ${big(c.window)}</span></div>` : "";
  // the ring on the rule in force is named once, on the kicker's line, in the editor too, since the ring follows the draft
  const aside = `<span class="pd-leg"><i class="rl-lit"></i>In force</span>${cx ? "" : DRAW.act({ word: "Edit", act: "cedit", cls: "quiet small" })}`;
  return DRAW.section("Compaction", `${rail}${foot}${cx ? "" : win}`, { aside });
}

// ----- the tab -----
function ghostProjectsHtml() {
  const tile = () => `<i class="ghost pj-gtile" aria-hidden="true"></i>`;
  const group = (n) => `<section class="pj-group" aria-hidden="true"><p class="pj-gh">${GHOST.bar(9)}</p><div class="pj-tiles">${Array.from({ length: n }, tile).join("")}</div></section>`;
  // the plates land as rails (a rung per layer or rule) and as file tiles, so their ghosts are those shapes
  const sec = (body, cls = "") => `<div class="plate${cls}"><section class="psec"><div class="sechead">${GHOST.bar(7)}</div>${body}</section></div>`;
  const rail = (ws) => sec(`<ol class="rrail">${ws.map((w) => `<li class="rung unset"><span class="rdot"></span><div class="rwho">${GHOST.bar(w)}</div></li>`).join("")}</ol>`);
  const tiles = `<div class="ftiles">${GHOST.bar(13, "ftile-g")}${GHOST.bar(13, "ftile-g")}</div>`;
  const pick = () => `<i class="ghost pj-gpick" style="width:9rem" aria-hidden="true"></i>`;
  // the side holds the loaded side's shape: the filter's box and its count, the key, then the folders, as many tiles as a
  // machine with a few dozen projects lands, so the rail does not jump to three times its height
  return `<div class="pj" aria-busy="true"><div class="pj-side"><div class="pj-bar"><i class="ghost pj-gsearch" aria-hidden="true"></i>${GHOST.bar(5)}</div>${projLegend()}`
    + `<div class="proj-list">${[9, 5, 4, 3, 12, 3].map(group).join("")}</div></div>`
    + `<section class="pane pj-pane" aria-hidden="true"><header class="pj-head">${GHOST.bar(12, "big")}${GHOST.bar(20)}</header><div class="body pj-body">`
    + `<div class="pj-cmd"><div class="pj-run"><section class="psec"><div class="sechead">${GHOST.bar(5)}</div><i class="ghost pj-gsplit"></i><div class="pj-chips">${pick()}${pick()}${pick()}</div></section></div>`
    + `<div class="pj-cols">${rail([9, 11, 7, 12])}${rail([9, 7, 10, 7, 11])}${sec(tiles)}</div></div>`
    + `${sec(tiles, " pj-files")}</div></section></div>`;
}
/** The command the plates follow: the one he picked, else the one used most in this project, else the first. */
function pickCmd(cs, s) {
  const mine = cs.find((x) => x.key === ui.projCmd);
  if (mine) return mine;
  return [...cs].sort((a, b) => (s.heads.get(b.key) ?? 0) - (s.heads.get(a.key) ?? 0))[0] ?? null;
}
/** AS RUN BY: the project's sessions as ONE bar split by command, each part in its command's colour, largest first, the
 *  part splice could not tie to one of his commands hatched at its end; the chips under it, in the same order, are its
 *  labels and the picks the plates below follow. A command never run here is still there to pick, quieter. */
function runByHtml(cs, c, s) {
  if (!c) return `<div class="pj-run">${DRAW.section("As run by", `<div class="pj-chips">${DRAW.nothing("No commands")}${DRAW.act({ word: "New command", act: "everynew", cls: "quiet small" })}</div>`)}</div>`;
  const here = (x) => s.heads.get(x.key) ?? 0;
  const pick = (x) => `<button class="pj-pick${x.key === c.key ? " on" : ""}${here(x) ? "" : " idle"}" type="button" data-act="projcmd" data-h="${esc(x.key)}" aria-pressed="${x.key === c.key}" style="--c:${colorOf(x.family)}">`
    + `${DRAW.chip({ text: x.cmd, live: (s.lives.get(x.key) ?? 0) > 0 })}${here(x) ? `<b class="pc-n">${DRAW.n(here(x))}</b>` : ""}</button>`;
  const used = cs.filter((x) => here(x)).sort((a, b) => here(b) - here(a)), rest = cs.filter((x) => !here(x));
  const known = new Set(cs.map((x) => x.key));
  const other = [...s.heads].reduce((a, [k, n]) => a + (known.has(k) ? 0 : n), 0);
  const unk = other ? `<span class="pj-unk"><i class="sw unk" aria-hidden="true"></i>Command not known<b class="pc-n">${DRAW.n(other)}</b></span>` : "";
  const all = used.reduce((a, x) => a + here(x), 0) + other;
  const split = all ? `<div class="pj-split${ui.fx?.pane ? " grow" : ""}" role="img" aria-label="${esc([...used.map((x) => `${x.cmd} ${here(x)}`), ...(other ? [`Command not known ${other}`] : [])].join(", "))}">`
    + used.map((x) => `<i style="--c:${colorOf(x.family)};width:${((here(x) / all) * 100).toFixed(2)}%" title="${esc(`${x.cmd} ${DRAW.n(here(x))}`)}"></i>`).join("")
    + `${other ? `<i class="unk" style="width:${((other / all) * 100).toFixed(2)}%" title="Command not known ${DRAW.n(other)}"></i>` : ""}</div>` : "";
  // the figure beside a chip is named once, on the kicker's line, as every section names its marks
  const aside = all ? `<span class="pd-count">sessions here</span>` : "";
  return `<div class="pj-run">${DRAW.section("As run by", `${split}<div class="pj-chips">${used.map(pick).join("")}${unk}${rest.map(pick).join("")}</div>`, { aside })}</div>`;
}
function projPane(p, cs, st) {
  const s = st.get(p.id) ?? NO_STATS, c = pickCmd(cs, s);
  // what the pane shows, for the acts pressed in it (Edit, Read again)
  ui.shownProj = p.id; ui.shownCmd = c?.key ?? null;
  loadProject(p.id);
  const got = ui.files[p.id], files = got?.files ?? [];
  const max = Math.max(1, ...files.map((f) => lineCount(f.text)));
  const instr = files.filter((f) => f.kind === "instructions"), memory = c ? files.filter((f) => f.kind === "memory" && f.head === c.key) : [];
  // the file read open: the one he opened, else the project's first instructions file, so the plate shows what Claude
  // Code reads here before anything is pressed; a press on the open tile closes it ("" until another project opens)
  ui.shownFile = ui.file ?? (instr[0] ? `${instr[0].kind}:${instr[0].path}` : null);
  const door = s.total ? `<a class="pd-count" href="sessions.html?project=${encodeURIComponent(p.id)}"><b>${DRAW.n(s.total)}</b> sessions${DRAW.go}</a>` : `<span class="pd-count">No sessions yet</span>`;
  const live = s.live ? `<span class="pt-live" title="Live now"><i></i><b>${DRAW.n(s.live)}</b></span>` : "";
  // the project's files are one read: when it fails it is said once, here, with the act that reads them again
  const failed = got?.err ? `<span class="pj-fail">${DRAW.lock(got.err)}${DRAW.act({ word: "Read again", act: "refiles", cls: "quiet small" })}</span>` : "";
  const head = `<header class="pj-head"><h2 class="pj-name">${esc(p.label || p.name)}</h2><span class="ppath" title="${esc(p.root)}"><bdo dir="ltr">${esc(homeRel(p.root))}</bdo></span>${failed}<span class="pj-door">${live}${door}</span></header>`;
  const instructions = `<div class="plate pj-files">${filesSection("Instructions", instr, got, "No files", max)}</div>`;
  const plates = c ? `<div class="pj-cols${ui.fx?.cmd ? " pj-grow" : ""}"><div class="plate">${promptRail(p, c)}</div><div class="plate">${compRail(p, c)}</div>`
    + `<div class="plate">${filesSection("Memory", memory, got, "No memory", max)}</div></div>` : "";
  // THE OPEN PROJECT IS IN THE PAGE'S ONE SCROLL: no height of its own and no scroll inside it. What each command does
  // here leads; the files every command reads follow, beside it where the pane is wide.
  return `<section class="pane pj-pane${ui.fx?.pane ? " pj-grow" : ""}" aria-label="${esc(p.label || p.name)}">${head}`
    + `<div class="body pj-body"><div class="pj-cmd">${runByHtml(cs, c, s)}${plates}</div>${instructions}</div></section>`;
}
function projectsHtml() {
  const ps = data.projects;
  const fail = data.projectsErr ? `<span class="pj-err">${DRAW.lock(data.projectsErr)}${DRAW.act({ word: "Read again", act: "reread-all", cls: "quiet small" })}</span>` : "";
  if (!ps.length) {
    if (pending("projects")) return ghostProjectsHtml();
    if (fail) return `<div class="pj-empty">${fail}</div>`;
    return `<div class="pj-empty">${DRAW.nothing("No projects")}<a class="plink" href="settings.html#projects">Projects in Settings${DRAW.go}</a></div>`;
  }
  const st = projectStats(), groups = projectFolders(ps, st), cs = commandsOf();
  const want = ui.proj ?? PICK.get();
  const p = ps.find((x) => x.id === want) ?? ps.find((x) => x.name === want) ?? groups[0].list[0];
  const terms = ui.pq.trim().toLowerCase().split(/\s+/).filter(Boolean);
  const hit = (x) => terms.every((t) => `${x.label} ${x.name} ${homeRel(x.root)}`.toLowerCase().includes(t));
  const maxTotal = Math.max(1, ...ps.map((x) => (st.get(x.id) ?? NO_STATS).total));
  const has = {};
  let found = 0;
  // a folder's head: its own name (the whole place is its title), its live sessions and its sessions in all, which are
  // what put the folder where it stands
  const folderHead = (g) => {
    const at = g.dir ? homeRel(g.dir) : "";
    const live = g.list.reduce((a, x) => a + (st.get(x.id) ?? NO_STATS).live, 0), total = g.list.reduce((a, x) => a + (st.get(x.id) ?? NO_STATS).total, 0);
    return `<p class="pj-gh" title="${esc(at || "Projects in no shared folder")}"><span class="gat">${esc(g.dir ? at.slice(at.lastIndexOf("/") + 1) : "Elsewhere")}</span>`
      + `${live ? `<span class="pt-live"><i></i><b>${DRAW.n(live)}</b></span>` : ""}<b class="gn">${total ? DRAW.n(total) : DASH}</b></p>`;
  };
  const wall = groups.map((g) => {
    const list = g.list.filter(hit);
    found += list.length;
    if (!list.length) return "";
    for (const x of list) { const mk = marksOf(x, x.id === p.id); for (const k of Object.keys(mk)) has[k] ||= mk[k]; }
    // a project in no shared folder carries its place; in a folder, the folder's head says where it sits
    const tiles = list.map((x) => projTile(x, g.dir ? "" : homeRel(x.root), st.get(x.id) ?? NO_STATS, maxTotal, x.id === p.id)).join("");
    return `<section class="pj-group">${folderHead(g)}<div class="pj-tiles">${tiles}</div></section>`;
  }).join("");
  const count = `<span class="pd-count">${terms.length ? `<b>${DRAW.n(found)}</b> of ${DRAW.n(ps.length)}` : `<b>${DRAW.n(ps.length)}</b>`} projects</span>`;
  const none = `<div class="nomatch"><span class="state">No project matches <b>${esc(ui.pq.trim())}</b></span>${DRAW.act({ word: "Clear", act: "clearpq", cls: "quiet" })}</div>`;
  // a later read that failed keeps the list it had, dimmed, so figures from before read as held, not as fresh
  return `<div class="pj"><div class="pj-side"><div class="pj-bar">${searchBox("pq", ui.pq, "Filter projects", "clearpq")}${count}${fail}</div>${projLegend(has)}`
    + `<nav class="proj-list${fail ? " stale" : ""}" aria-label="Projects">${wall || none}</nav></div>${projPane(p, cs, st)}</div>`;
}

// ---------- claude: plain `claude`, through splice or direct ----------
// The switch says where plain `claude` goes; the lamp beside it says whether the `claude` on his PATH is the one it
// names, in his words: splice's launcher, Claude Code's own, or another claude first on his PATH. The file it resolves
// to is one hover away, on those words. Without splice's launcher on disk there is nothing to switch to, so the lock
// takes the switch's place rather than two faded buttons (GET /api/claude-head).
function claudeHtml() {
  if (!data.claude) return ui.restartDue ? restartBtn() : "";
  const through = data.claude.mode === "wrapped";
  const shim = data.claude.shim_path, can = Boolean(shim);
  const due = ui.restartDue ? restartBtn() : "";
  const said = ui.claudeSaid ? DRAW.result(ui.claudeSaid, "bad") : "";
  const to = data.claude.resolves_to;
  const shadowed = Boolean(to && through && shim && to !== shim);
  // the lit lamp is for splice's launcher answering plain `claude`; Claude Code's own is a fact, not a fault, so its lamp
  // is hollow in the text's ink
  const kind = !to || shadowed ? "stalled" : to === shim ? "ready" : "own";
  const what = shadowed ? "Another claude comes first" : to === shim ? "splice's launcher" : "Claude Code's own";
  const runs = to
    ? `<span class="raside" title="${esc(homeRel(to))}"><span class="lamp inline ${kind}" aria-hidden="true">${kind === "stalled" ? GL.notstarted : "<i></i>"}</span>`
      + `<span class="rname${shadowed ? " limit" : ""}">${esc(what)}</span></span>`
    : DRAW.lock("No claude on your PATH");
  const switcher = can
    ? DRAW.switcher([["wrap", "Through splice"], ["unwrap", "Straight to Anthropic"]], through ? "wrap" : "unwrap", "claude", "Your claude command")
    : DRAW.lock("splice has no launcher on disk");
  return `<span class="clabel">Your claude command</span>${switcher}${runs}${said}${due}`;
}
async function setClaude(v) {
  const res = await API.post(`/api/claude-head/${v}`);
  ui.claudeSaid = res.ok ? null : "Not switched";
  const again = await API.get("/api/claude-head");
  if (again.ok) data.claude = again.body;
  render();
}
const VIEWS = [["commands", "Commands"], ["every", "Every model"], ["projects", "Projects"]];
// the tab he is on rides the address, so a reload or a link lands on it: ?view=every, ?view=projects&project=<id>
function viewToUrl() {
  const q = new URLSearchParams(location.search);
  if (ui.view === "commands") q.delete("view"); else q.set("view", ui.view);
  if (ui.view === "projects" && ui.proj) q.set("project", ui.proj); else q.delete("project");
  const s = q.toString();
  history.replaceState(history.state, "", `${location.pathname}${s ? `?${s}` : ""}${location.hash}`);
}
// A ROUND THAT FAILS AFTER THE PAGE LOADED SAYS SO BESIDE THE TABS (the tabs' pass, Oct 26): the page kept drawing what
// it last read with nothing to say it had stopped hearing from splice. The lock in the limit red, when it last heard,
// and Read again; the page keeps asking every 5 s, and the mark goes the moment a round answers.
function staleHtml() {
  if (!data.err) return "";
  const ago = data.heardAt ? Math.max(1, Math.round((Date.now() - data.heardAt) / 1000)) : null;
  const when = ago == null ? "" : `<span class="pd-count">last read <b>${ago < 120 ? `${ago} s` : `${Math.round(ago / 60)} min`}</b> ago</span>`;
  return `<span class="vstale" role="status">${DRAW.lock(data.err)}${when}${DRAW.act({ word: "Read again", act: "reread-all", cls: "quiet small" })}</span>`;
}
function viewsHtml() {
  return `<div class="views">${DRAW.switcher(VIEWS, ui.view, "view", "What to show")}${staleHtml()}</div>`;
}

// ---------- a new command: a template, what it asks, its sign-in, splice's checks, then Save and the restart ----------
const famOf = (p) => PROVIDER_FAMILY[p.head_key] ?? PROVIDER_FAMILY[p.name] ?? (p.name === "claude" ? "claude" : p.head_key);
const tplName = (p) => PROVIDER_NAME[famOf(p)] ?? (p.name === "local" ? "Local model" : p.name === "api-key" ? "Other provider" : p.name);
const second = (p) => Boolean(p.command) && taken(p);
const commandFor = (p, a) => (p.command ? (second(p) ? `${p.command}-${a.name.trim()}` : p.command) : a.name.trim() ? `claude-${a.name.trim()}` : "");
const taken = (p) => p.command && data.heads.some((h) => h.label === p.command || h.key === p.head_key);
// a second command on a provider already set up starts from the address that provider already sends to, never the
// profile's stock one (marlin p226: a second OpenRouter command tried openrouter.ai where the first sends to a stand-in)
const twinUrl = (p) => commandsOf().find((c) => c.key === p.head_key || c.cmd === p.command)?.url || p.base_url || "";
// the address is a field when the profile asks for it, for a second command, and once the address did not answer
// a second command at its provider's own address keeps that provider's address and models, and the form asks which key
// it sends with: the provider's own (the default, a command on that provider), or a new one, saved as another key under
// that provider with this command on it (key: "own", AddPrepare; marlin, Oct 12, Marcos's Oct 4 rule: a provider is a
// template). Neither writes over the provider's key.
const sameUrl = (p, a) => a.url.trim().replace(/\/+$/, "") === twinUrl(p).replace(/\/+$/, "");
const asksWhichKey = (p, a) => second(p) && sameUrl(p, a) && p.auth_kind === "api-key";
const sharesKey = (p, a) => second(p) && sameUrl(p, a) && !a.newKey;
const showsUrl = (p, a) => p.asks.includes("base_url") || second(p) || Boolean(a.urlFailed);
// how a template signs in, drawn before its words: a key, or a sign-in page opened in the browser
const kindGlyph = (k) => (/key/i.test(k ?? "") ? GL.key : /oauth|login|sign|browser|device/i.test(k ?? "") ? GL.browser : "");
// the command a new one will be, as the chip it will be: until a name is typed the name's place in it is a dashed slot
function toBe(p, a) {
  const cmd = commandFor(p, a);
  const stem = p.command && second(p) ? `${p.command}-` : !p.command ? "claude-" : "";
  const chip = cmd ? DRAW.chip({ text: cmd }) : `<span class="chip tobe"><span>${esc(stem)}</span><i class="slot" aria-hidden="true"></i></span>`;
  return { cmd, stem, chip };
}
function addPaneHtml() {
  const a = ui.add;
  const p = a.profile && data.profiles.find((x) => x.name === a.profile);
  const back = p && !a.busy && !a.saved && !a.sign ? `<button class="back tback" type="button" data-act="tpl-back">${GL.back}<span>Start from</span></button>` : "";
  const { cmd, stem, chip } = p ? toBe(p, a) : { cmd: "", stem: "", chip: "" };
  // the template it starts from rides the header beside the title, as an open command's provider does, with how it
  // signs in drawn by its glyph; the command it will make is its card, beside the form
  const kind = p && kindGlyph(p.auth_kind) ? `<span class="tsub" title="${esc(kindName(p.auth_kind))}">${kindGlyph(p.auth_kind)}<span>${kindGlyph(p.auth_kind) === GL.key ? "A key" : "Signs in on its own page"}</span></span>` : "";
  const head = `<header style="--c:${p ? colorOf(famOf(p)) : "var(--key-light)"}"><div class="who"><div class="top"><span class="name">New command</span>`
    + `${p ? `<span class="prov">${esc(tplName(p))}</span>` : ""}</div>${p || back ? `<div class="meta">${kind}${back}</div>` : ""}</div>`
    + `<div class="acts"><button class="icon close" data-act="cancel-add" aria-label="Close">${GL.close}</button></div></header>`;
  if (!p) {
    // each template says what it is and how it signs in, by its glyph, named once in the legend on the kicker's line;
    // one already added carries that command's own chip, and a command that does not exist yet carries its name hollow
    const tiles = data.profiles.map((x) => {
      const fam = famOf(x), live = taken(x) ? commandsOf().find((h) => h.key === x.head_key || h.cmd === x.command) : null;
      const tchip = live ? DRAW.chip({ text: live.cmd, live: live.running > 0, data: ` style="--c:${colorOf(live.family)}"` })
        : x.command ? DRAW.chip({ text: x.command, cls: "ghost" }) : "";
      const glyph = kindGlyph(x.auth_kind);
      return `<button class="pick tpl" style="--c:${colorOf(fam)}" data-act="tpl" data-t="${esc(x.name)}" title="${esc(`${tplName(x)}: ${kindName(x.auth_kind)}`)}">`
        + `<span class="ttop"><span class="blot"></span><span class="tname">${esc(tplName(x))}</span>${glyph ? `<span class="tkind">${glyph}</span>` : ""}</span>${tchip}</button>`;
    }).join("");
    const kinds = new Set(data.profiles.map((x) => (kindGlyph(x.auth_kind) === GL.key ? "key" : kindGlyph(x.auth_kind) ? "page" : "")).filter(Boolean));
    const legend = `${kinds.has("key") ? `<span class="leg">${GL.key}A key</span>` : ""}${kinds.has("page") ? `<span class="leg">${GL.browser}Signs in on its own page</span>` : ""}`;
    const none = data.profiles.length ? "" : DRAW.nothing("No templates read");
    return `<div class="pane add-pane${ui.fx?.add ? " unfold" : ""}">${head}<div class="body"><div class="sechead tplkick">${DRAW.kicker("Start from")}${legend}</div>`
      + `<div class="tpls">${tiles}</div>${none}</div></div>`;
  }
  const asks = (k) => p.asks.includes(k);
  // a field Add still waits for wears a dashed edge until it is filled, so the word beside Add points at something
  const field = (id, lbl, val, extra = "", wants = false) => `<label class="lbl" for="${id}">${lbl}</label><div class="ctl"><input class="field${id === "a-url" ? " url" : ""}${wants && !String(val).trim() ? " need" : ""}" id="${id}" value="${esc(val)}" spellcheck="false" autocomplete="off" ${extra}></div>`;
  let f = "";
  if (asks("name") || second(p)) f += field("a-name", "Name", a.name, "", true);
  // the address the template sends to by default is the field's hint, so an empty field reads as what it falls back to
  if (showsUrl(p, a)) f += field("a-url", "URL", a.url, p.base_url ? `placeholder="${esc(p.base_url)}"` : "", true);
  if (asksWhichKey(p, a)) {
    // the provider's key is drawn as the key it is: the variable it is read from and the commands that send with it
    const twin = commandsOf().find((x) => x.key === p.head_key || x.cmd === p.command);
    const env = twin ? topology().providers?.[twin.provKey]?.auth?.env : null;
    const users = twin ? commandsOf().filter((x) => x.provKey === twin.provKey) : [];
    const keyFact = !a.newKey && (env || users.length)
      ? `<span class="keyfact">${GL.key}${env ? `<code>${esc(env)}</code>` : ""}${users.map((x) => DRAW.chip({ text: x.cmd, live: x.running > 0, data: ` style="--c:${colorOf(x.family)}"` })).join("")}</span>` : "";
    f += `<span class="lbl">Key</span><div class="ctl"><div class="switch" role="radiogroup" aria-label="Key">`
      + `<button data-act="keychoice" data-v="provider" role="radio" aria-pressed="${!a.newKey}" aria-checked="${!a.newKey}">${esc(tplName(p))}'s key</button>`
      + `<button data-act="keychoice" data-v="new" role="radio" aria-pressed="${Boolean(a.newKey)}" aria-checked="${Boolean(a.newKey)}">A new key</button></div>${keyFact}</div>`;
  }
  if (!sharesKey(p, a) && (p.requires_key || (p.auth_kind === "api-key" && p.asks.length))) {
    f += field("a-key", asksWhichKey(p, a) ? "" : "Key", a.key, 'type="password" aria-label="Key"');
  }
  if (asks("models")) {
    // each window typed is a bar on one scale with every window his commands already serve, so a new row reads
    // against the ones he knows; the scale's end is said once over the bars
    const scale = Math.max(0, ...a.rows.map((r) => parseWindow(r.win) ?? 0), ...commandsOf().flatMap((x) => x.models.map((m) => m.window ?? 0)));
    const noModel = !a.rows.some((r) => r.id.trim());
    const rows = a.rows.map((r, i) => `<div class="mrow"><input class="field${noModel && i === 0 ? " need" : ""}" id="a-m${i}" data-i="${i}" data-f="id" value="${esc(r.id)}" spellcheck="false" autocomplete="off" aria-label="Model">`
      + `<input class="field win" id="a-w${i}" data-i="${i}" data-f="win" value="${esc(r.win)}" spellcheck="false" autocomplete="off" aria-label="Window"${r.win.trim() && !parseWindow(r.win) ? ' aria-invalid="true"' : ""}>`
      + `<span class="wcell">${winBar(parseWindow(r.win), null, scale)}</span>`
      + `${a.rows.length > 1 ? `<button class="icon" data-act="rmrow" data-i="${i}" aria-label="Remove this model">${GL.close}</button>` : ""}`
      + `${r.win.trim() && !parseWindow(r.win) ? `<span class="said limit">Not a window</span>` : ""}</div>`).join("");
    f += `<span class="lbl"></span><div class="ctl col"><div class="mrow labels"><span>Model</span><span>Window</span>${scale ? `<span class="mscale">to ${big(scale)}</span>` : ""}</div>${rows}`
      + `${DRAW.addTile({ word: "Add model", act: "addrow", cls: "row" })}</div>`;
  }
  const fam = famOf(p);
  const checkRow = (c) => {
    const soft = !c.ok && SOFT.has(c.name);
    const cls = c.ok ? "ready" : soft ? "unchecked" : "stalled";
    const mark = c.ok ? "<i></i>" : soft ? "<i></i>" : GL.notstarted;
    const why = c.ok ? null : failWordOf(c, p);
    const tip = why?.code ? `HTTP ${why.code}` : why?.at ?? "";
    const word = why ? `<span class="said${soft ? "" : " limit"}"${tip ? ` title="${esc(tip)}"` : ""}>${esc(why.word)}</span>` : "";
    return `<li class="${c.ok ? "pass" : "fail"}"><span class="lamp ${cls}">${mark}</span><span class="cname">${esc(c.name === "credential" && /oauth|login|sign/i.test(p.auth_kind) ? "Sign-in" : CHECK_NAME[c.name] ?? c.name)}</span>${word}</li>`;
  };
  // while splice runs its checks, their rows are arriving shapes in the place the answers land
  const checks = a.checks ? `<ol class="checks">${a.checks.map(checkRow).join("")}</ol>`
    : a.busy && !a.sign ? `<ol class="checks" aria-busy="true">${[9, 7, 8].map((w) => `<li class="gcheck">${GHOST.bar(1.2)}${GHOST.bar(w)}</li>`).join("")}</ol>` : "";
  const sg = a.sign;
  const sign = sg ? `<div class="outbox"><span class="waitbox" role="status">${ICON.wait}<span class="sr">Signing in</span></span>`
    + `${sg.code ? `<button class="usercode" data-act="copy-code">${esc(sg.code)}</button><span class="code-hint">Enter this code on the sign-in page</span>` : ""}`
    + `${sg.url ? `<button class="act" data-act="open-signin">Open sign-in</button>` : ""}<button class="act quiet" data-act="cancel-signin">Cancel</button></div>` : "";
  const said = a.said ? DRAW.result(a.said, "bad") : "";
  // Add that cannot go yet says everything it waits for, beside itself, instead of standing faded with no reason
  const missing = [(asks("name") || second(p)) && !a.name.trim() ? "a name" : "", showsUrl(p, a) && !a.url.trim() ? "an address" : "",
    asks("models") && !a.rows.some((r) => r.id.trim()) ? "a model" : ""].filter(Boolean);
  const ready = !missing.length;
  const waits = missing.length ? `Needs ${missing.length > 1 ? `${missing.slice(0, -1).join(", ")} and ${missing.at(-1)}` : missing[0]}` : "";
  // saved: splice restarts to start the command, and the pane says so until the restarted daemon lists it and its own
  // pane opens (marlin's desk A look, Oct 12: "Adding" sat still through the restart, and only a reload showed the pane)
  const foot = a.stalled
    ? `<div class="foot"><span class="said">Saved</span><span class="said limit">Not restarted</span>${restartBtn()}</div>`
    : a.saved
    ? `<div class="foot"><span class="said">Saved</span><span class="waitbox" role="status">${ICON.wait}<span class="sr">Restarting</span></span><span class="said">Restarting splice</span></div>`
    : `<div class="foot">${DRAW.act({ word: a.busy ? "Adding" : "Add", act: "add", cls: `primary${ui.shook === "add" ? " shook" : ""}`, busy: a.busy, disabled: !ready || a.busy })}`
      + `${DRAW.act({ word: "Cancel", act: "cancel-add", cls: "quiet" })}${said}${waits ? `<span class="said">${esc(waits)}</span>` : ""}</div>`;
  // WHAT ADD MAKES, drawn as the card it will be on the rail: its chip, its name's place dashed until one is typed, its
  // provider, the first model typed with its window and the address it sends to; once Add runs, splice's checks of it
  // stand under it, and the sign-in box where it signs in
  const m0 = a.rows.find((r) => r.id.trim()), w0 = m0 ? parseWindow(m0.win) : null;
  const host = hostPort((showsUrl(p, a) && a.url.trim()) || p.base_url || "");
  const card = `<article class="card h newcard" style="--c:${colorOf(fam)}" aria-label="The command Add makes"><span class="lamp" aria-hidden="true"></span>`
    + `<div class="top">${chip}${provSaid({ cmd: cmd || stem, prov: tplName(p) }) ? "" : `<span class="prov">${esc(tplName(p))}</span>`}</div>`
    + `<div class="meta">${m0 ? `<span class="cmodel">${esc(m0.id.trim())}</span>` : ""}${host ? `<span class="caddr">${esc(host)}</span>` : ""}`
    + `${w0 ? `<b class="cwin">${big(w0)}</b>` : ""}</div></article>`;
  const made = `<div class="addmade">${DRAW.kicker("Its card")}${card}${checks}${sign}</div>`;
  return `<div class="pane add-pane${ui.fx?.add ? " unfold" : ""}" style="--c:${colorOf(fam)}">${head}<div class="body"><div class="addcols">`
    + `<div class="addform">${f ? `<section class="form">${f}</section>` : ""}${foot}</div>${made}</div></div></div>`;
}
const CHECK_NAME = { credential: "Key", "base url": "URL", models: "Models", windows: "Windows", "live turn": "Live request" };
// what a provider's HTTP status means for him, in words; the number itself stays on the word's title and in the log
function httpWord(code) {
  const n = Number(code);
  return n === 401 || n === 403 ? "Key refused" : n === 404 ? "Not found there" : n === 429 ? "Rate limited"
    : n >= 500 ? "Provider failed" : n >= 400 ? "Refused" : "No answer";
}
function failWordOf(c, p) {
  const d = c.detail ?? "", http = /HTTP (\d{3})/.exec(d)?.[1] ?? null;
  console.warn(`add check ${c.name}: ${d}`);
  if (c.name === "credential") return { word: /oauth|login|sign/i.test(p.auth_kind) ? "Signed out" : "No key" };
  // the address tried is the URL field's own value beside the check, so the word does not say it again; it rides the title
  if (c.name === "base url") return { word: "No answer", at: /\bat (\S+)$/.exec(d)?.[1] ?? null };
  if (c.name === "models") {
    return http ? { word: httpWord(http), code: http }
      : { word: /did not answer with a model list|not a list/i.test(d) ? "No list" : /not listed|missing/i.test(d) ? "Not listed" : "No answer" };
  }
  if (c.name === "windows") return { word: "Too large" };
  return http ? { word: httpWord(http), code: http } : { word: "No answer" };
}
// a runtime that keeps no model list is normal: its rows are the picker
const SOFT = new Set(["models"]);
const failWord = (r) => (r.status === 409 ? "Already added" : "Not added");
async function startAdd() {
  const a = ui.add, p = data.profiles.find((x) => x.name === a.profile);
  // a press after a failed one starts a new add from the form as it reads now; the earlier one is closed
  if (a.id) { API.del(`/api/add/${encodeURIComponent(a.id)}`); a.id = null; }
  a.busy = true; a.said = null; a.checks = null; render();
  const body = { profile: p.name };
  if (p.asks.includes("name") || second(p)) body.name = a.name.trim();
  if (second(p)) body.command = commandFor(p, a);
  if (showsUrl(p, a) && a.url.trim()) body.base_url = a.url.trim();
  if (asksWhichKey(p, a) && a.newKey) body.key = "own";
  if (p.asks.includes("models")) body.models = a.rows.filter((r) => r.id.trim()).map((r) => ({ id: r.id.trim(), context_window: parseWindow(r.win) ?? undefined }));
  const res = await API.post("/api/add", body);
  if (!res.ok) { a.busy = false; a.said = failWord(res); ui.shook = "add"; render(); return; }
  a.id = res.body.id; a.env = res.body.key_env;
  if (res.body.sign_in_by === "key" && a.key.trim() && !sharesKey(p, a)) {
    const put = await API.put(`/api/keys/${encodeURIComponent(res.body.key_env)}`, { value: a.key.trim() });
    if (!put.ok) { a.busy = false; a.said = "Key not saved"; ui.shook = "add"; render(); return; }
  }
  if (res.body.sign_in_by === "login") { await signIn(); return; }
  await verifyAndSave();
}
async function signIn() {
  const a = ui.add;
  a.sign = { code: null, url: null };
  render();
  const res = await API.post(`/api/add/${encodeURIComponent(a.id)}/login`, {});
  if (!res.ok) { a.sign = null; a.busy = false; a.said = "Not completed"; render(); return; }
  for (let i = 0; i < 300 && a.sign; i++) {
    const st = (await API.get(`/api/add/${encodeURIComponent(a.id)}`)).body?.sign_in ?? {};
    if (st.state === "failed") { a.sign = null; a.busy = false; a.said = "Not completed"; render(); return; }
    if (st.state === "signed_in" || st.state === "live_after_restart") { a.sign = null; await verifyAndSave(); return; }
    const url = st.verification_uri || st.browser_url || null;
    if (url !== a.sign.url || (st.user_code || null) !== a.sign.code) { a.sign = { code: st.user_code || null, url }; render(); }
    await new Promise((r) => setTimeout(r, 1000));
  }
}
const profileOf = (a) => data.profiles.find((x) => x.name === a.profile);
// Add with a failed check saves nothing, and says so on the form with what to fix (marlin p226: a press did nothing). A
// failed address opens the URL field beside the check, filled with the address tried, so it can be fixed in place.
// The check that stopped it is lamped red on its own row under the card, with its word, so the foot says only Not added,
// never the check again as a sentence.
function notAddedWhy(a, prof) {
  const failed = a.checks.find((c) => !c.ok && !SOFT.has(c.name)) ?? a.checks.find((c) => !c.ok);
  if (failed?.name === "base url") {
    a.urlFailed = true;
    if (!a.url.trim()) a.url = /\bat (\S+)$/.exec(failed.detail ?? "")?.[1] ?? prof.base_url ?? "";
  }
  return "Not added";
}
async function verifyAndSave() {
  const a = ui.add;
  const v = await API.post(`/api/add/${encodeURIComponent(a.id)}/verify`, {});
  a.checks = v.body?.checks ?? [];
  // A press never ends in nothing: a check that stops the add shows beside its own name, and an answer with no checks says so.
  const hard = a.checks.some((c) => !c.ok && !SOFT.has(c.name));
  if (!v.ok && !a.checks.length) console.warn(`add verify: ${v.body?.error ?? v.status}`);
  if (hard || (!v.ok && !a.checks.length)) { a.busy = false; a.said = hard ? notAddedWhy(a, profileOf(a)) : "Not added"; ui.shook = "add"; render(); return; }
  render();
  const s = await API.post(`/api/add/${encodeURIComponent(a.id)}/save`, {});
  // the save checks again and refuses any check that fails, a model list it could not read included: its 409 carries the
  // rows, so the form shows them and says which one it waits on, never a bare "Not added"
  if (!s.ok) { if (Array.isArray(s.body?.checks)) a.checks = s.body.checks; a.busy = false; a.said = notAddedWhy(a, profileOf(a)); ui.shook = "add"; render(); return; }
  // the save answers with the add's own key and command (AddViews.session); the pane that opens is that command's, once
  // the restarted daemon lists it, never another command on the same profile (marlin p228)
  const key = s.body?.key, cmd = s.body?.command;
  a.saved = true; a.savedCmd = cmd; render();
  for (let i = 0; i < 90; i++) {
    await new Promise((r) => setTimeout(r, 1000));
    const up = await API.get("/api/heads");
    if (!up.ok) continue;
    await readAll();
    const added = commandsOf().find((c) => c.key === key || c.cmd === cmd);
    if (!added) continue;
    // its card lands on the rail with the kit's one pulse, so the eye finds where the new command went
    ui.add = null; ui.open = added.key; ui.draft = null; ui.added = { key: added.key, live: null }; ui.fx = { landed: added.key }; render();
    return;
  }
  // saved, but the restarted daemon never listed it in time: the file holds it, so the words say what to do, not "Not added"
  a.stalled = true; render();
}
function dropAdd() { if (ui.add?.id && !ui.add.saved) API.del(`/api/add/${encodeURIComponent(ui.add.id)}`); }

async function saveKey() {
  const kf = ui.keyin, c = cmdByKey(kf.key), field = document.getElementById("k-in");
  const env = topology().providers?.[c.provKey]?.auth?.env, value = field?.value.trim();
  if (!env || !value) { kf.said = "Not saved"; render(); return; }
  const res = await API.put(`/api/keys/${encodeURIComponent(env)}`, { value });
  if (!res.ok) { kf.said = "Not saved"; render(); return; }
  ui.keyin = null;
  await readAll();
  render();
}
async function signinCommand(key) {
  const c = cmdByKey(key);
  if (/key/i.test(c.auth)) { ui.keyin = { key, said: null }; render(); document.getElementById("k-in")?.focus(); return; }
  ui.sign = { key, code: null, url: null }; ui.fixSaid = null;
  render();
  const res = await API.post(`/api/auth/${encodeURIComponent(key)}/login`, {});
  // a sign-in that did not finish says so in the header where Sign in was pressed, on the command it was for
  if (!res.ok || !res.body?.id) { ui.sign = null; ui.fixSaid = { key, word: "Not completed" }; render(); return; }
  const sg = ui.sign;
  for (let i = 0; i < 300 && ui.sign === sg; i++) {
    const st = (await API.get(`/api/auth/${encodeURIComponent(key)}/login/${encodeURIComponent(res.body.id)}`)).body ?? {};
    if (st.state === "failed") { ui.sign = null; ui.fixSaid = { key, word: "Not completed" }; render(); return; }
    if (st.state === "signed_in" || st.state === "live_after_restart") { ui.sign = null; await readAll(); render(); return; }
    const url = st.verification_uri || st.browser_url || null;
    if (url !== sg.url || (st.user_code || null) !== sg.code) { Object.assign(sg, { url, code: st.user_code || null }); render(); }
    await new Promise((r) => setTimeout(r, 1000));
  }
}
// ---------- the fix splice offers: Start, Sign in, Restore ----------
async function fix(key) {
  const c = cmdByKey(key), s = stateOf(c);
  if (!s?.fix) return;
  if (s.fix.signin) { signinCommand(key); return; }
  ui.fixing[key] = s.fix.label === "Start" ? "Starting" : s.fix.label === "Restore" ? "Restoring" : "Signing in";
  ui.fixSaid = null;
  render();
  const res = s.fix.id ? await API.post(`/api/doctor/fix/${encodeURIComponent(s.fix.id)}`, {}) : await API.post(`/api/heads/${encodeURIComponent(key)}/start`, {});
  delete ui.fixing[key];
  // a refused fix says so on the command it was pressed on, which shakes once, never in another part of the page
  if (!res.ok) { ui.fixSaid = { key, word: "Not done" }; ui.shook = key; }
  await readAll();
  render();
}

// ---------- the page ----------
// THE RAIL: the commands that need him first, under their own kicker, then the ones that are fine, and the dashed
// tile where the next one will land. The kickers are drawn only when there is something in both groups, so a healthy
// machine reads as one list and a troubled one says which cards are which without a word on each card.
const commandsHtml = () => {
  const cs = commandsOf();
  const needs = cs.filter((c) => stateOf(c)), fine = cs.filter((c) => !stateOf(c));
  const split = needs.length > 0 && fine.length > 0;
  // while a new command is being made, the rail's tile is where it will land: it holds the chip it will be and its
  // provider, so the rail shows the coming card in its place
  const tp = ui.add?.profile ? data.profiles.find((x) => x.name === ui.add.profile) : null;
  const tile = DRAW.addTile({ word: "New command", act: "new", socket: true, pressed: Boolean(ui.add),
    body: tp ? `<span class="tland" style="--c:${colorOf(famOf(tp))}">${toBe(tp, ui.add).chip}<span class="prov">${esc(tplName(tp))}</span></span>` : null });
  const open = ui.open || ui.add;
  const group = (word, list) => (list.length ? `${split ? `<div class="rhead">${DRAW.kicker(word)}<span class="pd-count"><b>${DRAW.n(list.length)}</b></span></div>` : ""}${list.map(cardHtml).join("")}` : "");
  // no command yet: the rail says so where the first card lands, just above the tile that adds it, at every width
  const none = cs.length ? "" : DRAW.nothing("No commands yet");
  return `<div class="cmds${open ? " open" : ""}"><div class="list rail">`
    + `${group("Needs you", needs)}${group("Ready", fine)}${none}${tile}</div>${open ? paneHtml() : ""}</div>`;
};
// THE FIRST READ DRAWS AS THE PAGE THAT IS ARRIVING, not as the word "Reading": the tabs, a rail of ghost cards the
// size of the cards that land and a ghost pane beside them, so nothing moves when the commands answer. A read that
// failed says so in the limit colour, with the act that asks again (Marcos, Oct 12: "we can do better than that").
// The other two tabs arrive as their own shapes when the address opens on them (?view=every, ?view=projects): the
// board's command cards, or the projects' wall and pane.
// The tabs need no read, so they are drawn as they are and answer at once: another tab shows its own arriving shape.
const ghostViews = () => `<div class="views">${DRAW.switcher(VIEWS, ui.view, "view", "What to show")}</div>`;
const ghostPageHtml = () => (ui.view === "every"
  ? `${ghostViews()}${ghostEveryHtml()}`
  : ui.view === "projects" ? `${ghostViews()}${ghostProjectsHtml()}` : ghostCommandsHtml());
// the board while its first read is out: the search and the legend's line, then as many columns as the board holds, each
// a provider's title over command cards whose rows are the rows that land (a name, a window bar and its figure, a price
// track and its two figures), so nothing below moves when the models answer
function ghostEveryHtml() {
  const row = (w) => `<div class="em-row" aria-hidden="true">${GHOST.bar(w)}<i class="ghost gtrk"></i>${GHOST.bar(2.6)}<i class="ghost gtrk"></i>${GHOST.bar(2.6)}${GHOST.bar(2.6)}</div>`;
  const card = (n, i) => `<article class="card em-card ghosted" style="--i:${i}" aria-hidden="true"><header class="em-head">${GHOST.bar(9)}</header>`
    + `<div class="em-rows">${Array.from({ length: n }, (_, j) => row([11, 9, 13, 10, 8, 12][j % 6])).join("")}</div></article>`;
  const group = (ns, i) => `<section class="titled" aria-hidden="true"><header><h2>${GHOST.bar(7, "big")}</h2></header><div class="stack-cards">${ns.map((n, j) => card(n, i + j)).join("")}</div></section>`;
  const cols = [[[6, 6]], [[6], [2]], [[6], [1, 1]], [[6], [6]]];
  return `<div class="every" aria-busy="true"><div class="em-bar"><i class="ghost pj-gsearch em-gsearch" aria-hidden="true"></i>${GHOST.bar(14)}</div>`
    + `<div class="board em-board">${DRAW.boardCols(cols.slice(0, boardN()).map((gs, i) => ({ body: gs.map((ns, j) => group(ns, i + j)).join("") })))}</div></div>`;
}
const ghostCmdsBody = () => `<div class="cmds"><div class="list rail">${[0, 1, 2, 3, 4].map((i) => GHOST.card([9, 13, 7], i)).join("")}`
  + `<i class="ghost card-tile" aria-hidden="true"></i></div>`
  // beside the rail, where the open command lands on a wide screen: its header, then its columns of sections, as
  // many as the pane's width holds (the same rule the pane itself follows)
  + `<div class="gpane" aria-hidden="true"><div class="ghost-row">${GHOST.bar(2, "big")}${GHOST.bar(12, "big")}${GHOST.bar(6)}</div>`
  + `<div class="gcols">${[0, 1, 2].map(() => `<div class="plate pcol">${GHOST.bar(7)}${GHOST.fig(9)}${GHOST.track()}`
    + `${GHOST.lines([16, 13, 15])}${GHOST.bar(7)}${GHOST.lines([14, 16, 11, 15])}</div>`).join("")}</div></div></div>`;
const ghostCommandsHtml = () => `${ghostViews()}${ghostCmdsBody()}`;
// The first read failed: the tabs, the lock in the limit colour with the act that asks again, and the page that would
// have arrived, held still, since nothing is on its way until Read again is pressed or the next round answers.
const firstFailHtml = () => `${ghostViews()}<div class="firstread" role="alert">${DRAW.lock(data.err)}${DRAW.act({ word: "Read again", act: "reread-all", cls: "quiet" })}</div>`
  + (ui.view === "commands" ? `<div class="held" aria-hidden="true">${ghostCmdsBody()}</div>` : "");
/** A card that changed place slides to its new one at the kit's --t-snap, so a command that starts or fails is followed
 *  by eye rather than appearing somewhere else (the kit's own FLIP; the token is zero where he asked for no motion). */
const snapMotion = () => {
  const css = getComputedStyle(document.documentElement), v = css.getPropertyValue("--t-snap").trim(), n = parseFloat(v);
  return { duration: Number.isFinite(n) ? (v.endsWith("ms") ? n : n * 1000) : 0, easing: css.getPropertyValue("--e-out").trim() || "ease-out" };
};
function cardTops() {
  const m = new Map();
  for (const el of root.querySelectorAll(".card.h[data-open]")) m.set(el.dataset.open, el.getBoundingClientRect().top);
  return m;
}
function flip(was) {
  if (!was?.size || window.matchMedia("(prefers-reduced-motion: reduce)").matches) return;
  const how = snapMotion();
  if (!how.duration) return;
  for (const el of root.querySelectorAll(".card.h[data-open]")) {
    const from = was.get(el.dataset.open);
    if (from == null) continue;
    const dy = from - el.getBoundingClientRect().top;
    if (Math.abs(dy) < 2) continue;
    el.animate([{ transform: `translateY(${dy}px)` }, { transform: "none" }], how);
  }
}
/** WHAT A POLL CHANGED MOVES, AND ONLY THAT. Each redraw builds the page anew, so a meter, an outcome bar or a figure
 *  would swap in one frame. What the last draw showed is kept by its place ([data-meter], [data-stack], [data-fig]):
 *  a meter or a segment that moved slides from its old width to its new one, and a figure whose words changed settles
 *  in, each at the kit's --t-snap; one that did not change does not move. */
function shownNow() {
  const m = new Map(), s = new Map(), f = new Map();
  for (const b of root.querySelectorAll("[data-meter] .bar > b[data-v]")) m.set(b.closest("[data-meter]").dataset.meter, b.dataset.v);
  for (const el of root.querySelectorAll("[data-stack] .pd-seg")) s.set(`${el.closest("[data-stack]").dataset.stack}|${el.className}`, el.getBoundingClientRect().width);
  for (const el of root.querySelectorAll("[data-fig]")) f.set(el.dataset.fig, el.textContent);
  return { m, s, f };
}
function settle(was) {
  if (window.matchMedia("(prefers-reduced-motion: reduce)").matches) return;
  const how = snapMotion();
  if (!how.duration) return;
  for (const b of root.querySelectorAll("[data-meter] .bar > b[data-v]")) {
    const from = was.m.get(b.closest("[data-meter]").dataset.meter);
    if (from != null && from !== b.dataset.v) b.animate([{ width: `${from}%` }, { width: `${b.dataset.v}%` }], how);
  }
  for (const el of root.querySelectorAll("[data-stack] .pd-seg")) {
    const from = was.s.get(`${el.closest("[data-stack]").dataset.stack}|${el.className}`), to = el.getBoundingClientRect().width;
    if (from != null && Math.abs(from - to) >= 1) el.animate([{ width: `${from}px` }, { width: `${to}px` }], how);
  }
  for (const el of root.querySelectorAll("[data-fig]")) {
    const from = was.f.get(el.dataset.fig);
    if (from != null && from !== el.textContent) el.animate([{ opacity: 0.3, transform: "translateY(-.2em)" }, { opacity: 1, transform: "none" }], how);
  }
}
function render() {
  KNOBS.save?.();
  const f = document.activeElement, fid = f?.id, pos = f?.selectionStart;
  const shown = shownNow();
  // a key being pasted lives only in its field until Add is pressed, so it is carried across the redraw
  if (ui.keyin) { const k = document.getElementById("k-in"); if (k) ui.keyin.val = k.value; }
  const was = cardTops();
  document.getElementById("claude").innerHTML = data.loaded ? claudeHtml() : "";
  // a round that failed after the page loaded: what is drawn is what splice last answered, and it reads as held
  root.classList.toggle("stale", Boolean(data.loaded && data.err));
  if (!data.loaded) {
    root.innerHTML = data.err ? firstFailHtml() : ghostPageHtml();
    return;
  }
  // a section that holds a change it did not hold on the last draw opens its Save row with its own motion
  const oc = ui.view === "commands" && ui.open && !ui.add && ui.draft ? cmdByKey(ui.open) : null;
  const dirtyNow = oc ? dirtySecs(oc) : [];
  const fresh = dirtyNow.find((s) => !(ui.dirtyWas ?? []).includes(s));
  if (fresh) ui.fx = { ...(ui.fx ?? {}), saveRow: fresh };
  ui.dirtyWas = dirtyNow;
  const draw = () => { root.innerHTML = viewsHtml() + (ui.view === "commands" ? commandsHtml() : ui.view === "every" ? everyHtml() : projectsHtml()); };
  draw();
  // the open pane's columns, and the board's, come from their own width, read once they are in the page: a count that
  // changed draws once more
  if (ui.view === "commands" && fitCols()) draw();
  if (ui.view === "every" && fitEvery()) draw();
  // the meters and bars take their widths once the markup is in; they grow from nothing only when the pane just opened
  DRAW.paint(root, { grow: ui.grow ? "all" : null });
  if (!ui.grow) settle(shown);
  ui.grow = false;
  flip(was);
  if (fid) {
    const g = document.getElementById(fid);
    if (g) { g.focus(); if (pos != null && g.setSelectionRange) g.setSelectionRange(pos, pos); }
  }
  if (ui.shook) ui.shook = null;
  // a press's own motion plays once, on the render that answers it: the next poll redraws the same place still
  ui.fx = null;
}
// a pane that is now wider or narrower holds another number of columns: drawn again once the resize settles
let refit = 0;
window.addEventListener("resize", () => { clearTimeout(refit); refit = setTimeout(() => { const a = fitCols(), b = fitEvery(); if (a || b) render(); }, 120); });
function openCmd(key) {
  Object.assign(ui, { open: key, add: null, draft: null, said: null, pick: null, grow: true, changesOpen: false, split: null, qedit: null });
  readLog(key);
}
function onClick(e) {
  if (KNOBS.click?.(e, render)) { render(); return; }
  const b = e.target.closest("[data-act]");
  if (!b) {
    const card = e.target.closest(".card.h[data-open]");
    if (card) { openCmd(card.dataset.open); render(); } else if (ui.pick && !e.target.closest(".menu")) { ui.pick = null; render(); }
    return;
  }
  const d = b.dataset, a = ui.add;
  // the section a press lands in is the one whose Save lights as the next thing to press
  const sec = b.closest("[data-sec]")?.dataset.sec;
  if (sec && SECTION_FIELDS[sec]) ui.lastSec = sec;
  // a menu, a disclosure or a list's tail opens with its own motion, on the draw that opens it only
  const opens = (on, fx) => { if (on) ui.fx = fx; };
  switch (d.act) {
    case "view": ui.view = d.v; ui.pick = null; ui.seenRows = null; ui.fx = { pane: true }; viewToUrl(); if (ui.view === "commands" && !ui.wide) Object.assign(ui, { open: null, add: null, draft: null, said: null }); render(); break;
    case "rate": { if (ui.rates.has(d.r)) ui.rates.delete(d.r); else ui.rates.add(d.r); ui.fx = { turn: d.r }; render(); break; }
    case "everynew": ui.view = "commands"; viewToUrl(); Object.assign(ui, { open: null, draft: null, add: { profile: null, name: "", url: "", key: "", rows: [{ id: "", win: "" }], checks: null } }); render(); break;
    case "fix": e.stopPropagation(); fix(d.h); break;
    case "hact": pressHeadAct(ui.open, d.v); break;
    case "hact-now": if (ui.headAct?.cut) runHeadAct(ui.headAct.key, ui.headAct.act); break;
    case "hact-keep": ui.headAct = null; render(); break;
    case "close": dropAdd(); ui.headAct = null; Object.assign(ui, { open: null, add: null, draft: null, said: null }); render(); break;
    case "new": Object.assign(ui, { open: null, draft: null, add: { profile: null, name: "", url: "", key: "", rows: [{ id: "", win: "" }], checks: null } }); ui.fx = { add: true }; render(); break;
    case "cancel-add": dropAdd(); ui.add = null; render(); break;
    case "tpl": { a.profile = d.t; const pr = data.profiles.find((x) => x.name === d.t); if (pr && second(pr)) a.url = twinUrl(pr); ui.fx = { add: true }; render(); document.getElementById("a-name")?.focus(); break; }
    // back to the templates: the add begun on this one is closed, the form starts again on the next pick
    case "tpl-back": dropAdd(); ui.add = { profile: null, name: "", url: "", key: "", rows: [{ id: "", win: "" }], checks: null }; ui.fx = { add: true }; render(); break;
    case "keychoice": a.newKey = d.v === "new"; render(); if (a.newKey) document.getElementById("a-key")?.focus(); break;
    case "addrow": a.rows.push({ id: "", win: "" }); render(); document.getElementById(`a-m${a.rows.length - 1}`)?.focus(); break;
    case "rmrow": a.rows.splice(+d.i, 1); a.said = null; render(); break;
    case "add": startAdd(); break;
    case "open-signin": { const sg = a?.sign ?? ui.sign; if (sg?.url) window.open(sg.url, "_blank", "noopener"); break; }
    case "copy-code": navigator.clipboard?.writeText((a?.sign ?? ui.sign).code).catch(() => {}); break;
    case "copy-launch": navigator.clipboard?.writeText(cmdByKey(d.h)?.cmd ?? "").catch(() => {}); break;
    case "try-live": tryLive(d.h); break;
    case "savekey": saveKey(); break;
    case "cancel-key": ui.keyin = null; render(); break;
    case "cancel-signin": if (a?.sign) { a.sign = null; a.busy = false; } else ui.sign = null; render(); break;
    case "arm": armRemove(ui.open); break;
    case "disarm": ui.rm = null; render(); break;
    case "reread": readAll().then(() => armRemove(ui.open)); break;
    case "rawopen": ui.rawOpen = true; render(); break;
    case "remove": removeCommand(ui.open); break;
    case "tierpick": ui.pick = ui.pick === `tier:${d.t}` ? null : `tier:${d.t}`; ui.mq = ""; opens(ui.pick, { menu: true }); render(); document.getElementById("mq")?.focus(); break;
    case "splittiers": (ui.split ??= new Set()).add(ui.open); render(); root.querySelector('[data-act="tierpick"]')?.focus({ preventScroll: true }); break;
    case "tier": {
      const c = cmdByKey(ui.open), m = c.models.find((x) => x.id === d.v);
      // Every tier's picker sets all four to one model, or gives each its default back
      if (d.t === "all") {
        for (const x of TIERS) ui.draft.tiers[x] = { model: d.v || null, win: m ? big(m.window) : "" };
        if (!d.v) ui.draft.headWin = draftOf(c).headWin;
        ui.pick = null; edited(); render(); break;
      }
      const wasUnset = TIERS.every((x) => !ui.draft.tiers[x].model);
      // from the defaults, picking one tier declares the other three at the defaults they had: Save never turns them into a 400
      if (wasUnset && d.v) for (const x of TIERS) { const dm = c.models.find((y) => y.id === c.defaults[x]); ui.draft.tiers[x] = { model: dm?.id ?? null, win: dm ? big(dm.window) : "" }; }
      ui.draft.tiers[d.t] = { model: d.v || null, win: m ? big(m.window) : "" };
      if (!TIERS.some((x) => ui.draft.tiers[x].model)) ui.draft.headWin = draftOf(c).headWin;
      ui.pick = null; edited(); render(); break;
    }
    case "mode": if (d.id === "head") ui.draft.mode = d.v; else ui.draft.per[d.id.slice(4)].mode = d.v; edited(); render(); break;
    case "perpick": ui.pick = ui.pick === "per" ? null : "per"; ui.ppq = ""; opens(ui.pick, { menu: true }); render(); document.getElementById("ppq")?.focus(); break;
    case "per": ui.draft.per[d.p] = { mode: "append", text: "", file: "" }; ui.pick = null; edited(); render(); break;
    case "unper": delete ui.draft.per[d.p]; edited(); render(); break;
    case "save": save(d.sec); break;
    case "discard": discard(d.sec); ui.pick = null; render(); break;
    case "kindpick": ui.pick = ui.pick === "kind" ? null : "kind"; opens(ui.pick, { menu: true }); render(); break;
    case "kind": ui.draft.kind = d.v; ui.pick = null; edited(); render(); break;
    case "hdr-add": ui.draft.headers.push({ name: "", value: "", fresh: true }); render(); document.getElementById(`hn-${ui.draft.headers.length - 1}`)?.focus(); break;
    case "hdr-rm": ui.draft.headers.splice(+d.i, 1); edited(); render(); break;
    case "local": ui.draft.local = localPick(cmdByKey(ui.open), d.v === "yes"); edited(); render(); break;
    case "share": { const iso = ui.draft.isolate, i = iso.indexOf(d.v); if (i < 0) iso.push(d.v); else iso.splice(i, 1); edited(); render(); break; }
    case "quirk": ui.draft.quirks[d.q] = d.v === "" ? undefined : JSON.parse(d.v); edited(); render(); break;
    case "restart-splice": pressRestart(); break;
    case "restart-now": ui.cut = null; restartSplice(); break;
    case "keep-running": ui.cut = null; render(); break;
    // Clear hands the search back for the next word, as Projects' Clear does
    case "clearq": ui.q = ""; render(); document.getElementById("q")?.focus(); break;
    case "clearlogq": ui.logq = ""; render(); document.getElementById("logq")?.focus(); break;
    // the tail of a bounded list, opened in place: live requests, a window's sessions, the ports, the prices
    // the redraw keeps the focus on the same count, now "Fewer", without scrolling to it, so the keyboard can close it again
    case "more": { if (ui.more.has(d.m)) ui.more.delete(d.m); else { ui.more.add(d.m); ui.fx = { more: d.m }; } render(); root.querySelector(`[data-act="more"][data-m="${CSS.escape(d.m ?? "")}"]`)?.focus({ preventScroll: true }); break; }
    // a list shown a few at a time: twenty more a press, and Fewer back to the first four
    case "moren": {
      ui.moreN ??= {};
      if (d.less) delete ui.moreN[d.m]; else { ui.moreN[d.m] = (ui.moreN[d.m] ?? (Number(d.base) || 4)) + 20; ui.fx = { more: d.m }; }
      render(); root.querySelector(`[data-act="moren"][data-m="${CSS.escape(d.m ?? "")}"]`)?.focus({ preventScroll: true }); break;
    }
    case "eff": {
      ui.effOpen = !ui.effOpen; opens(ui.effOpen, { eff: true }); render();
      // it opens under the sections: when its head lands low on the screen it is brought up to be read
      const drawer = ui.effOpen ? root.querySelector("#every-settings") : null;
      if (drawer && drawer.getBoundingClientRect().top > window.innerHeight * 0.7) drawer.scrollIntoView({ block: "start", behavior: window.matchMedia("(prefers-reduced-motion: reduce)").matches ? "instant" : "smooth" });
      break;
    }
    case "qopen": ui.qOpen = !ui.qOpen; opens(ui.qOpen, { q: true }); render(); break;
    case "qback": ui.draft.quirks[d.q] = undefined; edited(); render(); break;
    case "qedit": ui.qedit = d.q; render(); document.getElementById(`q-${d.q}`)?.focus(); break;
    case "rawtoggle": ui.rawOpen = !ui.rawOpen; opens(ui.rawOpen, { raw: true }); render(); break;
    case "rawmore": ui.rawN = (ui.rawN ?? 100) + 400; render(); break;
    case "reread-all": readAll({ draw: () => render() }).then(render); break;
    case "logpause": ui.logPaused = !ui.logPaused; if (!ui.logPaused && ui.open) readLog(ui.open); else render(); break;
    // another project or command keeps a compaction edit in hand where it was made: it waits there, marked on its tile
    case "proj": {
      ui.proj = d.p; ui.file = null; ui.fileAll = null; ui.fx = { pane: true }; viewToUrl(); render();
      // the pane is in the page's one scroll: a project picked low in the rail brings the pane's top into view
      const pane = root.querySelector(".pj-pane"), bar = document.querySelector(".side")?.getBoundingClientRect().bottom ?? 0;
      if (pane && pane.getBoundingClientRect().top < bar) pane.scrollIntoView({ block: "start", behavior: window.matchMedia("(prefers-reduced-motion: reduce)").matches ? "instant" : "smooth" });
      break;
    }
    case "projcmd": ui.projCmd = d.h; if (ui.file?.startsWith("memory:")) ui.file = null; ui.fx = { cmd: true }; render(); break;
    case "cedit": {
      const p = data.projects.find((x) => x.id === ui.shownProj), c = cmdByKey(ui.shownCmd);
      if (p && c) openComp(p, c);
      render(); root.querySelector(".rrail.cx .switch button")?.focus(); break;
    }
    case "cmode": ui.comp.rows[d.s].mode = d.v; Object.assign(ui.comp, { said: null, bad: null, finding: null }); render(); document.getElementById(d.v === "text" ? `cx-${d.s}` : `cxf-${d.s}`)?.focus(); break;
    case "csave": compSave(); break;
    // the editor closes back onto the Edit that opened it, so the keyboard carries on from where it was
    case "ccancel": ui.comp = null; render(); root.querySelector('[data-act="cedit"]')?.focus(); break;
    case "file": ui.file = ui.shownFile === d.f ? "" : d.f; ui.fileAll = null; ui.fx = { file: true }; render(); break;
    case "fileall": ui.fileAll = ui.fileAll === ui.shownFile ? null : ui.shownFile; render(); break;
    case "refiles": delete ui.files[ui.shownProj]; render(); break;
    case "clearpq": ui.pq = ""; render(); document.getElementById("pq")?.focus(); break;
    // the command's own prompt layer for this project lives in its pane: the door opens it there, the layer added to the
    // draft when it has none yet, its field in view and focused, the row pulsing once where it landed
    case "perdoor": {
      const c = cmdByKey(d.h);
      if (!c) break;
      ui.view = "commands"; viewToUrl(); openCmd(c.key); takeDraft(c);
      if (!Object.keys(ui.draft.per).some((r) => samePath(r, d.p))) ui.draft.per[d.p] = { mode: "append", text: "", file: "" };
      render();
      const at = Object.keys(ui.draft.per).find((r) => samePath(r, d.p));
      const field = root.querySelector(`.pane [data-ptext="${CSS.escape(at)}"]`), row = field?.closest(".per") ?? root.querySelector(".pane .pers");
      row?.scrollIntoView({ block: "center", behavior: "instant" }); row?.classList.add("landed"); field?.focus();
      break;
    }
    case "claude": setClaude(d.v); break;
    default: break;
  }
}
function onInput(e) {
  const t = e.target, a = ui.add;
  if (t.id === "q") { ui.q = t.value; render(); return; }
  if (t.id === "pq") { ui.pq = t.value; render(); return; }
  if (t.id === "logq") { ui.logq = t.value; render(); return; }
  if (t.id === "mq") { ui.mq = t.value; render(); return; }
  if (t.id === "prq") { ui.prq = t.value; render(); return; }
  if (t.id === "ppq") { ui.ppq = t.value; render(); return; }
  if (t.id === "k-in") { if (ui.keyin) ui.keyin.val = t.value; return; }
  if (a) {
    if (t.id === "a-name") a.name = t.value;
    else if (t.id === "a-url") a.url = t.value;
    else if (t.id === "a-key") a.key = t.value;
    else if (t.dataset.i) a.rows[+t.dataset.i][t.dataset.f] = t.value;
    else return;
    a.said = null;
    render();
    return;
  }
  if (ui.comp && (t.dataset.cx || t.dataset.cxf)) {
    const st = ui.comp.rows[t.dataset.cx || t.dataset.cxf];
    if (t.dataset.cx) st.text = t.value; else st.file = t.value;
    Object.assign(ui.comp, { said: null, bad: null, finding: null }); render(); return;
  }
  if (!ui.draft) return;
  const sec = t.closest("[data-sec]")?.dataset.sec;
  if (sec && SECTION_FIELDS[sec]) ui.lastSec = sec;
  if (t.dataset.hw) ui.draft.headWin = t.value;
  else if (t.dataset.t === "all") for (const x of TIERS) ui.draft.tiers[x].win = t.value;
  else if (t.dataset.t) ui.draft.tiers[t.dataset.t].win = t.value;
  else if (t.id === "h-text") ui.draft.text = t.value;
  else if (t.id === "h-url") ui.draft.url = t.value;
  else if (t.dataset.ptext) ui.draft.per[t.dataset.ptext].text = t.value;
  else if (!ownInput(t, ui.draft)) return;
  edited();
  render();
}
root.addEventListener("click", onClick);
root.addEventListener("input", onInput);
document.getElementById("claude").addEventListener("click", onClick);
document.querySelector('.foot [data-act="theme"]')?.addEventListener("click", toggleTheme);

async function init() {
  const view = new URLSearchParams(location.search).get("view");
  if (VIEWS.some(([v]) => v === view)) ui.view = view;
  render();
  const want = new URLSearchParams(location.search).get("cmd");
  const setting = new URLSearchParams(location.search).get("setting");
  if (setting) ui.effOpen = true;
  ui.wide = sideBySide();
  // the command he asked for, or on a wide screen the first, opens at the first draw, not once every read has landed
  let opened = false;
  const openFirst = () => {
    if (opened || !data.loaded) return;
    opened = true;
    if (want && cmdByKey(want)) openCmd(want);
    else if (ui.wide && !ui.open) { const first = order(commandsOf())[0]; if (first) openCmd(first.key); }
  };
  // a locked console (api.js set body.locked on a 401) sends nothing until it is unlocked (gap 386b)
  const locked = () => document.body.classList.contains("locked");
  // THE PAGE ASKS ONLY WHILE IT CAN BE SEEN, AND NEVER WHILE ITS LAST ROUND IS ON ITS WAY (Oct 25). The tick read nine
  // routes every 5 s, in a tab nobody could see too, and started again before the last round had come back. A hidden tab,
  // a locked console, an edit in hand and a round still out each skip the tick, and a tab shown again reads at once. A
  // round asks for the lists when listsDue says so (readAll). When its heads show the commands moved, it reads the lists
  // after its live reads, and only if the page may still ask then: a pane opened meanwhile holds a draft built from the
  // lists, which a read under it would make look edited, and a tab hidden meanwhile asks nothing. The page's own writes
  // read them all and are never skipped for a round in flight, since the round's answer could predate the write. The
  // tick and the listener are set before the first read, while this page is the one shown, so the shell takes both away
  // when it is left: set after the read, the listener could land once a page left mid-read was gone, and keep that page
  // in the tab for good.
  // AN OPEN PANE IS NOT AN EDIT IN HAND (the width pass, Oct 26). A pane opens by itself on a wide screen, which set
  // ui.draft at load, and a draft of any kind stopped every round: at 3822 the rail's words and the Right now figures
  // never moved again for as long as the page was open. A draft equal to what the command reads now holds nothing of
  // his, so the page keeps asking under it, and the round that lands rebuilds it from what it read; only a draft that
  // differs from the command, his own typing, stops the page from reading the lists under him.
  const mayAsk = () => !document.hidden && !locked() && !typedIn() && !ui.add && !ui.comp && !ui.saving && !ui.keyin && !ui.sign && !Object.keys(ui.fixing).length;
  let polling = true; // the load below is the first round
  async function poll() {
    if (polling || !mayAsk()) return;
    polling = true;
    try {
      const lists = listsDue();
      await readAll({ lists });
      if (!lists && !data.err && listsDue() && mayAsk()) await readAll();
      // a pane whose draft he has not typed in is built again from what this round read, so nothing he never touched
      // reads as an edit, and a pane opened while the round was out never shows a change he did not make
      if (ui.draft && !typedIn()) ui.draft = null;
      render();
    } finally {
      polling = false;
    }
  }
  setInterval(() => { data.listsTicks++; poll(); }, TICK_MS);
  document.addEventListener("visibilitychange", poll);
  const profiles = API.get("/api/add/profiles").then((r) => { data.profiles = (r.body?.profiles ?? []).filter((p) => p.name !== "nvidia"); });
  try {
    await readAll({ draw: () => { openFirst(); render(); } });
    await profiles;
  } finally {
    polling = false;
  }
  openFirst();
  render();
  if (location.hash === "#log") document.querySelector(".hlog")?.scrollIntoView({ block: "center", behavior: "instant" });
  // the row Settings sent him to: it lands in view and pulses once, so the setting he followed is the one he sees
  if (setting) {
    const row = document.querySelector(`.pane [data-row="${CSS.escape(setting)}"]`);
    row?.scrollIntoView({ block: "center", behavior: "instant" });
    row?.classList.add("landed");
    row?.nextElementSibling?.classList.add("landed");
  }
  setInterval(() => { if (ui.open && !ui.add && !ui.logPaused && !document.hidden && !locked()) readLog(ui.open); }, 5000);
}
init();

});
