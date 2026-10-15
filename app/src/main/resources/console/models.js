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
const data = { heads: [], topo: null, models: [], local: [], projects: [], sessions: [], doctor: null, claude: null, profiles: [], loaded: false, err: null };
// What he is doing: the open command and its unsaved draft, a new command in progress, the saved words.
const ui = { rm: null, gone: new Set(), restartDue: false, sign: null, keyin: null, view: "commands", open: null, add: null, draft: null, said: null, pick: null, saving: false, restart: null,
  effOpen: false, logs: {}, logPath: {}, logWhy: {}, logq: "", logPaused: false, proj: null, projCmd: null, file: null, files: {}, more: new Set(), q: "", claudeSaid: null, fixing: {}, wide: true };

/** Every read the page draws from. With [draw], the page draws as soon as the commands and their models answer, and
 *  again as each slower read lands: the sessions listing took 4 s and more, doctor 3 s, and Models once sat blank 17 s
 *  waiting on all of them in a row (walk on d0ec523b5). What has not landed reads Reading where it shows ([pending]). */
async function readAll({ draw } = {}) {
  const paths = ["/api/heads", "/api/topology", "/api/models", "/api/claude-head"];
  const [heads, topo, models, claude] = await Promise.all(paths.map((p) => API.get(p)));
  if (!heads.ok || !topo.ok) {
    if (heads.status !== 0) console.warn(`models: /api/heads answered ${heads.status}`);
    data.err = heads.status === 0 ? "Not answering" : "Not read";
    return;
  }
  data.err = null;
  data.heads = heads.body?.heads ?? [];
  data.topo = topo.body;
  data.models = models.body?.heads ?? [];
  data.claude = claude.ok ? claude.body : null;
  KNOBS.st.cmds = data.heads.map((h) => h.key);
  KNOBS.st.commandOf = Object.fromEntries(data.heads.map((h) => [h.key, h.label || h.key]));
  KNOBS.st.providerOf = Object.fromEntries(data.heads.map((h) => [h.key, topology().heads?.[h.key]?.provider ?? ""]));
  if (draw && !data.loaded) { data.pending = new Set(["sessions", "projects", "doctor", "local", "knobs"]); data.loaded = true; draw(); }
  data.loaded = true;
  const land = (what) => { data.pending?.delete(what); draw?.(); };
  await Promise.all([
    API.get("/api/sessions").then((r) => { data.sessions = r.body?.sessions ?? []; land("sessions"); }),
    API.get("/api/projects").then((r) => { data.projects = r.body?.projects ?? []; land("projects"); }),
    API.get("/api/doctor").then((r) => { data.doctor = r.ok ? r.body : null; land("doctor"); }),
    // what each local runtime says about its models, asked of the runtimes themselves (spec 9i)
    API.get("/api/models/local").then((r) => {
      if (!r.ok && r.status !== 0) console.warn(`models: /api/models/local answered ${r.status}`);
      data.local = r.ok ? r.body?.providers ?? [] : [];
      land("local");
    }),
    KNOBS.load().then((err) => { data.knobsErr = err; land("knobs"); }),
  ]);
}
const pending = (what) => !!data.pending?.has(what);

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
  const word = auth ? (keyed ? (refused ? "Credentials refused" : "No key") : "Signed out") : bad.id === "installation/wrapper" ? "Command not found" : "Not started";
  const label = auth ? (keyed ? (refused ? "Replace key" : "Add key") : "Sign in") : bad.id === "installation/wrapper" ? "Restore" : "Start";
  return { state: bad.id, word, fix: auth ? { signin: true, label } : bad.fix_id ? { id: bad.fix_id, label } : null };
}
const cmdByKey = (k) => commandsOf().find((c) => c.key === k);

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
};
const pfile = (name) => `<span class="pfile">${GL.file}<span>${esc(name)}</span></span>`;

// ---------- a command's lamp and word ----------
function look(c) {
  if (ui.sign?.key === c.key) return { cls: "working", lamp: '<svg class="wait" viewBox="0 0 40 40"><circle cx="20" cy="20" r="15"/></svg>', word: "Signing in" };
  if (ui.fixing[c.key]) return { cls: "working", lamp: '<svg class="wait" viewBox="0 0 40 40"><circle cx="20" cy="20" r="15"/></svg>', word: ui.fixing[c.key] };
  const s = stateOf(c);
  if (ui.keyin?.key === c.key) return { cls: "working", lamp: ICON.signout, word: "Add key" };
  if (s?.state === "checking") return { cls: "working", lamp: '<svg class="wait" viewBox="0 0 40 40"><circle cx="20" cy="20" r="15"/></svg>', word: s.word };
  if (s?.state === "stopped" || s?.state === "runtime") return { cls: "stalled", lamp: GL.notstarted, word: s.word };
  if (s?.state === "installation/wrapper") return { cls: "stalled", lamp: GL.relink, word: s.word };
  if (s) return { cls: "stalled", lamp: ICON.signout, word: s.word };
  if (c.running) return { cls: "working", lamp: ICON.trace, word: `${c.running} running` };
  if (pending("sessions")) return { cls: "working", lamp: '<svg class="wait" viewBox="0 0 40 40"><circle cx="20" cy="20" r="15"/></svg>', word: "Reading" };
  return { cls: "ready", lamp: "<i></i>", word: "Ready" };
}
const order = (cs) => [...cs.filter((c) => stateOf(c)), ...cs.filter((c) => !stateOf(c))];
function signHtml(c) {
  const kf = ui.keyin;
  if (kf?.key === c.key) {
    return `<div class="outbox"><input class="field code-in" id="k-in" type="password" placeholder="Paste key" autocomplete="off" aria-label="Paste key">`
      + `<button class="act primary" data-act="savekey">Add</button><button class="act quiet" data-act="cancel-key">Cancel</button>${kf.said ? `<span class="said limit">${esc(kf.said)}</span>` : ""}</div>`;
  }
  const sg = ui.sign;
  if (sg?.key !== c.key) return "";
  return `<div class="outbox"><span class="waitbox" role="status">${ICON.wait}<span class="sr">Signing in</span></span>`
    + `${sg.code ? `<button class="usercode" data-act="copy-code">${esc(sg.code)}</button><span class="code-hint">Enter this code on the sign-in page</span>` : ""}`
    + `${sg.url ? `<button class="act" data-act="open-signin">Open sign-in</button>` : ""}<button class="act quiet" data-act="cancel-signin">Cancel</button></div>`;
}
function cardHtml(c) {
  const k = look(c), s = stateOf(c);
  const fix = s?.fix && !ui.fixing[c.key] && ui.sign?.key !== c.key && ui.keyin?.key !== c.key ? `<div class="fix"><button class="act primary" data-act="fix" data-h="${esc(c.key)}">${esc(s.fix.label)}</button></div>` : "";
  return `<article class="card h ${k.cls}" style="--c:${colorOf(c.family)}" data-open="${esc(c.key)}" aria-current="${ui.open === c.key && !ui.add}">`
    + `<span class="lamp ${k.cls}" aria-hidden="true">${k.lamp}</span>`
    + `<div class="top"><span class="chip">${c.running && !s ? "<i></i>" : ""}${esc(c.cmd)}</span><span class="prov">${esc(c.prov)}</span></div>`
    + `<div class="meta"><span class="word ${k.cls}">${esc(k.word)}</span><span>${esc(c.model)}</span>${c.window ? `<span>${big(c.window)}</span>` : ""}</div>${fix}${signHtml(c)}</article>`;
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
function headActsHtml(c) {
  const h = ui.headAct;
  if (h?.key === c.key && h.busy) return `<span class="said">${ICON.wait}${h.act === "stop" ? "Stopping" : "Restarting"}</span>`;
  const said = ui.headSaid?.key === c.key ? `<span class="said${ui.headSaid.ok ? "" : " limit"}">${esc(ui.headSaid.word)}</span>` : "";
  return `${said}<button class="act quiet small" data-act="hact" data-v="restart">Restart</button><button class="act quiet small" data-act="hact" data-v="stop">Stop</button>`;
}
function headAskHtml(c) {
  const h = ui.headAct;
  if (h?.key !== c.key || !h.cut) return "";
  const said = h.cut.length ? "Mid-request" : h.act === "stop" ? "Serves nothing until restarted" : "";
  return armedAsk(said, h.cut, `<button class="act danger small" data-act="hact-now">${HEAD_ACT[h.act][0]} ${esc(c.cmd)}</button>`,
    '<button class="act quiet small" data-act="hact-keep">Keep running</button>');
}
async function pressHeadAct(key, act) {
  const cut = await midRequest([key]);
  if (cut.length || act === "stop") { ui.headAct = { key, act, cut }; render(); return; }
  runHeadAct(key, act);
}
async function runHeadAct(key, act) {
  ui.headAct = { key, act, busy: true }; ui.headSaid = null; render();
  const res = await API.post(`/api/heads/${encodeURIComponent(key)}/${act}`);
  ui.headAct = null;
  ui.headSaid = { key, ok: res.ok, word: HEAD_ACT[act][res.ok ? 1 : 2] };
  await readAll(); render();
}
/** The wire dialect the command speaks, as splice.toml spells it (GET /api/heads' dialect). */
const dialectHtml = (c) => {
  const d = data.heads.find((x) => x.key === c.key)?.dialect;
  return d ? `<span class="dialect" title="Dialect">${esc(d)}</span>` : "";
};
function removeHtml(c) {
  const rm = ui.rm?.key === c.key ? ui.rm : null;
  if (rm?.busy) return `<span class="said">${ICON.wait}Removing</span>`;
  if (rm?.refused) {
    const chips = rm.refused.map((x) => `<a class="chip" href="sessions.html#${encodeURIComponent(x.id)}">${esc(x.name || x.id)}</a>`).join("");
    return `<span class="said limit">In use</span>${chips}<button class="act quiet" data-act="disarm">Close</button>`;
  }
  if (rm?.armed) {
    const stays = [rm.info?.requests ? `${rm.info.requests} requests` : "", rm.info?.sessions ? `${rm.info.sessions} sessions` : ""].filter(Boolean);
    return (stays.length ? `<span class="said">History stays · ${stays.join(" · ")}</span>` : "")
      + `<button class="act danger" data-act="remove">Remove ${esc(c.cmd)}</button><button class="act quiet" data-act="disarm">Keep ${esc(c.cmd)}</button>`;
  }
  if (rm?.failed) {
    const door = rm.failed.act === "reread" ? `<button class="act quiet" data-act="reread">Read again</button>`
      : rm.failed.act === "raw" ? `<button class="act quiet" data-act="rawopen">Raw log</button>` : "";
    return `<span class="said limit">${esc(rm.failed.word)}</span>${door}${rm.failed.act === "reread" ? "" : `<button class="act quiet risky" data-act="arm">Remove</button>`}`;
  }
  return `<button class="act quiet risky" data-act="arm">Remove</button>`;
}
async function armRemove(key) {
  ui.rm = { key, armed: true, info: null };
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
      : res.body?.reason === "config" ? { word: "Config error", act: "raw" } : { word: "Not read", act: "" };
    ui.rm = { key, failed };
    render();
    return;
  }
  ui.rm = null;
  ui.gone.add(key);
  ui.restartDue = true;
  Object.assign(ui, { open: null, draft: null, said: null });
  render();
}
// beside a window field: the most the provider reports for the model, or that it reports none; a window over it turns the note
// a local command's runtime, and what it says about one model: the window it serves now (its most, until it loads the
// model) and whether it calls tools (spec 9i). Null for a command whose runtime lists nothing to read.
const localOf = (c) => data.local.find((p) => p.provider === c.provKey);
// whether the runtime answers is the daemon's watch, the one the card's lamp reads, so the two never disagree;
// /api/models/local only names the runtime and lists its models
function runtimeHtml(c) {
  const r = localOf(c);
  if (!r && !c.down && !c.unread) return "";
  const said = c.down ? `<span class="said limit">Not answering</span>` : c.unread ? `<span class="said">Checking</span>`
    : r && !r.models ? `<span class="said limit">List not answering</span>` : "";
  return `<span class="lbl">Runtime</span><div class="ctl"><span class="urlread">${esc([r?.runtime, r?.version].filter(Boolean).join(" ") || r?.url || c.url)}</span>${said}</div>`;
}
function runtimeNote(c, id, over) {
  const r = localOf(c);
  if (!r?.models || !id) return null;
  const m = r.models.find((x) => x.id === id) ?? (id.includes(":") ? null : r.models.find((x) => x.id === `${id}:latest`));
  if (!m) return `<span class="said limit">Not listed</span>`;
  const win = m.served_window ? `Serves ${big(m.served_window)}` : m.ceiling ? `Max ${big(m.ceiling)}` : "";
  const tools = m.tools === true ? "Calls tools" : m.tools === false ? "No tool calls" : "";
  return [win && `<span class="said${over ? " limit" : ""}">${win}</span>`, tools && `<span class="said">${tools}</span>`].filter(Boolean).join("");
}
function limitHtml(model, sel, over) {
  if (!sel.model || !model) return "";
  return model.max ? `<span class="said${over ? " limit" : ""}">Max ${big(model.max)}</span>` : `<span class="said">No max reported</span>`;
}
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
    : l ? `<span class="said limit">${esc(l.word)}</span>` : "";
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
    ui.added.live = { ok: false, word: res.status === 0 ? "Not answering" : r?.status >= 400 ? `Failed · HTTP ${r.status}` : "Failed" };
  }
  render();
}
function paneHtml() {
  if (ui.add) return addPaneHtml();
  const c = cmdByKey(ui.open);
  if (!c) return "";
  ui.draft ??= draftOf(c);
  const d = ui.draft, k = look(c), st = draftState(c), s = stateOf(c);
  const fix = s?.fix && !ui.fixing[c.key] && ui.sign?.key !== c.key && ui.keyin?.key !== c.key ? `<button class="act primary small" data-act="fix" data-h="${esc(c.key)}">${esc(s.fix.label)}</button>` : "";
  const head = `<header style="--c:${colorOf(c.family)}"><span class="lamp ${k.cls}" aria-hidden="true">${k.lamp}</span><div class="who"><div class="top"><span class="name">${esc(c.cmd)}</span><span class="prov">${esc(c.prov)}</span></div>`
    + `<div class="meta"><span class="word ${k.cls}">${esc(k.word)}</span>${fix}${dialectHtml(c)}${headAskHtml(c)}</div>${signHtml(c)}</div>`
    + `<div class="acts">${headActsHtml(c)}${removeHtml(c)}<button class="icon close" data-act="close" aria-label="Close">${GL.close}</button></div></header>`;
  const unset = !TIERS.some((x) => d.tiers[x].model);
  const tierRows = TIERS.map((t) => {
    const sel = d.tiers[t], ts = st.tiers[t];
    const picked = c.models.find((x) => x.id === sel.model), dm = c.models.find((x) => x.id === c.defaults[t]);
    const lost = picked ? null : c.missing.find((x) => x.id === sel.model);
    const asDefault = !picked && !lost && unset && dm;
    // clearing this tier gives its default back when the other three are unset too, else None (a tier left out is a 400)
    const alone = TIERS.every((x) => x === t || !d.tiers[x].model);
    const clear = alone && dm ? `<span>${esc(dm.label)}</span><span class="dflt">Default</span>` : "<span>None</span>";
    const items = [`<button role="menuitem" data-act="tier" data-t="${t}" data-v="">${clear}</button>`,
      ...c.models.map((x) => `<button role="menuitem" data-act="tier" data-t="${t}" data-v="${esc(x.id)}"><span>${esc(x.label)}</span><span class="mmeta">${x.window ? big(x.window) : "Not reported"}</span></button>`)].join("");
    const face = picked ? esc(picked.label) : lost ? esc(lost.label) : asDefault ? `<span>${esc(dm.label)}</span><span class="dflt">Default</span>` : "None";
    const win = picked ? `<input class="field win" id="h-win-${t}" data-t="${t}" value="${esc(sel.win)}" spellcheck="false" autocomplete="off" aria-label="${tierName(t)} window">`
      : asDefault ? `<input class="field win" id="h-win-${t}" data-hw="1" value="${esc(d.headWin)}" spellcheck="false" autocomplete="off" aria-label="${tierName(t)} window">` : "";
    const bad = picked ? !(ts.ok || ts.over) : asDefault && !st.head.ok && !st.head.over;
    const note = picked ? runtimeNote(c, picked.id, ts.over) ?? limitHtml(picked, sel, ts.over)
      : lost ? notServed(lost) : asDefault ? runtimeNote(c, dm.id, st.head.over) ?? limitHtml({ max: st.head.max ?? dm.max }, { model: dm.id }, st.head.over)
      // a tier left out is never sent to Claude Code, which then asks for its own built-in model; the command refuses it
      // with a 400, so a subagent or tool call on this tier fails (LaunchModelEnvironment.kt:21-23, gap 472)
      : `<span class="said limit">Refused, so a subagent on it fails</span>`;
    return `<span class="lbl">${tierName(t)}</span><div class="ctl menuwrap"><button class="pickbtn${picked || lost ? "" : asDefault ? " dflt-tier" : " empty"}" data-act="tierpick" data-t="${t}" style="--c:${colorOf(c.family)}">${face}${ICON.caret}</button>`
      + `${ui.pick === `tier:${t}` ? `<div class="menu">${items}</div>` : ""}${win}${bad ? `<span class="said limit">Not a window</span>` : ""}${note}${picked ? sharedHtml(c) : ""}</div>`;
  }).join("");
  const per = Object.entries(d.per).map(([root, l]) => `<div class="per"><div class="per-top"><span class="pname">${esc(nameOfRoot(root))}</span>${modeSwitch(`per:${root}`, l.mode)}`
    + `<button class="icon" data-act="unper" data-p="${esc(root)}" aria-label="Remove">${GL.close}</button></div>${l.file ? pfile(l.file) : `<textarea class="field ptext" data-ptext="${esc(root)}" rows="2">${esc(l.text)}</textarea>`}</div>`).join("");
  const free = data.projects.filter((p) => !d.per[p.root]);
  const addPer = free.length ? `<div class="menuwrap"><button class="act quiet" data-act="perpick">Add project</button>${ui.pick === "per" ? `<div class="menu">${free.map((p) => `<button data-act="per" data-p="${esc(p.root)}">${esc(p.name)}</button>`).join("")}</div>` : ""}</div>` : "";
  const calm = ["Applied", "Saved", "Restarting"].includes(ui.said);
  const saidHtml = ui.said ? `<span class="said${calm ? "" : " limit"}">${ui.said === "Restarting" ? ICON.wait : ""}${esc(ui.said)}</span>` : "";
  const restart = ui.said === "Saved" && ui.pending ? restartBtn() : "";
  const form = `<section class="form" style="--edge:${colorOf(c.family)}">`
    + addedHtml(c)
    + '<h3 class="gh">Where it goes</h3>'
    + `<span class="lbl">Sends to</span><div class="ctl"><input class="field url${dirt(d.url !== c.url)}" id="h-url" value="${esc(d.url)}" spellcheck="false" autocomplete="off" aria-label="Sends to">`
    + `${st.url.ok ? "" : `<span class="said limit">Not an address</span>`}${sharedHtml(c)}</div>`
    + whereHtml(c, d, st.own)
    + signInHtml(c, d, st.own)
    + '<h3 class="gh">Models</h3>'
    + runtimeHtml(c)
    // what each tier row's two controls set, over them: the model, then the context window splice reports for it
    + (tierRows ? '<span class="lbl"></span><div class="ctl colhead" aria-hidden="true"><span class="ch-model">Model</span><span>Context window</span></div>' : "")
    + tierRows
    + nowRow(c)
    + windowsRow(c)
    + clientRow(c)
    + pricesHtml(c, d, st.own)
    + runningHtml(c, d, st.own)
    + '<h3 class="gh">Instructions</h3>'
    + `<span class="lbl">System prompt</span><div class="ctl col">${modeSwitch("head", d.mode)}${d.file ? pfile(d.file) : `<textarea class="field ptext" id="h-text" rows="3">${esc(d.text)}</textarea>`}</div>`
    + (data.knobsErr || pending("knobs") ? "" : `<div class="kn">${KNOBS.commandRowsHtml(c.key, COMMAND_KNOBS)}</div>`)
    + `<span class="lbl">Projects</span><div class="ctl col">${per}${addPer}</div>`
    + saveRowHtml(c, st, saidHtml, restart) + "</section>";
  const q = ui.logq.trim().toLowerCase(), hit = (l) => !q || l.raw.toLowerCase().includes(q);
  const lines = (logsOf(c.key) ?? []).filter(hit);
  const ev = eventsOf(lines);
  const raw = lines.filter((l) => l.raw);
  const path = ui.logPath[c.key], why = ui.logWhy[c.key];
  const top = `<div class="loghead"><h3>Log</h3>${path ? `<span class="ppath" title="${esc(path)}"><bdo dir="ltr">${esc(homeRel(path))}</bdo></span>` : ""}`
    + `<input class="field" id="logq" placeholder="Search the log" value="${esc(ui.logq)}" autocomplete="off" aria-label="Search the log">`
    + `<button class="act quiet small" data-act="logpause" aria-pressed="${ui.logPaused}">${ui.logPaused ? "Resume" : "Pause"}</button></div>`
    + (why ? `<p class="said limit">${esc(why)}</p>` : "");
  const log = `<section class="hlog">${top}${ev.length ? `<ol>${ev.map((e) => `<li><time>${esc(ampm(e.at))}</time><span>${esc(e.text)}${e.n > 1 ? ` · ${e.n} times` : ""}</span></li>`).join("")}</ol>` : `<p class="none">${q ? "No lines match" : "No events"}</p>`}`
    + `<details class="rawlog"${ui.rawOpen ? " open" : ""}><summary>Raw log<span class="mmeta">${raw.length.toLocaleString("en-US")} ${raw.length === 1 ? "line" : "lines"}</span></summary><ol>${raw.map((l) => `<li><span>${esc(l.raw)}</span></li>`).join("")}</ol></details></section>`;
  return `<div class="pane" style="--c:${colorOf(c.family)}">${head}<div class="body">${form}${effHtml(c)}${log}</div></div>`;
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
/** The windows the provider declares beside its models (gap 473), read from GET /api/topology: an extra window names
 *  one model, with its served ceiling when it has one, and a rule names every model whose id starts with its prefix.
 *  Each line says it in words, by the model's name where splice has one: a bare "k3 1M" read as nothing (walk, Oct 13). */
function windowsRow(c) {
  // splice applies an extra window to the model whose id, less a tier suffix such as [1m], is the window's id
  // (Topology.declaredWindowFor), so "k3" names Kimi K3 (1M), never the bare id beside a K3 256K row (walk, Oct 13)
  const bare = (id) => id.replace(/\[[^\]]*\]$/, "");
  const name = (id) => (c.models.find((m) => m.id === id) ?? c.models.find((m) => bare(m.id) === bare(id)))?.label ?? id;
  const extra = c.extraWindows.map((w) => `<span class="fig">${esc(name(w.id))}: ${big(w.context_window)} context window${w.max_context_window ? `, served up to ${big(w.max_context_window)}` : ""}</span>`);
  const rules = c.windowRules.map((w) => `<span class="fig">Every model whose id starts with <bdo dir="ltr">${esc(w.prefix)}</bdo>: ${big(w.context_window)} context window</span>`);
  if (!extra.length && !rules.length) return "";
  return `<span class="lbl">Context windows</span><div class="ctl col">${[...extra, ...rules].join("")}</div>`;
}
/** The window Claude Code itself counts against in each session on this command, as its status line reported it
 *  (GET /api/sessions' client_context_window, 48fa2f61b). A session that has not posted one is left out, never guessed. */
function clientRow(c) {
  const seen = data.sessions.filter((x) => x.head === c.key && x.client_context_window > 0);
  if (!seen.length) return "";
  const row = (x) => `<span class="fig"><a class="chip" href="sessions.html#${encodeURIComponent(x.session_id)}">${esc(x.name || x.title || x.session_id.slice(0, 8))}</a> ${big(x.client_context_window)}</span>`;
  return `<span class="lbl">Claude Code's window</span><div class="ctl col">${seen.map(row).join("")}</div>`;
}
// ---------- every setting as this command runs it (gaps 459 and 457) ----------
// Each setting with the value this command runs: its own where it holds one, else the shared one, as the daemon folds
// them for a turn (ConfigRoutes.kt). One a command may hold for itself is its control, applied like Settings' rows, with
// the way back to the shared value; Settings' command chips open these same rows. A daemon-wide one shows its value.
function effHtml(c) {
  if (!ui.effOpen) return `<details class="rawlog everyset"><summary>Every setting</summary></details>`;
  const K = KNOBS, wire = K.st.cfg?.knobs;
  if (!wire) return `<details class="rawlog everyset" open><summary>Every setting</summary><p class="said limit">Not read</p></details>`;
  const keys = [...K.LIST.map((k) => k.key), ...K.others().map((k) => k.key)].filter((key) => wire[key]);
  const own = (key) => K.ownValue(key, c.key) !== undefined;
  const row = (key) => {
    const k = K.KNOB[key], id = `${key}@${c.key}`, mine = own(key);
    const ctl = wire[key].per_command && wire[key].editable !== false
      ? `${K.ctlHtml(k, mine ? K.ownValue(key, c.key) : K.valueOf(k).v, c.key)}${K.saidHtml(id)}${mine ? K.backHtml(k, c.key) : ""}`
      : `<span class="fixed">${esc(K.word(k, K.valueOf(k).v))}</span>`;
    return `<div class="lbl" data-row="${key}">${esc(k.label)}</div><div class="ctl"><div class="line">${ctl}</div></div>`;
  };
  const sorted = [...keys.filter(own), ...keys.filter((key) => !own(key))];
  return `<details class="rawlog everyset kn" open><summary>Every setting</summary><div class="effgrid">${sorted.map(row).join("")}</div></details>`;
}
/** What the command's gate holds right now and what has failed on it, as GET /api/heads serves them. */
function nowRow(c) {
  const h = data.heads.find((x) => x.key === c.key);
  if (!h?.gate) return "";
  const g = h.gate, e = h.health ?? {};
  const num = (n) => Number(n).toLocaleString("en-US");
  const max = g.max === "unlimited" ? "no limit" : `at most ${num(g.max)}`;
  const errs = [[e.localOriginErrors, "in splice"], [e.providerErrors, "from the provider"]].filter(([n]) => n > 0)
    .map(([n, w]) => `${num(n)} ${n === 1 ? "error" : "errors"} ${w}`);
  return `<span class="lbl">Right now</span><div class="ctl"><span class="fig">${num(g.inflight ?? 0)} in flight, ${max}</span>`
    + `<span class="fig">${num(g.queued ?? 0)} waiting</span>${errs.map((x) => `<span class="fig limit">${x}</span>`).join("") || '<span class="fig">No errors</span>'}</div>`;
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
function whereHtml(c, d, s) {
  return `<span class="lbl">Port</span><div class="ctl"><input class="field num${dirt(d.port !== fieldsOf(c).port)}" id="h-port" value="${esc(d.port)}" inputmode="numeric" spellcheck="false" autocomplete="off" aria-label="Port">${bad(s.port, "Not a port")}<span class="note">Where Claude Code reaches this command on this computer; each command needs its own</span></div>`
    + `<span class="lbl">Model prefix</span><div class="ctl"><input class="field mono prefix${dirt(d.prefix !== fieldsOf(c).prefix)}" id="h-prefix" value="${esc(d.prefix)}" spellcheck="false" autocomplete="off" aria-label="Model prefix">${bad(s.prefix, "Needs a prefix")}<span class="note">Goes before each model's name in Claude Code's model picker, so this command's models never clash with another's</span></div>`;
}
// the names of what a command can share with your own Claude Code, by the item splice.toml spells (claude.share, isolate)
const SHARE_NAME = { settings: "Settings", mcps: "MCP servers", skills: "Skills", hooks: "Hooks", agents: "Agents", commands: "Commands",
  plugins: "Plugins", claude_md: "CLAUDE.md", sessions: "Sessions", projects: "Transcripts" };
const shareName = (x) => SHARE_NAME[x] ?? x;
const kindName = (k) => (vocab().auth_kinds ?? []).find((x) => x.kind === k)?.label ?? k;
function signInHtml(c, d, s) {
  const kinds = vocab().auth_kinds ?? [];
  const items = kinds.map((x) => `<button role="menuitem" data-act="kind" data-v="${esc(x.kind)}"><span>${esc(x.label)}</span><span class="mmeta">${esc(x.kind)}</span></button>`).join("");
  const env = d.kind === "api-key"
    ? `<span class="lbl">Key variable</span><div class="ctl"><input class="field mono${dirt(d.env !== fieldsOf(c).env)}" id="h-env" value="${esc(d.env)}" placeholder="${esc(`${c.provKey.toUpperCase()}_API_KEY`)}" spellcheck="false" autocomplete="off" aria-label="Key variable">${bad(s.env, "Not a variable name")}</div>` : "";
  const rows = d.headers.map((x, i) => `<div class="hdr">${x.fresh ? `<input class="field mono" id="hn-${i}" data-hn="${i}" value="${esc(x.name)}" placeholder="Name" spellcheck="false" autocomplete="off" aria-label="Header name">` : `<span class="hname">${esc(x.name)}</span>`}`
    + `<input class="field mono${dirt(x.fresh || x.value !== MASKED)}" type="password" id="hv-${i}" data-hv="${i}" value="${x.value === MASKED ? "" : esc(x.value)}" placeholder="${x.value === MASKED ? "Kept" : "Value"}" autocomplete="off" aria-label="${esc(x.name || "Header")} value">`
    + `<button class="icon" data-act="hdr-rm" data-i="${i}" aria-label="Remove">${GL.close}</button>${bad(s.header[i], "Needs a name and a value")}</div>`).join("");
  return '<h3 class="gh">Sign-in</h3>'
    + `<span class="lbl">Signs in with</span><div class="ctl menuwrap"><button class="pickbtn" data-act="kindpick">${esc(kindName(d.kind))}${ICON.caret}</button>${ui.pick === "kind" ? `<div class="menu">${items}</div>` : ""}${sharedHtml(c)}</div>`
    + env
    + `<span class="lbl">Headers</span><div class="ctl col">${rows}<button class="act quiet" data-act="hdr-add">Add header</button></div>`;
}
// a model priced nowhere has no cache price either: once its row is priced, Cache read shows beside it, filled with the In
// price splice charges a cache read at until a real one is typed (marlin, Oct 13: overcount, but never hide it)
const unpriced = (c, id) => !c.models.find((m) => m.id === id)?.rates && !topology().heads?.[c.key]?.rates?.[id];
const cacheShown = (c, id, x) => unpriced(c, id) && Boolean(x.i || x.o || x.c);
function pricesHtml(c, d, s) {
  if (!c.models.length) return "";
  const was = fieldsOf(c).rates;
  const cell = (id, k) => `<td class="num"><input class="field price${dirt(d.rates[id][k] !== was[id][k])}" id="pr-${k}-${esc(id)}" data-rate="${esc(id)}" data-rk="${k}" value="${esc(d.rates[id][k])}" placeholder="No price" inputmode="decimal" spellcheck="false" autocomplete="off" aria-label="${k === "i" ? "In" : "Out"} per 1M tokens">${bad(s.rate[id][k], "Not a price")}</td>`;
  const shown = c.models.some((m) => cacheShown(c, m.id, d.rates[m.id]));
  const cache = (id) => (!shown ? "" : !cacheShown(c, id, d.rates[id]) ? '<td class="num"></td>'
    : `<td class="num"><input class="field price dirty" id="pr-c-${esc(id)}" data-rate="${esc(id)}" data-rk="c" value="${esc(d.rates[id].c ?? d.rates[id].i)}" inputmode="decimal" spellcheck="false" autocomplete="off" aria-label="Cached per 1M tokens">${bad(s.rate[id].c, "Not a price")}</td>`);
  const body = c.models.map((m) => `<tr><td class="mname">${esc(m.label)}</td>${cell(m.id, "i")}${cell(m.id, "o")}${cache(m.id)}</tr>`).join("");
  return '<h3 class="gh">Prices</h3>'
    + `<div class="ctl wide"><table class="every prices"><thead><tr><th>Model</th><th class="num">In per 1M</th><th class="num">Out per 1M</th>${shown ? '<th class="num">Cached per 1M</th>' : ""}</tr></thead><tbody>${body}</tbody></table></div>`;
}
// the items a command shares with your own Claude Code: every item splice shares, each one this command keeps its own of unpressed
function runningHtml(c, d, s) {
  const local = cmdDialect(c) === "openai-chat" ? (() => {
    const on = d.local ?? autoLocal(c);
    return `<span class="lbl">Runs on this computer</span><div class="ctl"><div class="switch" role="group" aria-label="Runs on this computer">`
      + `<button data-act="local" data-v="yes" aria-pressed="${on}">Yes</button><button data-act="local" data-v="no" aria-pressed="${!on}">No</button></div>${sharedHtml(c)}</div>`;
  })() : "";
  const items = vocab().share ?? [];
  const share = items.length ? `<span class="lbl">Shares with Claude Code</span><div class="ctl"><div class="switch multi" role="group" aria-label="Shares with Claude Code">${items.map((x) => `<button data-act="share" data-v="${esc(x)}" aria-pressed="${!d.isolate.includes(x)}">${esc(shareName(x))}</button>`).join("")}</div></div>` : "";
  const was = fieldsOf(c).quirks;
  const quirks = quirksOf(c).map((q) => `<span class="lbl" title="${esc(q.name)}">${esc(quirkName(q.name))}</span><div class="ctl">${quirkCtl(q, d.quirks[q.name], JSON.stringify(was[q.name]) !== JSON.stringify(d.quirks[q.name]))}${QUIRK_UNIT[q.name] ? `<span class="unit">${QUIRK_UNIT[q.name]}</span>` : ""}${bad(s.quirk[q.name], "Not a value")}</div>`).join("");
  return '<h3 class="gh">Running</h3>' + local + share + quirks;
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
function quirkCtl(q, v, moved) {
  const opt = (val, word) => `<button data-act="quirk" data-q="${esc(q.name)}" data-v="${esc(val === undefined ? "" : JSON.stringify(val))}" aria-pressed="${JSON.stringify(v) === JSON.stringify(val)}">${esc(word)}</button>`;
  const sw = (opts) => `<div class="switch" role="group" aria-label="${esc(q.name)}">${opt(undefined, "Provider's own")}${opts}</div>`;
  if (q.kind === "bool") return sw(opt(true, "On") + opt(false, "Off"));
  if (q.words) return sw(q.words.map((w) => opt(w, w)).join(""));
  if (q.kind === "list") return `<input class="field mono${dirt(moved)}" id="q-${esc(q.name)}" data-ql="${esc(q.name)}" value="${esc((v ?? []).join(", "))}" placeholder="Provider's own" spellcheck="false" autocomplete="off" aria-label="${esc(q.name)}">`;
  if (q.kind === "table") return `<span class="fig mono">${v === undefined ? "Provider's own" : esc(JSON.stringify(v))}</span>`;
  return `<input class="field mono${q.kind === "int" ? " num" : ""}${dirt(moved)}" id="q-${esc(q.name)}" data-q="${esc(q.name)}" data-qk="${q.kind}" value="${esc(v ?? "")}" placeholder="Provider's own" spellcheck="false" autocomplete="off" aria-label="${esc(q.name)}">`;
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
function changesOf(c) {
  const a = draftOf(c), d = ui.draft, out = [];
  // a context window is the one field the running daemon re-reads (TopologyRoutes, V4-162); every other field here is
  // read when splice starts, so its change says Applies on restart before it is saved (spec 9)
  const add = (name, was, now, live = false) => { if (JSON.stringify(was) !== JSON.stringify(now)) out.push({ name, was: clip(was), now: clip(now), live }); };
  const label = (id) => c.models.find((m) => m.id === id)?.label ?? c.missing.find((m) => m.id === id)?.label ?? id ?? "None";
  add("Sends to", a.url, d.url.trim());
  add("Port", a.port, d.port);
  add("Model prefix", a.prefix, d.prefix.trim());
  add("Signs in with", kindName(a.kind), kindName(d.kind));
  add("Key variable", a.env || "Default", d.env || "Default");
  for (const x of a.headers) if (!d.headers.some((y) => y.name === x.name)) add(x.name, "Set", "Removed");
  for (const y of d.headers) {
    if (y.fresh) { if (y.name.trim()) add(y.name.trim(), "None", "Set"); continue; }
    if (y.value !== MASKED) add(y.name, "Set", "New value");
  }
  for (const t of TIERS) {
    add(`${tierName(t)} model`, label(a.tiers[t].model), label(d.tiers[t].model));
    if (d.tiers[t].model && a.tiers[t].model === d.tiers[t].model) add(`${tierName(t)} window`, a.tiers[t].win, d.tiers[t].win, true);
  }
  add("Window", a.headWin, d.headWin, true);
  add("System prompt", { append: "Append", replace: "Replace" }[a.mode] ?? a.mode, { append: "Append", replace: "Replace" }[d.mode] ?? d.mode);
  add("System prompt text", a.file || a.text, d.file || d.text);
  for (const root of new Set([...Object.keys(a.per), ...Object.keys(d.per)])) {
    add(`Prompt in ${nameOfRoot(root)}`, a.per[root] ? clip(a.per[root].file || a.per[root].text) || "Set" : "None", d.per[root] ? clip(d.per[root].file || d.per[root].text) || "Set" : "None");
  }
  const yn = (v) => (v ? "Yes" : "No");
  add("Runs on this computer", yn(a.local ?? autoLocal(c)), yn(d.local ?? autoLocal(c)));
  for (const x of vocab().share ?? []) add(`Shares ${shareName(x)}`, yn(!a.isolate.includes(x)), yn(!d.isolate.includes(x)));
  for (const m of c.models) {
    add(`${m.label} in`, a.rates[m.id].i || "No price", d.rates[m.id].i || "No price");
    add(`${m.label} out`, a.rates[m.id].o || "No price", d.rates[m.id].o || "No price");
    if (cacheShown(c, m.id, d.rates[m.id])) add(`${m.label} cached`, "No price", d.rates[m.id].c ?? d.rates[m.id].i);
  }
  const qv = (v) => (v === undefined ? "Provider's own" : Array.isArray(v) ? v.join(", ") : String(v));
  for (const q of quirksOf(c)) add(quirkName(q.name), qv(a.quirks[q.name]), qv(d.quirks[q.name]));
  return out;
}
function saveRowHtml(c, st, saidHtml, restart) {
  const list = st.same ? [] : changesOf(c), n = list.length;
  const rows = list.map((x) => `<li><span class="cname">${esc(x.name)}</span><span class="was">${esc(x.was)}</span><span class="now">${esc(x.now)}</span><span class="when">${x.live ? "Live" : "Applies on restart"}</span></li>`).join("");
  const acts = n ? `<button class="act primary" data-act="save"${st.ok && !ui.saving ? "" : " disabled"}>Save ${n} ${n === 1 ? "change" : "changes"}</button><button class="act quiet" data-act="discard">Discard</button>` : "";
  return `${n ? `<ul class="changes">${rows}</ul>` : ""}<div class="foot">${acts}${saidHtml}${restart}</div>`;
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
function applyOwn(t, c, d) {
  const a = fieldsOf(c), head = t.heads[c.key], prov = t.providers[c.provKey];
  if (d.port !== a.port && portOk(d.port)) head.port = Number(d.port);
  if (d.prefix.trim() !== a.prefix) head.discovery_prefix = d.prefix.trim();
  if (JSON.stringify(d.isolate) !== JSON.stringify(a.isolate)) {
    head.claude ??= {};
    if (d.isolate.length) head.claude.isolate = [...d.isolate]; else delete head.claude.isolate;
    if (!Object.keys(head.claude).length) delete head.claude;
  }
  for (const m of c.models) {
    const x = d.rates[m.id], w = a.rates[m.id];
    if (x.i === w.i && x.o === w.o && x.c === w.c) continue;
    head.rates ??= {};
    head.rates[m.id] = cardOf(head, m, x.i, x.o, x.c);
  }
  if (!prov) return;
  if (d.kind !== a.kind || d.env !== a.env) {
    prov.auth = { ...prov.auth, kind: d.kind };
    if (d.env && d.kind === "api-key") prov.auth.env = d.env; else delete prov.auth.env;
  }
  if (JSON.stringify(d.headers) !== JSON.stringify(a.headers)) {
    const kept = d.headers.filter((x) => x.name.trim());
    if (kept.length) prov.extra_headers = Object.fromEntries(kept.map((x) => [x.name.trim(), x.value])); else delete prov.extra_headers;
  }
  if (d.local !== a.local) { if (d.local === null) delete prov.local; else prov.local = d.local; }
  if (JSON.stringify(d.quirks) !== JSON.stringify(a.quirks)) {
    const q = Object.fromEntries(Object.entries(d.quirks).filter(([, v]) => v !== undefined));
    if (Object.keys(q).length) prov.quirks = q; else delete prov.quirks;
  }
}

// ---------- Save: one PUT of the topology, the way Settings writes it ----------
function applyDraft(tree, c, d) {
  const t = JSON.parse(JSON.stringify(tree));
  const head = t.heads[c.key];
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
  // windows: the provider's own row for that model, which every command on the provider shares
  const prov = t.providers[c.provKey];
  // the provider's address, which every command on the provider shares too (spec 9d)
  if (prov && d.url !== c.url && urlOk(d.url)) prov.base_url = d.url.trim();
  for (const tier of TIERS) {
    const sel = d.tiers[tier], w = parseWindow(sel.win);
    const row = prov?.models?.find((m) => m.id === sel.model);
    if (row && w) row.context_window = w;
  }
  // no tier declared: the one window is the head's own, which the head's models all follow
  if (!TIERS.some((tier) => d.tiers[tier].model)) {
    const w = parseWindow(d.headWin);
    if (w && w !== parseWindow(draftOf(c).headWin)) head.context_window = w;
  }
  applyOwn(t, c, d);
  // the head's prompt
  const set = (o, p) => {
    delete o.system_prompt; delete o.system_prompt_file; delete o.system_prompt_mode;
    if (p.file) o.system_prompt_file = p.file; else if (p.text) o.system_prompt = p.text;
    if ((p.file || p.text || p.mode !== "append") && p.mode) o.system_prompt_mode = p.mode;
  };
  set(head, d);
  // the project layers for this command
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
  return t;
}
async function save() {
  const c = cmdByKey(ui.open), d = ui.draft;
  ui.saving = true; ui.said = null; render();
  const next = applyDraft(topology(), c, d);
  const was = JSON.stringify(topology().heads[c.key]) + JSON.stringify(topology().projects ?? {});
  const res = await API.put("/api/topology", { topology: next });
  ui.saving = false;
  if (!res.ok || !res.body?.ok) {
    // the daemon's sentences are for the log; the page says a config finding in fin's word for it, never the sentence
    const found = res.body?.findings ?? [];
    if (found.length) console.warn(`models: saving ${c.key} refused: ${found.map((x) => x.message).join("; ")}`);
    ui.said = found.length ? "Config error" : "Not saved";
    render();
    return;
  }
  ui.draft = null;
  ui.pending = Boolean(res.body.restart_required);
  ui.said = ui.pending ? "Saved" : "Applied";
  await readAll();
  render();
}
/** Restart splice, or, armed, the sessions it would cut with the act and the way out (kit.js's midRequest). */
const restartBtn = () => (ui.cut
  ? armedAsk("Mid-request", ui.cut, '<button class="act danger" data-act="restart-now">Restart splice</button>',
    '<button class="act quiet" data-act="keep-running">Keep running</button>')
  : '<button class="act" data-act="restart-splice">Restart splice</button>');
async function pressRestart() {
  const cut = await midRequest(data.heads.map((h) => h.key));
  if (!cut.length) { restartSplice(); return; }
  ui.cut = cut; render();
}
async function restartSplice() {
  ui.said = "Restarting"; render();
  const res = await API.post("/api/daemon/restart");
  if (!res.ok) { ui.said = "Not restarted"; ui.draft = null; render(); return; }
  for (let i = 0; i < 90; i++) {
    await new Promise((r) => setTimeout(r, 1000));
    const up = await API.get("/api/heads");
    if (!up.ok) continue;
    ui.pending = false; ui.restartDue = false; ui.gone.clear(); ui.said = "Applied"; ui.draft = null; await readAll();
    // an add that was saved while splice did not come back opens its command's pane once it is listed
    const added = ui.add?.saved && commandsOf().find((c) => c.cmd === ui.add.savedCmd);
    if (added) { ui.add = null; ui.open = added.key; }
    render(); return;
  }
  ui.said = "Not restarted"; render();
}

// a declared model the provider does not serve, in the route's own words (ModelsRoute.unresolvedReason)
const notServed = (m) => `<span class="said limit">Not served${m.reason ? `: ${esc(m.reason)}` : ""}</span>`;

// ---------- Every model: each command's roster with window and price ----------
function everyHtml() {
  const q = ui.q.trim().toLowerCase();
  const groups = commandsOf().map((c) => {
    const hit = (m) => !q || m.label.toLowerCase().includes(q) || m.id.toLowerCase().includes(q);
    const rows = c.models.filter(hit), gone = c.missing.filter(hit);
    if (!rows.length && !gone.length) return "";
    // each group is one command, already named by its chip: its main model leads, marked in the command's colour at the
    // row's left edge, with no second chip (spec 9h; marlin, Oct 12)
    const main = rows.filter((x) => x.id === c.pinned);
    const body = [...main, ...rows.filter((x) => x.id !== c.pinned)].map((x) => {
      const r = x.rates;
      const price = r ? `<td class="num">${usd(r.input ?? r.i)}</td><td class="num">${usd(r.output ?? r.o)}</td>` : `<td class="num unpriced" colspan="2">No price</td>`;
      const lc = r?.long_context ? `<tr class="tier"><td></td><td class="num">${big(r.long_context.over_input_tokens)}+</td><td class="num">${usd(r.long_context.input)}</td><td class="num">${usd(r.long_context.output)}</td></tr>` : "";
      return `<tr${x.id === c.pinned ? ' class="main"' : ""}><td class="mname">${esc(x.label)}</td><td class="num">${x.window ? big(x.window) : `<span class="nr">Not reported</span>`}</td>${price}</tr>${lc}`;
    }).join("") + gone.map((x) => `<tr><td class="mname">${esc(x.label)}</td><td class="unpriced" colspan="3">${notServed(x)}</td></tr>`).join("");
    return `<tbody style="--c:${colorOf(c.family)}"><tr class="grp"><td colspan="4"><span class="chip">${esc(c.cmd)}</span><span class="prov">${esc(c.prov)}</span></td></tr>${body}</tbody>`;
  }).join("");
  const table = groups
    ? `<table class="every"><thead><tr><th rowspan="2">Model</th><th rowspan="2" class="num">Window</th><th colspan="2" class="num per-m">Per 1M tokens</th></tr><tr><th class="num">In</th><th class="num">Out</th></tr></thead>${groups}</table>`
    : `<div class="nomatch"><span class="state">No match</span><button class="act quiet" data-act="clearq">Clear</button></div>`;
  return `<div class="every-wrap"><div class="search">${G('<circle cx="11" cy="11" r="6.5"/><path d="M16 16l4.5 4.5"/>')}<input class="field" id="q" placeholder="Search" value="${esc(ui.q)}" autocomplete="off"></div>${table}</div>`;
}

// ---------- Projects: compaction's rules for this project and command, edited in place (spec 7 Resolved) ----------
// Four rules reach a compaction here, widest first: everywhere, the command's model, this project, this project with that
// model. Each is left to the rule above it (its entry absent), set to Claude Code's own instructions (an empty text, which
// opts out of every wider one), or given text, inline or from a file. The whole topology goes back through PUT
// /api/topology with only [compaction] changed; a compaction-only edit serves the next compaction with no restart.
const CX_MODES = { inherit: "Inherit", off: "Claude Code's", text: "Text", file: "File" };
// each choice says what it does here; a text or a file says only that it replaces the wider rule, the one fact its box
// cannot show, and on Everywhere, with no rule above, says nothing (fin, Oct 12)
const CX_HINT = { inherit: "Takes the rule above", off: "Claude Code compacts with its own instructions",
  text: "Replaces the rule above", file: "Replaces the rule above" };
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
  ui.comp = { root: p.root, head: c.key, model: c.mainId, rows, said: null, saving: false };
}
function compEditHtml(p, c) {
  const cx = ui.comp, model = c.models.find((m) => m.id === cx.model)?.label ?? cx.model;
  const chip = `<span class="chip" style="--c:${colorOf(c.family)}">${esc(model ?? "")}</span>`;
  const who = { g: `<span>Everywhere</span>`, m: chip, p: `<span class="pname">${esc(p.name)}</span>`, pm: `<span class="pname">${esc(p.name)}</span>${chip}` };
  const row = (k) => {
    const st = cx.rows[k], modes = k === "g" ? ["off", "text", "file"] : ["inherit", "off", "text", "file"];
    const sw = `<div class="switch" role="group" aria-label="Compaction rule">${modes.map((v) => `<button data-act="cmode" data-s="${k}" data-v="${v}" aria-pressed="${st.mode === v}">${CX_MODES[v]}</button>`).join("")}</div>`;
    const body = st.mode === "text" ? `<textarea class="field ptext" id="cx-${k}" data-cx="${k}" rows="3" aria-label="Compaction instructions">${esc(st.text)}</textarea>`
      : st.mode === "file" ? `<input class="field" id="cxf-${k}" data-cxf="${k}" value="${esc(st.file)}" placeholder="~/notes/compact.md" spellcheck="false" autocomplete="off" aria-label="Instructions file">`
      : "";
    return `<div class="crow"><div class="cwho">${who[k]}</div>${sw}${k === "g" && (st.mode === "text" || st.mode === "file") ? "" : `<span class="chint">${CX_HINT[st.mode]}</span>`}${body}</div>`;
  };
  const keys = cx.model ? ["g", "m", "p", "pm"] : ["g", "p"];
  const said = cx.said ? `<span class="said limit">${esc(cx.said)}</span>` : "";
  return `<div class="cedit">${keys.map(row).join("")}<div class="cfoot"><button class="act primary" data-act="csave"${cx.saving ? " disabled" : ""}>Save</button>`
    + `<button class="act quiet" data-act="ccancel">Cancel</button>${said}</div></div>`;
}
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
  if (Object.values(ui.comp.rows).some((st) => st.mode === "text" && !st.text.trim())) return "Write the text, or pick Claude Code's";
  if (Object.values(ui.comp.rows).some((st) => st.mode === "file" && !st.file.trim())) return "Name the file";
  const twice = (keys) => keys.some((k, i) => keys.indexOf(k) !== i);
  if (twice(table.model.map((x) => x.model)) || twice(table.project.map((x) => `${x.path}\n${x.model ?? ""}`))) return "splice.toml holds one rule twice; fix it there first";
  return null;
}
async function compSave() {
  const cx = ui.comp, t = structuredClone(topology());
  const table = compTable(t, cx);
  cx.said = compRefusal(table);
  if (cx.said) { render(); return; }
  t.compaction = table;
  cx.saving = true; render();
  const res = await API.put("/api/topology", { topology: t });
  cx.saving = false;
  if (!res.ok || !res.body?.ok) {
    const found = res.body?.findings ?? [];
    if (found.length) console.warn(`models: saving compaction refused: ${found.map((x) => x.message).join("; ")}`);
    cx.said = found.length ? "Config error" : "Not saved"; render(); return;
  }
  ui.comp = null; ui.files = {};
  ui.pending = Boolean(res.body.restart_required); ui.restartDue = ui.pending;
  await readAll(); render();
}

// ---------- Projects: files, the prompt stack, and where compaction reads ----------
async function loadProject(id) {
  if (ui.files[id]) return;
  const res = await API.get(`/api/projects/${encodeURIComponent(id)}/files`);
  ui.files[id] = res.ok ? res.body : { files: [], compaction: [] };
  render();
}
function projectsHtml() {
  const ps = data.projects;
  if (!ps.length) return `<div class="projects"><p class="none">${pending("projects") ? "Reading" : "No projects"}</p></div>`;
  const p = ps.find((x) => x.id === ui.proj) ?? ps[0];
  const cs = commandsOf();
  const c = cs.find((x) => x.key === ui.projCmd) ?? cs[0];
  loadProject(p.id);
  const list = ps.map((x) => `<button class="proj-tab" data-act="proj" data-p="${esc(x.id)}" aria-current="${x.id === p.id}"><span class="pname">${esc(x.name)}</span><span class="ppath" title="${esc(x.root ?? "")}"><bdo dir="ltr">${esc(x.place ?? homeRel(x.root))}</bdo></span></button>`).join("");
  if (!c) return `<div class="projects"><nav class="proj-list">${list}</nav><p class="none">No commands</p></div>`;
  const got = ui.files[p.id] ?? { files: [], compaction: [] };
  const mine = got.files.filter((f) => !f.head || f.head === c.key);
  const tile = (f) => {
    const name = f.path.split("/").pop(), id = `${f.kind}:${f.path}`;
    return `<button class="ftile" data-act="file" data-f="${esc(id)}" aria-expanded="${ui.file === id}">${GL.file}<span>${esc(name)}</span></button>`;
  };
  const kind = (k, title) => {
    const fs = mine.filter((f) => f.kind === k);
    return `<h3>${title}</h3>${fs.length ? `<div class="ftiles">${fs.map(tile).join("")}</div>` : `<p class="none">No files</p>`}`;
  };
  const open = mine.find((f) => `${f.kind}:${f.path}` === ui.file);
  const files = `<section class="pblock">${kind("instructions", "Instructions")}${kind("memory", "Memory")}${open ? `<pre class="ftext">${esc(open.text)}</pre>` : ""}</section>`;
  const chips = `<div class="cmdpick"><span class="clabel">As run by</span>${cs.map((x) => `<button class="chip${x.key === c.key ? " on" : ""}" style="--c:${colorOf(x.family)}" data-act="projcmd" data-h="${esc(x.key)}" aria-pressed="${x.key === c.key}">${esc(x.cmd)}</button>`).join("")}</div>`;
  const projTopo = (topology().projects ?? {})[p.root] ?? {};
  const layers = [
    { who: `<span class="chip" style="--c:${colorOf(c.family)}">${esc(c.cmd)}</span>`, ...c.prompt },
    { who: `<span class="pname">${esc(p.name)}</span>`, mode: projTopo.system_prompt_mode ?? "append", text: projTopo.system_prompt ?? "", file: projTopo.system_prompt_file ?? "" },
    { who: `<span class="pname">${esc(p.name)}</span><span class="chip" style="--c:${colorOf(c.family)}">${esc(c.cmd)}</span>`, ...(perOf(c)[p.root] ?? { mode: "append", text: "", file: "" }) },
  ].filter((l) => l.text || l.file || l.mode !== "append");
  const lastReplace = layers.map((l) => l.mode).lastIndexOf("replace");
  const stack = layers.length
    ? `<ol class="stack">${layers.map((l, i) => `<li class="${i < lastReplace ? "dim" : ""}"><div class="lwho">${l.who}<span class="mode">${tierName(l.mode)}</span></div>${l.file ? pfile(l.file) : `<p>${esc(l.text)}</p>`}</li>`).join("")}</ol>`
    : `<p class="none">Claude Code's own</p>`;
  const rows = p.compaction ?? []; // the project row carries it; the files route never did (marlin, Oct 12)
  const lit = rows.findIndex((r) => r.source);
  const ladder = rows.length
    ? `<ol class="ladder">${rows.map((r, i) => `<li class="${i === lit ? "lit" : ""}"><span class="rung"></span><span class="rname">${esc(r.scope)}</span>${i === lit ? `<span class="rfile">${esc(r.source)}</span>${r.chars === 0 ? `<span class="rempty">Empty</span>` : ""}` : ""}</li>`).join("")}</ol>`
    : `<p class="none">Claude Code's own</p>`;
  const comp = ui.comp && ui.comp.root === p.root && ui.comp.head === c.key ? compEditHtml(p, c) : `${ladder}<button class="act quiet small cedit-open" data-act="cedit">Edit</button>`;
  const bar = c.window ? `<div class="winbar" style="--c:${colorOf(c.family)}"><div class="bar"><span class="tick" style="left:85%"></span></div><span class="wlabel">${big(c.window)}</span></div>` : "";
  const right = `<div class="proj"><header><h2>${esc(p.name)}</h2><span class="ppath" title="${esc(p.root ?? "")}"><bdo dir="ltr">${esc(p.place ?? homeRel(p.root))}</bdo></span></header>${files}`
    + `<section class="pblock">${chips}<div class="twin"><div><h3>System prompt</h3>${stack}</div><div><h3>Compaction</h3>${comp}${ui.comp ? "" : bar}</div></div></section></div>`;
  return `<div class="projects"><nav class="proj-list">${list}</nav>${right}</div>`;
}

// ---------- claude: plain `claude`, through splice or direct ----------
function claudeHtml() {
  const m = data.claude?.mode;
  if (!data.claude) return ui.restartDue ? restartBtn() : "";
  const through = m === "wrapped";
  const can = Boolean(data.claude.shim_path);
  const due = ui.restartDue ? restartBtn() : "";
  const said = ui.claudeSaid ? `<span class="said limit">${esc(ui.claudeSaid)}</span>` : "";
  const to = data.claude.resolves_to;
  const runs = to ? `<span class="said" title="${esc(to)}">Runs <bdo dir="ltr">${esc(homeRel(to))}</bdo></span>` : `<span class="said limit">No claude on your PATH</span>`;
  return `<span class="clabel">Your claude command</span><div class="switch" role="group">`
    + `<button data-act="claude" data-v="wrap" aria-pressed="${through}"${can ? "" : " disabled"}>Through splice</button>`
    + `<button data-act="claude" data-v="unwrap" aria-pressed="${!through}"${can ? "" : " disabled"}>Straight to Anthropic</button></div>${runs}${said}${due}`;
}
async function setClaude(v) {
  const res = await API.post(`/api/claude-head/${v}`);
  ui.claudeSaid = res.ok ? null : "Not switched";
  const again = await API.get("/api/claude-head");
  if (again.ok) data.claude = again.body;
  render();
}
function viewsHtml() {
  return `<div class="views switch" role="tablist">${[["commands", "Commands"], ["every", "Every model"], ["projects", "Projects"]].map(([v, w]) =>
    `<button role="tab" data-act="view" data-v="${v}" aria-pressed="${ui.view === v}">${w}</button>`).join("")}</div>`;
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
function addPaneHtml() {
  const a = ui.add;
  const p = a.profile && data.profiles.find((x) => x.name === a.profile);
  const head = `<header style="--c:${p ? colorOf(famOf(p)) : "var(--key-light)"}"><div class="who"><div class="top"><span class="name">New command</span></div></div>`
    + `<div class="acts"><button class="icon close" data-act="cancel-add" aria-label="Close">${GL.close}</button></div></header>`;
  if (!p) {
    const tiles = data.profiles.map((x) => {
      const fam = famOf(x), off = taken(x);
      return `<button class="pick tpl" style="--c:${colorOf(fam)}" data-act="tpl" data-t="${esc(x.name)}"><span class="blot"></span><span class="tname">${esc(tplName(x))}</span>`
        + `${off ? `<span class="said">Added</span>` : ""}${x.command ? `<span class="chip">${esc(x.command)}</span>` : ""}</button>`;
    }).join("");
    return `<div class="pane add-pane">${head}<div class="body"><div class="tpls">${tiles}</div></div></div>`;
  }
  const asks = (k) => p.asks.includes(k);
  const field = (id, lbl, val, extra = "") => `<label class="lbl" for="${id}">${lbl}</label><div class="ctl"><input class="field${id === "a-url" ? " url" : ""}" id="${id}" value="${esc(val)}" spellcheck="false" autocomplete="off" ${extra}></div>`;
  let f = "";
  if (asks("name") || second(p)) f += field("a-name", "Name", a.name);
  if (showsUrl(p, a)) f += field("a-url", "URL", a.url);
  if (asksWhichKey(p, a)) {
    f += `<span class="lbl">Key</span><div class="ctl"><div class="switch" role="radiogroup" aria-label="Key">`
      + `<button data-act="keychoice" data-v="provider" role="radio" aria-pressed="${!a.newKey}" aria-checked="${!a.newKey}">${esc(tplName(p))}'s key</button>`
      + `<button data-act="keychoice" data-v="new" role="radio" aria-pressed="${Boolean(a.newKey)}" aria-checked="${Boolean(a.newKey)}">A new key</button></div></div>`;
  }
  if (!sharesKey(p, a) && (p.requires_key || (p.auth_kind === "api-key" && p.asks.length))) {
    f += field("a-key", asksWhichKey(p, a) ? "" : "Key", a.key, 'type="password" aria-label="Key"');
  }
  if (asks("models")) {
    const rows = a.rows.map((r, i) => `<div class="mrow"><input class="field" id="a-m${i}" data-i="${i}" data-f="id" value="${esc(r.id)}" spellcheck="false" autocomplete="off" aria-label="Model">`
      + `<input class="field win" id="a-w${i}" data-i="${i}" data-f="win" value="${esc(r.win)}" spellcheck="false" autocomplete="off" aria-label="Window"></div>`).join("");
    f += `<span class="lbl"></span><div class="ctl col"><div class="mrow labels"><span>Model</span><span>Window</span></div>${rows}<button class="act quiet" data-act="addrow">Add model</button></div>`;
  }
  const fam = famOf(p);
  const cmd = commandFor(p, a);
  const headRow = `<div class="tplhead"><span class="blot"></span><span class="tname">${esc(tplName(p))}</span>${cmd ? `<span class="chip">${esc(cmd)}</span>` : ""}</div>`;
  const checks = a.checks ? `<ol class="checks">${a.checks.map((c) => {
    const soft = !c.ok && SOFT.has(c.name);
    const cls = c.ok ? "ready" : soft ? "unchecked" : "stalled";
    const mark = c.ok ? "<i></i>" : soft ? "<i></i>" : GL.notstarted;
    const word = c.ok ? "" : `<span class="said${soft ? "" : " limit"}">${esc(failWordOf(c, p))}</span>`;
    return `<li class="${c.ok ? "pass" : "fail"}"><span class="lamp ${cls}">${mark}</span><span class="cname">${esc(c.name === "credential" && /oauth|login|sign/i.test(p.auth_kind) ? "Sign-in" : CHECK_NAME[c.name] ?? c.name)}</span>${word}</li>`;
  }).join("")}</ol>` : "";
  const sg = a.sign;
  const sign = sg ? `<div class="outbox"><span class="waitbox" role="status">${ICON.wait}<span class="sr">Signing in</span></span>`
    + `${sg.code ? `<button class="usercode" data-act="copy-code">${esc(sg.code)}</button><span class="code-hint">Enter this code on the sign-in page</span>` : ""}`
    + `${sg.url ? `<button class="act" data-act="open-signin">Open sign-in</button>` : ""}<button class="act quiet" data-act="cancel-signin">Cancel</button></div>` : "";
  const said = a.said ? `<span class="said limit">${esc(a.said)}</span>` : "";
  const ready = ((!asks("name") && !second(p)) || a.name.trim()) && (!showsUrl(p, a) || a.url.trim()) && (!asks("models") || a.rows.some((r) => r.id.trim()));
  // saved: splice restarts to start the command, and the pane says so until the restarted daemon lists it and its own
  // pane opens (marlin's desk A look, Oct 12: "Adding" sat still through the restart, and only a reload showed the pane)
  const foot = a.stalled
    ? `<div class="foot">${restartBtn()}<span class="said limit">Saved; splice did not come back with it</span></div>`
    : a.saved
    ? `<div class="foot"><span class="waitbox" role="status">${ICON.wait}<span class="sr">Restarting</span></span><span class="said">Saved. Restarting splice to start ${esc(a.savedCmd || "it")}</span></div>`
    : `<div class="foot"><button class="act primary" data-act="add"${ready && !a.busy ? "" : " disabled"}>${a.busy ? "Adding" : "Add"}</button><button class="act quiet" data-act="cancel-add">Cancel</button>${said}</div>`;
  return `<div class="pane add-pane" style="--c:${colorOf(fam)}">${head}<div class="body">${headRow}${f ? `<section class="form">${f}</section>` : ""}${checks}${sign}${foot}</div></div>`;
}
const CHECK_NAME = { credential: "Key", "base url": "URL", models: "Models", windows: "Windows", "live turn": "Live request" };
function failWordOf(c, p) {
  const d = c.detail ?? "", http = /HTTP (\d{3})/.exec(d);
  console.warn(`add check ${c.name}: ${d}`);
  if (c.name === "credential") return /oauth|login|sign/i.test(p.auth_kind) ? "Signed out" : "No key";
  if (c.name === "base url") { const at = /\bat (\S+)$/.exec(d)?.[1]; return at ? `No answer at ${at}` : "No answer"; }
  if (c.name === "models") return http ? `HTTP ${http[1]}` : /did not answer with a model list|not a list/i.test(d) ? "No list" : /not listed|missing/i.test(d) ? "Not listed" : "No answer";
  if (c.name === "windows") return "Too large";
  return http ? `HTTP ${http[1]}` : "No answer";
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
  if (!res.ok) { a.busy = false; a.said = failWord(res); render(); return; }
  a.id = res.body.id; a.env = res.body.key_env;
  if (res.body.sign_in_by === "key" && a.key.trim() && !sharesKey(p, a)) {
    const put = await API.put(`/api/keys/${encodeURIComponent(res.body.key_env)}`, { value: a.key.trim() });
    if (!put.ok) { a.busy = false; a.said = "Key not saved"; render(); return; }
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
function notAddedWhy(a, prof) {
  const failed = a.checks.find((c) => !c.ok && !SOFT.has(c.name)) ?? a.checks.find((c) => !c.ok);
  if (!failed) return "Not added";
  if (failed.name === "base url") {
    a.urlFailed = true;
    if (!a.url.trim()) a.url = /\bat (\S+)$/.exec(failed.detail ?? "")?.[1] ?? prof.base_url ?? "";
    return "Not added until the URL answers";
  }
  if (failed.name === "credential") return /oauth|login|sign/i.test(prof.auth_kind) ? "Not added until signed in" : "Not added until the key works";
  return `Not added until ${CHECK_NAME[failed.name] ?? failed.name} passes`;
}
async function verifyAndSave() {
  const a = ui.add;
  const v = await API.post(`/api/add/${encodeURIComponent(a.id)}/verify`, {});
  a.checks = v.body?.checks ?? [];
  // A press never ends in nothing: a check that stops the add shows beside its own name, and an answer with no checks says so.
  const hard = a.checks.some((c) => !c.ok && !SOFT.has(c.name));
  if (!v.ok && !a.checks.length) console.warn(`add verify: ${v.body?.error ?? v.status}`);
  if (hard || (!v.ok && !a.checks.length)) { a.busy = false; a.said = hard ? notAddedWhy(a, profileOf(a)) : "Not added"; render(); return; }
  render();
  const s = await API.post(`/api/add/${encodeURIComponent(a.id)}/save`, {});
  // the save checks again and refuses any check that fails, a model list it could not read included: its 409 carries the
  // rows, so the form shows them and says which one it waits on, never a bare "Not added"
  if (!s.ok) { if (Array.isArray(s.body?.checks)) a.checks = s.body.checks; a.busy = false; a.said = notAddedWhy(a, profileOf(a)); render(); return; }
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
    ui.add = null; ui.open = added.key; ui.draft = null; ui.added = { key: added.key, live: null }; render();
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
  ui.sign = { key, code: null, url: null };
  render();
  const res = await API.post(`/api/auth/${encodeURIComponent(key)}/login`, {});
  if (!res.ok || !res.body?.id) { ui.sign = null; ui.said = "Not completed"; render(); return; }
  const sg = ui.sign;
  for (let i = 0; i < 300 && ui.sign === sg; i++) {
    const st = (await API.get(`/api/auth/${encodeURIComponent(key)}/login/${encodeURIComponent(res.body.id)}`)).body ?? {};
    if (st.state === "failed") { ui.sign = null; ui.said = "Not completed"; render(); return; }
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
  render();
  const res = s.fix.id ? await API.post(`/api/doctor/fix/${encodeURIComponent(s.fix.id)}`, {}) : await API.post(`/api/heads/${encodeURIComponent(key)}/start`, {});
  delete ui.fixing[key];
  await readAll();
  render();
  if (!res.ok) { ui.said = "Not done"; render(); }
}

// ---------- the page ----------
const commandsHtml = () => {
  const tile = `<button class="add" data-act="new" aria-pressed="${Boolean(ui.add)}"><span class="plus">+</span>New command</button>`;
  const open = ui.open || ui.add;
  return `<div class="cmds${open ? " open" : ""}"><div class="list rail">${order(commandsOf()).map(cardHtml).join("")}${tile}</div>${open ? paneHtml() : ""}</div>`;
};
function render() {
  KNOBS.save?.();
  const f = document.activeElement, fid = f?.id, pos = f?.selectionStart;
  const body = root.querySelector(".pane .body"), top = body && root.dataset.shown === `${ui.view}:${ui.open}` ? body.scrollTop : 0;
  const rl = root.querySelector(".hlog .rawlog ol"), rawAt = rl && { top: rl.scrollTop, end: rl.scrollTop + rl.clientHeight >= rl.scrollHeight - 4 };
  document.getElementById("claude").innerHTML = data.loaded ? claudeHtml() : "";
  if (!data.loaded) {
    root.innerHTML = `<p class="none">${esc(data.err ?? "Reading")}</p>`;
    return;
  }
  root.innerHTML = viewsHtml() + (ui.view === "commands" ? commandsHtml() : ui.view === "every" ? everyHtml() : projectsHtml());
  root.dataset.shown = `${ui.view}:${ui.open}`;
  const now = root.querySelector(".pane .body");
  if (now) now.scrollTop = top;
  moreBelow();
  const rl2 = root.querySelector(".hlog .rawlog ol");
  if (rl2) rl2.scrollTop = !rawAt || rawAt.end ? rl2.scrollHeight : rawAt.top;
  if (fid) {
    const g = document.getElementById(fid);
    if (g) { g.focus(); if (pos != null && g.setSelectionRange) g.setSelectionRange(pos, pos); }
  }
}
// An open pane taller than the window says so on its lower edge, as Settings' does: "More below" while content waits
// under the fold, gone once the scroll reaches the end (splice-usage's walk at 3394, Oct 13: Running was cut in half)
function moreBelow() {
  const body = root.querySelector(".pane .body");
  if (body) body.parentElement.classList.toggle("more", body.scrollHeight - body.scrollTop - body.clientHeight > 6);
}
document.addEventListener("scroll", (e) => { if (e.target.classList?.contains("body")) moreBelow(); }, true);
window.addEventListener("resize", moreBelow);
function openCmd(key) {
  Object.assign(ui, { open: key, add: null, draft: null, said: null, pick: null });
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
  switch (d.act) {
    case "view": ui.view = d.v; ui.pick = null; if (ui.view === "commands" && !ui.wide) Object.assign(ui, { open: null, add: null, draft: null, said: null }); render(); break;
    case "fix": e.stopPropagation(); fix(d.h); break;
    case "hact": pressHeadAct(ui.open, d.v); break;
    case "hact-now": if (ui.headAct?.cut) runHeadAct(ui.headAct.key, ui.headAct.act); break;
    case "hact-keep": ui.headAct = null; render(); break;
    case "close": dropAdd(); ui.headAct = null; Object.assign(ui, { open: null, add: null, draft: null, said: null }); render(); break;
    case "new": Object.assign(ui, { open: null, draft: null, add: { profile: null, name: "", url: "", key: "", rows: [{ id: "", win: "" }], checks: null } }); render(); break;
    case "cancel-add": dropAdd(); ui.add = null; render(); break;
    case "tpl": { a.profile = d.t; const pr = data.profiles.find((x) => x.name === d.t); if (pr && second(pr)) a.url = twinUrl(pr); render(); break; }
    case "keychoice": a.newKey = d.v === "new"; render(); if (a.newKey) document.getElementById("a-key")?.focus(); break;
    case "addrow": a.rows.push({ id: "", win: "" }); render(); document.getElementById(`a-m${a.rows.length - 1}`)?.focus(); break;
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
    case "tierpick": ui.pick = ui.pick === `tier:${d.t}` ? null : `tier:${d.t}`; render(); break;
    case "tier": {
      const c = cmdByKey(ui.open), m = c.models.find((x) => x.id === d.v);
      const wasUnset = TIERS.every((x) => !ui.draft.tiers[x].model);
      // from the defaults, picking one tier declares the other three at the defaults they had: Save never turns them into a 400
      if (wasUnset && d.v) for (const x of TIERS) { const dm = c.models.find((y) => y.id === c.defaults[x]); ui.draft.tiers[x] = { model: dm?.id ?? null, win: dm ? big(dm.window) : "" }; }
      ui.draft.tiers[d.t] = { model: d.v || null, win: m ? big(m.window) : "" };
      if (!TIERS.some((x) => ui.draft.tiers[x].model)) ui.draft.headWin = draftOf(c).headWin;
      ui.pick = null; ui.said = null; render(); break;
    }
    case "mode": if (d.id === "head") ui.draft.mode = d.v; else ui.draft.per[d.id.slice(4)].mode = d.v; ui.said = null; render(); break;
    case "perpick": ui.pick = ui.pick === "per" ? null : "per"; render(); break;
    case "per": ui.draft.per[d.p] = { mode: "append", text: "", file: "" }; ui.pick = null; ui.said = null; render(); break;
    case "unper": delete ui.draft.per[d.p]; ui.said = null; render(); break;
    case "save": save(); break;
    case "discard": ui.draft = draftOf(cmdByKey(ui.open)); ui.said = null; ui.pick = null; render(); break;
    case "kindpick": ui.pick = ui.pick === "kind" ? null : "kind"; render(); break;
    case "kind": ui.draft.kind = d.v; ui.pick = null; ui.said = null; render(); break;
    case "hdr-add": ui.draft.headers.push({ name: "", value: "", fresh: true }); render(); document.getElementById(`hn-${ui.draft.headers.length - 1}`)?.focus(); break;
    case "hdr-rm": ui.draft.headers.splice(+d.i, 1); ui.said = null; render(); break;
    case "local": ui.draft.local = localPick(cmdByKey(ui.open), d.v === "yes"); ui.said = null; render(); break;
    case "share": { const iso = ui.draft.isolate, i = iso.indexOf(d.v); if (i < 0) iso.push(d.v); else iso.splice(i, 1); ui.said = null; render(); break; }
    case "quirk": ui.draft.quirks[d.q] = d.v === "" ? undefined : JSON.parse(d.v); ui.said = null; render(); break;
    case "restart-splice": pressRestart(); break;
    case "restart-now": ui.cut = null; restartSplice(); break;
    case "keep-running": ui.cut = null; render(); break;
    case "clearq": ui.q = ""; render(); break;
    case "logpause": ui.logPaused = !ui.logPaused; if (!ui.logPaused && ui.open) readLog(ui.open); else render(); break;
    case "proj": ui.proj = d.p; ui.file = null; ui.comp = null; render(); break;
    case "projcmd": ui.projCmd = d.h; ui.comp = null; render(); break;
    case "cedit": { const p = data.projects.find((x) => x.id === ui.proj) ?? data.projects[0], c = cmdByKey(ui.projCmd) ?? commandsOf()[0]; if (p && c) openComp(p, c); render(); break; }
    case "cmode": ui.comp.rows[d.s].mode = d.v; ui.comp.said = null; render(); break;
    case "csave": compSave(); break;
    case "ccancel": ui.comp = null; render(); break;
    case "file": ui.file = ui.file === d.f ? null : d.f; render(); break;
    case "claude": setClaude(d.v); break;
    default: break;
  }
}
function onInput(e) {
  const t = e.target, a = ui.add;
  if (t.id === "q") { ui.q = t.value; render(); return; }
  if (t.id === "logq") { ui.logq = t.value; render(); return; }
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
    ui.comp.said = null; render(); return;
  }
  if (!ui.draft) return;
  if (t.dataset.hw) { ui.draft.headWin = t.value; ui.said = null; render(); }
  else if (t.dataset.t) { ui.draft.tiers[t.dataset.t].win = t.value; ui.said = null; render(); }
  else if (t.id === "h-text") { ui.draft.text = t.value; ui.said = null; render(); }
  else if (t.id === "h-url") { ui.draft.url = t.value; ui.said = null; render(); }
  else if (t.dataset.ptext) { ui.draft.per[t.dataset.ptext].text = t.value; ui.said = null; render(); }
  else if (ownInput(t, ui.draft)) { ui.said = null; render(); }
}
root.addEventListener("click", onClick);
root.addEventListener("input", onInput);
root.addEventListener("toggle", (e) => {
  const t = e.target;
  if (t.classList?.contains("everyset")) { if (t.open !== ui.effOpen) { ui.effOpen = t.open; render(); } }
  else if (t.classList?.contains("rawlog")) ui.rawOpen = t.open;
}, true);
document.getElementById("claude").addEventListener("click", onClick);
document.querySelector('.foot [data-act="theme"]')?.addEventListener("click", toggleTheme);

async function init() {
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
  const profiles = API.get("/api/add/profiles").then((r) => { data.profiles = (r.body?.profiles ?? []).filter((p) => p.name !== "nvidia"); });
  await readAll({ draw: () => { openFirst(); render(); } });
  await profiles;
  openFirst();
  render();
  if (location.hash === "#log") document.querySelector(".hlog")?.scrollIntoView({ block: "center", behavior: "instant" });
  if (setting) document.querySelector(`.everyset [data-row="${CSS.escape(setting)}"]`)?.scrollIntoView({ block: "center", behavior: "instant" });
  // a locked console (api.js set body.locked on a 401) sends nothing until it is unlocked (gap 386b)
  const locked = () => document.body.classList.contains("locked");
  setInterval(() => { if (ui.open && !ui.add && !ui.logPaused && !document.hidden && !locked()) readLog(ui.open); }, 5000);
  setInterval(async () => { if (!locked() && !ui.draft && !ui.add && !ui.comp && !ui.saving && !ui.keyin && !ui.sign && !Object.keys(ui.fixing).length) { await readAll(); render(); } }, 5000);
}
init();

});
