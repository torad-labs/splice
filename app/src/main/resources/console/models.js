// Models: every command he runs, what it serves and how it is doing, every model on every plan and key with its window
// and price, and what applies inside a project. Create a command from a template or a custom endpoint, change its model,
// window and system prompt, run the fix splice offers, and choose whether plain `claude` runs through splice.
// BUILT AS DRAWN (Splice-Animator/design/console/models.html), on the daemon's own routes: /api/heads, /api/topology,
// /api/models, /api/projects, /api/add, /api/doctor, /api/logs, /api/claude-head. Where the drawing and the daemon
// disagree on a fact the daemon wins, and the element shows only where the fact exists. Rename is out of 0.4.0, and
// Remove is not on this page yet.
"use strict";

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
const data = { heads: [], topo: null, models: [], local: [], projects: [], sessions: [], doctor: null, claude: null, accounts: [], profiles: [], loaded: false, err: null };
// What he is doing: the open command and its unsaved draft, a new command in progress, the saved words.
const ui = { rm: null, gone: new Set(), restartDue: false, sign: null, keyin: null, view: "commands", open: null, add: null, draft: null, said: null, pick: null, saving: false, restart: null,
  logs: {}, proj: null, projCmd: null, file: null, files: {}, more: new Set(), q: "", claudeSaid: null, fixing: {}, wide: true };

async function readAll() {
  const paths = ["/api/heads", "/api/topology", "/api/models", "/api/projects", "/api/sessions", "/api/claude-head", "/api/accounts"];
  const [heads, topo, models, projects, sessions, claude, accounts] = await Promise.all(paths.map((p) => API.get(p)));
  if (!heads.ok || !topo.ok) {
    if (heads.status !== 0) console.warn(`models: /api/heads answered ${heads.status}`);
    data.err = heads.status === 0 ? "Not answering" : "Not read";
    return;
  }
  data.err = null;
  data.heads = heads.body?.heads ?? [];
  data.topo = topo.body;
  data.models = models.body?.heads ?? [];
  data.projects = projects.body?.projects ?? [];
  data.sessions = sessions.body?.sessions ?? [];
  data.claude = claude.ok ? claude.body : null;
  data.accounts = accounts.body?.accounts ?? [];
  data.loaded = true;
  const doctor = await API.get("/api/doctor");
  data.doctor = doctor.ok ? doctor.body : null;
  // what each local runtime says about its models, asked of the runtimes themselves (spec 9i)
  const local = await API.get("/api/models/local");
  if (!local.ok && local.status !== 0) console.warn(`models: /api/models/local answered ${local.status}`);
  data.local = local.ok ? local.body?.providers ?? [] : [];
  KNOBS.st.cmds = data.heads.map((h) => h.key);
  KNOBS.st.providerOf = Object.fromEntries(data.heads.map((h) => [h.key, topology().heads?.[h.key]?.provider ?? ""]));
  data.knobsErr = await KNOBS.load();
}

// ---------- the commands: the daemon's heads joined to the topology file and the model roster ----------
const topology = () => data.topo?.topology ?? {};
function window_(row) { return row?.context_window ?? 0; }
function commandsOf() {
  const t = topology(), hs = t.heads ?? {}, ps = t.providers ?? {};
  return data.heads.filter((h) => !ui.gone.has(h.key)).map((h) => {
    const key = h.key, cfg = hs[key] ?? {}, provKey = cfg.provider ?? "", pcfg = ps[provKey] ?? {};
    const row = data.models.find((r) => r.head === key);
    const models = (row?.models ?? []).filter((m) => m.resolved).map((m) => ({
      id: m.id, label: nameOf(m), window: window_(m), max: m.max_context_window ?? null, rates: m.rates ?? null, slot: m.slot ?? null,
    }));
    const slots = {};
    for (const m of models) if (m.slot && TIERS.includes(m.slot)) slots[m.slot] = m.id;
    for (const [slot, id] of Object.entries(cfg.model_slots ?? {})) slots[String(slot).toLowerCase()] ??= id;
    const defaults = defaultTiers(models.map((m) => m.id), row?.pinned_model || null);
    const first = TIERS.map((s) => slots[s]).find(Boolean) ?? defaults.opus ?? row?.pinned_model ?? models[0]?.id;
    const shown = models.find((m) => m.id === first);
    const family = PROVIDER_FAMILY[provKey] ?? provKey;
    const lives = data.sessions.filter((s) => s.head === key && (s.status === "working" || s.status === "busy")).length;
    return {
      key, cmd: h.label || cfg.claude?.command || key, provKey, family, prov: PROVIDER_NAME[family] ?? provKey,
      url: pcfg.base_url ?? "", models, slots, defaults, pinned: row?.pinned_model ?? null, model: shown?.label ?? "None", window: shown?.window ?? 0, running: lives,
      prompt: { mode: cfg.system_prompt_mode ?? "append", text: cfg.system_prompt ?? "", file: cfg.system_prompt_file ?? "" },
      up: h.running && h.healthy, live: h.running, auth: h.authKind ?? "",
    };
  });
}
// a command's state, as the daemon's doctor reports it: unlinked, signed out or not started carry their fix
function stateOf(c) {
  const checks = data.doctor?.checks ?? [];
  const mine = checks.filter((x) => x.id === `auth/${c.key}` || x.id === `daemon/head ${c.key}` || (x.id === "installation/wrapper" && x.detail?.includes(c.cmd)));
  const bad = mine.find((x) => x.status === "fail" || x.status === "warn");
  if (!c.live) return { state: "stopped", word: "Not started", fix: bad?.fix_id ? { id: bad.fix_id, label: "Start" } : { head: c.key, label: "Start" } };
  if (!bad) return null;
  const auth = bad.id.startsWith("auth/"), keyed = /key/i.test(c.auth), refused = /refus|reject|invalid|401/i.test(bad.detail ?? "");
  const word = auth ? (keyed ? (refused ? "Credentials refused" : "No key") : "Signed out") : bad.id === "installation/wrapper" ? "Command not found" : "Not started";
  const label = auth ? (keyed ? (refused ? "Replace key" : "Add key") : "Sign in") : bad.id === "installation/wrapper" ? "Restore" : "Start";
  return { state: bad.id, word, fix: auth ? { signin: true, label } : bad.fix_id ? { id: bad.fix_id, label } : null };
}
const cmdByKey = (k) => commandsOf().find((c) => c.key === k);

// ---------- what a person reads ----------
function big(n) { // a window: 200K, 256K, 1M, 1.05M
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
  if (s?.state === "stopped") return { cls: "stalled", lamp: GL.notstarted, word: s.word };
  if (s?.state === "installation/wrapper") return { cls: "stalled", lamp: GL.relink, word: s.word };
  if (s) return { cls: "stalled", lamp: ICON.signout, word: s.word };
  if (c.running) return { cls: "working", lamp: ICON.trace, word: `${c.running} running` };
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
      const row = c.models.find((m) => m.id === c.slots[t]);
      return [t, { model: row?.id ?? null, win: row ? big(row.window) : "" }];
    })),
    // with no tier declared, one window serves every default tier: the head's own, shown as the default model's
    headWin: TIERS.some((t) => c.slots[t]) ? "" : big(c.models.find((m) => m.id === c.defaults.opus)?.window ?? 0),
    mode: c.prompt.mode, text: c.prompt.text, file: c.prompt.file, per: perOf(c),
  };
}
function draftState(c) {
  const d = ui.draft;
  // a window is a number, and no larger than the most the provider publishes for that model, where it publishes one
  const tiers = Object.fromEntries(TIERS.map((t) => {
    const win = parseWindow(d.tiers[t].win), max = c.models.find((m) => m.id === d.tiers[t].model)?.max ?? null;
    return [t, { ok: !d.tiers[t].model || (Boolean(win) && (max === null || win <= max)), over: Boolean(win) && max !== null && win > max }];
  }));
  // no tier declared: the one head window, capped at the least the provider publishes for the models those tiers answer with
  const unset = !TIERS.some((t) => d.tiers[t].model);
  const hw = parseWindow(d.headWin), caps = unset ? TIERS.map((t) => c.models.find((m) => m.id === c.defaults[t])?.max).filter(Boolean) : [];
  const hmax = caps.length ? Math.min(...caps) : null;
  const head = { ok: !unset || (Boolean(hw) && (hmax === null || hw <= hmax)), over: unset && Boolean(hw) && hmax !== null && hw > hmax, max: hmax };
  const same = JSON.stringify(d) === JSON.stringify(draftOf(c));
  return { tiers, head, ok: TIERS.every((t) => tiers[t].ok) && head.ok, same };
}
const logsOf = (key) => ui.logs[key];
// Remove: two presses, and what stays is said before the second. A command a session is on says who, and each name opens it.
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
    return `<span class="said limit">${esc(rm.failed.word)}</span>${door}${rm.failed.act === "reread" ? "" : `<button class="act quiet" data-act="arm">Remove</button>`}`;
  }
  return `<button class="act quiet" data-act="arm">Remove</button>`;
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
function runtimeHtml(c) {
  const r = localOf(c);
  if (!r) return "";
  const said = !r.runtime ? `<span class="said limit">Not answering</span>` : !r.models ? `<span class="said limit">List not answering</span>` : "";
  return `<span class="lbl">Runtime</span><div class="ctl"><span class="urlread">${esc([r.runtime, r.version].filter(Boolean).join(" ") || r.url)}</span>${said}</div>`;
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
function paneHtml() {
  if (ui.add) return addPaneHtml();
  const c = cmdByKey(ui.open);
  if (!c) return "";
  ui.draft ??= draftOf(c);
  const d = ui.draft, k = look(c), st = draftState(c), s = stateOf(c);
  const fix = s?.fix && !ui.fixing[c.key] && ui.sign?.key !== c.key && ui.keyin?.key !== c.key ? `<button class="act primary small" data-act="fix" data-h="${esc(c.key)}">${esc(s.fix.label)}</button>` : "";
  const head = `<header style="--c:${colorOf(c.family)}"><span class="lamp ${k.cls}" aria-hidden="true">${k.lamp}</span><div class="who"><div class="top"><span class="name">${esc(c.cmd)}</span><span class="prov">${esc(c.prov)}</span></div>`
    + `<div class="meta"><span class="word ${k.cls}">${esc(k.word)}</span>${fix}</div>${signHtml(c)}</div>`
    + `<div class="acts">${removeHtml(c)}<button class="icon close" data-act="close" aria-label="Close">${GL.close}</button></div></header>`;
  const unset = !TIERS.some((x) => d.tiers[x].model);
  const tierRows = TIERS.map((t) => {
    const sel = d.tiers[t], ts = st.tiers[t];
    const picked = c.models.find((x) => x.id === sel.model), dm = c.models.find((x) => x.id === c.defaults[t]);
    const asDefault = !picked && unset && dm;
    // clearing this tier gives its default back when the other three are unset too, else None (a tier left out is a 400)
    const alone = TIERS.every((x) => x === t || !d.tiers[x].model);
    const clear = alone && dm ? `<span>${esc(dm.label)}</span><span class="dflt">Default</span>` : "<span>None</span>";
    const items = [`<button role="menuitem" data-act="tier" data-t="${t}" data-v="">${clear}</button>`,
      ...c.models.map((x) => `<button role="menuitem" data-act="tier" data-t="${t}" data-v="${esc(x.id)}"><span>${esc(x.label)}</span><span class="mmeta">${big(x.window)}</span></button>`)].join("");
    const face = picked ? esc(picked.label) : asDefault ? `<span>${esc(dm.label)}</span><span class="dflt">Default</span>` : "None";
    const win = picked ? `<input class="field win" id="h-win-${t}" data-t="${t}" value="${esc(sel.win)}" spellcheck="false" autocomplete="off" aria-label="${tierName(t)} window">`
      : asDefault ? `<input class="field win" id="h-win-${t}" data-hw="1" value="${esc(d.headWin)}" spellcheck="false" autocomplete="off" aria-label="${tierName(t)} window">` : "";
    const bad = picked ? !(ts.ok || ts.over) : asDefault && !st.head.ok && !st.head.over;
    const note = picked ? runtimeNote(c, picked.id, ts.over) ?? limitHtml(picked, sel, ts.over)
      : asDefault ? runtimeNote(c, dm.id, st.head.over) ?? limitHtml({ max: st.head.max ?? dm.max }, { model: dm.id }, st.head.over) : "";
    return `<span class="lbl">${tierName(t)}</span><div class="ctl menuwrap"><button class="pickbtn${picked ? "" : asDefault ? " dflt-tier" : " empty"}" data-act="tierpick" data-t="${t}" style="--c:${colorOf(c.family)}">${face}${ICON.caret}</button>`
      + `${ui.pick === `tier:${t}` ? `<div class="menu">${items}</div>` : ""}${win}${bad ? `<span class="said limit">Not a window</span>` : ""}${note}</div>`;
  }).join("");
  const per = Object.entries(d.per).map(([root, l]) => `<div class="per"><div class="per-top"><span class="pname">${esc(nameOfRoot(root))}</span>${modeSwitch(`per:${root}`, l.mode)}`
    + `<button class="icon" data-act="unper" data-p="${esc(root)}" aria-label="Remove">${GL.close}</button></div>${l.file ? pfile(l.file) : `<textarea class="field ptext" data-ptext="${esc(root)}" rows="2">${esc(l.text)}</textarea>`}</div>`).join("");
  const free = data.projects.filter((p) => !d.per[p.root]);
  const addPer = free.length ? `<div class="menuwrap"><button class="act quiet" data-act="perpick">Add project</button>${ui.pick === "per" ? `<div class="menu">${free.map((p) => `<button data-act="per" data-p="${esc(p.root)}">${esc(p.name)}</button>`).join("")}</div>` : ""}</div>` : "";
  const calm = ["Applied", "Saved", "Restarting"].includes(ui.said);
  const saidHtml = ui.said ? `<span class="said${calm ? "" : " limit"}">${ui.said === "Restarting" ? ICON.wait : ""}${esc(ui.said)}</span>` : "";
  const restart = ui.said === "Saved" && ui.pending ? `<button class="act" data-act="restart-splice">Restart splice</button>` : "";
  const form = `<section class="form">`
    + `<span class="lbl">Sends to</span><div class="ctl"><span class="urlread">${esc(c.url)}</span></div>`
    + runtimeHtml(c)
    + tierRows
    + `<span class="lbl">System prompt</span><div class="ctl col">${modeSwitch("head", d.mode)}${d.file ? pfile(d.file) : `<textarea class="field ptext" id="h-text" rows="3">${esc(d.text)}</textarea>`}</div>`
    + (data.knobsErr ? "" : `<div class="kn">${KNOBS.commandRowsHtml(c.key, COMMAND_KNOBS)}</div>`)
    + `<span class="lbl">Projects</span><div class="ctl col">${per}${addPer}</div>`
    + `<div class="foot"><button class="act primary" data-act="save"${st.ok && !st.same && !ui.saving ? "" : " disabled"}>Save</button>${saidHtml}${restart}</div></section>`;
  const lines = logsOf(c.key);
  const ev = eventsOf(lines ?? []);
  const raw = (lines ?? []).filter((l) => l.raw).slice(-60);
  const log = `<section class="hlog"><h3>Log</h3>${ev.length ? `<ol>${ev.map((e) => `<li><time>${esc(ampm(e.at))}</time><span>${esc(e.text)}${e.n > 1 ? ` · ${e.n} times` : ""}</span></li>`).join("")}</ol>` : `<p class="none">No events</p>`}`
    + `<details class="rawlog"${ui.rawOpen ? " open" : ""}><summary>Raw log</summary><ol>${raw.map((l) => `<li><span>${esc(l.raw)}</span></li>`).join("")}</ol></details></section>`;
  return `<div class="pane" style="--c:${colorOf(c.family)}">${head}<div class="body">${form}${log}</div></div>`;
}
// daemon.log lines: "[2026-10-12 04:12:52] [claudex] text"; the log is already this command's, so its tag drops
function parseLog(lines) {
  return lines.map((raw) => {
    const m = /^\[(\d{4}-\d\d-\d\d) (\d\d:\d\d:\d\d)\]\s*(?:\[([^\]]+)\]\s*)?(.*)$/.exec(raw);
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
async function readLog(key) {
  const res = await API.get(`/api/logs/${encodeURIComponent(key)}?tail=2000`);
  ui.logs[key] = res.ok ? parseLog(res.body?.lines ?? []).filter((l) => l.tag === key) : [];
  render();
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
async function restartSplice() {
  ui.said = "Restarting"; render();
  const res = await API.post("/api/daemon/restart");
  if (!res.ok) { ui.said = "Not restarted"; ui.draft = null; render(); return; }
  for (let i = 0; i < 90; i++) {
    await new Promise((r) => setTimeout(r, 1000));
    const up = await API.get("/api/heads");
    if (up.ok) { ui.pending = false; ui.restartDue = false; ui.gone.clear(); ui.said = "Applied"; ui.draft = null; await readAll(); render(); return; }
  }
  ui.said = "Not restarted"; render();
}

// ---------- Every model: each command's roster with window and price ----------
function everyHtml() {
  const q = ui.q.trim().toLowerCase();
  const groups = commandsOf().map((c) => {
    const rows = c.models.filter((m) => !q || m.label.toLowerCase().includes(q) || m.id.toLowerCase().includes(q));
    if (!rows.length) return "";
    const body = rows.map((x) => {
      const r = x.rates;
      const price = r ? `<td class="num">${usd(r.input ?? r.i)}</td><td class="num">${usd(r.output ?? r.o)}</td>` : `<td class="num unpriced" colspan="2">No price</td>`;
      const lc = r?.long_context ? `<tr class="tier"><td></td><td class="num">${big(r.long_context.over_input_tokens)}+</td><td class="num">${usd(r.long_context.input)}</td><td class="num">${usd(r.long_context.output)}</td></tr>` : "";
      // the command whose main model this is wears its chip beside it, no word (spec 9h; marlin, Oct 12)
      const pin = x.id === c.pinned ? `<span class="chip">${esc(c.cmd)}</span>` : "";
      return `<tr><td class="mname">${esc(x.label)}${pin}</td><td class="num">${big(x.window)}</td>${price}</tr>${lc}`;
    }).join("");
    return `<tbody style="--c:${colorOf(c.family)}"><tr class="grp"><td colspan="4"><span class="chip">${esc(c.cmd)}</span><span class="prov">${esc(c.prov)}</span></td></tr>${body}</tbody>`;
  }).join("");
  const table = groups
    ? `<table class="every"><thead><tr><th rowspan="2">Model</th><th rowspan="2" class="num">Window</th><th colspan="2" class="num per-m">Per 1M tokens</th></tr><tr><th class="num">In</th><th class="num">Out</th></tr></thead>${groups}</table>`
    : `<div class="nomatch"><span class="state">No match</span><button class="act quiet" data-act="clearq">Clear</button></div>`;
  return `<div class="every-wrap"><div class="search">${G('<circle cx="11" cy="11" r="6.5"/><path d="M16 16l4.5 4.5"/>')}<input class="field" id="q" placeholder="Search" value="${esc(ui.q)}" autocomplete="off"></div>${table}</div>`;
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
  if (!ps.length) return `<div class="projects"><p class="none">No projects</p></div>`;
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
  const rows = got.compaction ?? [];
  const lit = rows.findIndex((r) => r.source);
  const ladder = rows.length
    ? `<ol class="ladder">${rows.map((r, i) => `<li class="${i === lit ? "lit" : ""}"><span class="rung"></span><span class="rname">${esc(r.scope)}</span>${i === lit ? `<span class="rfile">${esc(r.source)}</span>${r.chars === 0 ? `<span class="rempty">Empty</span>` : ""}` : ""}</li>`).join("")}</ol>`
    : `<p class="none">Claude Code's own</p>`;
  const bar = c.window ? `<div class="winbar" style="--c:${colorOf(c.family)}"><div class="bar"><span class="tick" style="left:85%"></span></div><span class="wlabel">${big(c.window)}</span></div>` : "";
  const right = `<div class="proj"><header><h2>${esc(p.name)}</h2><span class="ppath" title="${esc(p.root ?? "")}"><bdo dir="ltr">${esc(p.place ?? homeRel(p.root))}</bdo></span></header>${files}`
    + `<section class="pblock">${chips}<div class="twin"><div><h3>System prompt</h3>${stack}</div><div><h3>Compaction</h3>${ladder}${bar}</div></div></section></div>`;
  return `<div class="projects"><nav class="proj-list">${list}</nav>${right}</div>`;
}

// ---------- claude: plain `claude`, through splice or direct ----------
function claudeHtml() {
  const m = data.claude?.mode;
  if (!data.claude) return ui.restartDue ? `<button class="act" data-act="restart-splice">Restart splice</button>` : "";
  const through = m === "wrapped";
  const can = Boolean(data.claude.shim_path);
  const due = ui.restartDue ? `<button class="act" data-act="restart-splice">Restart splice</button>` : "";
  const said = ui.claudeSaid ? `<span class="said limit">${esc(ui.claudeSaid)}</span>` : "";
  return `<span class="clabel">Your claude command</span><div class="switch" role="group">`
    + `<button data-act="claude" data-v="wrap" aria-pressed="${through}"${can ? "" : " disabled"}>Through splice</button>`
    + `<button data-act="claude" data-v="unwrap" aria-pressed="${!through}"${can ? "" : " disabled"}>Straight to Anthropic</button></div>${said}${due}`;
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
  const field = (id, lbl, val, extra = "") => `<label class="lbl" for="${id}">${lbl}</label><div class="ctl"><input class="field" id="${id}" value="${esc(val)}" spellcheck="false" autocomplete="off" ${extra}></div>`;
  let f = "";
  if (asks("name") || second(p)) f += field("a-name", "Name", a.name);
  if (asks("base_url")) f += field("a-url", "URL", a.url);
  if (p.requires_key || (p.auth_kind === "api-key" && p.asks.length)) f += field("a-key", "Key", a.key, 'type="password"');
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
  const ready = ((!asks("name") && !second(p)) || a.name.trim()) && (!asks("base_url") || a.url.trim()) && (!asks("models") || a.rows.some((r) => r.id.trim()));
  const foot = `<div class="foot"><button class="act primary" data-act="add"${ready && !a.busy ? "" : " disabled"}>${a.busy ? "Adding" : "Add"}</button><button class="act quiet" data-act="cancel-add">Cancel</button>${said}</div>`;
  return `<div class="pane add-pane" style="--c:${colorOf(fam)}">${head}<div class="body">${headRow}${f ? `<section class="form">${f}</section>` : ""}${checks}${sign}${foot}</div></div>`;
}
const CHECK_NAME = { credential: "Key", "base url": "URL", models: "Models", windows: "Windows", "live turn": "Live request" };
function failWordOf(c, p) {
  const d = c.detail ?? "", http = /HTTP (\d{3})/.exec(d);
  console.warn(`add check ${c.name}: ${d}`);
  if (c.name === "credential") return /oauth|login|sign/i.test(p.auth_kind) ? "Signed out" : "No key";
  if (c.name === "base url") return "No answer";
  if (c.name === "models") return http ? `HTTP ${http[1]}` : /did not answer with a model list|not a list/i.test(d) ? "No list" : /not listed|missing/i.test(d) ? "Not listed" : "No answer";
  if (c.name === "windows") return "Too large";
  return http ? `HTTP ${http[1]}` : "No answer";
}
// a runtime that keeps no model list is normal: its rows are the picker
const SOFT = new Set(["models"]);
const failWord = (r) => (r.status === 409 ? "Already added" : "Not added");
async function startAdd() {
  const a = ui.add, p = data.profiles.find((x) => x.name === a.profile);
  a.busy = true; a.said = null; a.checks = null; render();
  const body = { profile: p.name };
  if (p.asks.includes("name") || second(p)) body.name = a.name.trim();
  if (second(p)) body.command = commandFor(p, a);
  if (p.asks.includes("base_url")) body.base_url = a.url.trim();
  if (p.asks.includes("models")) body.models = a.rows.filter((r) => r.id.trim()).map((r) => ({ id: r.id.trim(), context_window: parseWindow(r.win) ?? undefined }));
  const res = await API.post("/api/add", body);
  if (!res.ok) { a.busy = false; a.said = failWord(res); render(); return; }
  a.id = res.body.id; a.env = res.body.key_env;
  if (res.body.sign_in_by === "key" && a.key.trim()) {
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
async function verifyAndSave() {
  const a = ui.add;
  const v = await API.post(`/api/add/${encodeURIComponent(a.id)}/verify`, {});
  a.checks = v.body?.checks ?? [];
  // A press never ends in nothing: a check that stops the add shows beside its own name, and an answer with no checks says so.
  const hard = a.checks.some((c) => !c.ok && !SOFT.has(c.name));
  if (!v.ok && !a.checks.length) console.warn(`add verify: ${v.body?.error ?? v.status}`);
  if (hard || (!v.ok && !a.checks.length)) { a.busy = false; a.said = hard ? null : "Not added"; render(); return; }
  render();
  const s = await API.post(`/api/add/${encodeURIComponent(a.id)}/save`, {});
  if (!s.ok) { a.busy = false; a.said = "Not added"; render(); return; }
  const key = s.body?.saved?.head_key ?? a.profile;
  for (let i = 0; i < 90; i++) {
    await new Promise((r) => setTimeout(r, 1000));
    const up = await API.get("/api/heads");
    if (up.ok) {
      await readAll();
      const added = commandsOf().find((c) => c.key === key || c.cmd === s.body?.saved?.command) ?? commandsOf().at(-1);
      ui.add = null; ui.open = added?.key ?? null; ui.draft = null; render();
      return;
    }
  }
  a.busy = false; a.said = "Not added"; render();
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
  document.getElementById("claude").innerHTML = data.loaded ? claudeHtml() : "";
  if (!data.loaded) {
    root.innerHTML = `<p class="none">${esc(data.err ?? "Reading")}</p>`;
    return;
  }
  root.innerHTML = viewsHtml() + (ui.view === "commands" ? commandsHtml() : ui.view === "every" ? everyHtml() : projectsHtml());
  root.dataset.shown = `${ui.view}:${ui.open}`;
  const now = root.querySelector(".pane .body");
  if (now) now.scrollTop = top;
  if (fid) {
    const g = document.getElementById(fid);
    if (g) { g.focus(); if (pos != null && g.setSelectionRange) g.setSelectionRange(pos, pos); }
  }
}
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
    case "close": dropAdd(); Object.assign(ui, { open: null, add: null, draft: null, said: null }); render(); break;
    case "new": Object.assign(ui, { open: null, draft: null, add: { profile: null, name: "", url: "", key: "", rows: [{ id: "", win: "" }], checks: null } }); render(); break;
    case "cancel-add": dropAdd(); ui.add = null; render(); break;
    case "tpl": a.profile = d.t; render(); break;
    case "addrow": a.rows.push({ id: "", win: "" }); render(); document.getElementById(`a-m${a.rows.length - 1}`)?.focus(); break;
    case "add": startAdd(); break;
    case "open-signin": { const sg = a?.sign ?? ui.sign; if (sg?.url) window.open(sg.url, "_blank", "noopener"); break; }
    case "copy-code": navigator.clipboard?.writeText((a?.sign ?? ui.sign).code).catch(() => {}); break;
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
    case "restart-splice": restartSplice(); break;
    case "clearq": ui.q = ""; render(); break;
    case "proj": ui.proj = d.p; ui.file = null; render(); break;
    case "projcmd": ui.projCmd = d.h; render(); break;
    case "file": ui.file = ui.file === d.f ? null : d.f; render(); break;
    case "claude": setClaude(d.v); break;
    default: break;
  }
}
function onInput(e) {
  const t = e.target, a = ui.add;
  if (t.id === "q") { ui.q = t.value; render(); return; }
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
  if (!ui.draft) return;
  if (t.dataset.hw) { ui.draft.headWin = t.value; ui.said = null; render(); }
  else if (t.dataset.t) { ui.draft.tiers[t.dataset.t].win = t.value; ui.said = null; render(); }
  else if (t.id === "h-text") { ui.draft.text = t.value; ui.said = null; render(); }
  else if (t.dataset.ptext) { ui.draft.per[t.dataset.ptext].text = t.value; ui.said = null; render(); }
}
root.addEventListener("click", onClick);
root.addEventListener("input", onInput);
document.getElementById("claude").addEventListener("click", onClick);

async function init() {
  render();
  await readAll();
  const profiles = await API.get("/api/add/profiles");
  data.profiles = (profiles.body?.profiles ?? []).filter((p) => p.name !== "nvidia");
  ui.wide = sideBySide();
  const want = new URLSearchParams(location.search).get("cmd");
  if (want && cmdByKey(want)) openCmd(want);
  else if (ui.wide && !ui.open) { const first = order(commandsOf())[0]; if (first) openCmd(first.key); }
  render();
  if (location.hash === "#log") document.querySelector(".hlog")?.scrollIntoView({ block: "center", behavior: "instant" });
  setInterval(async () => { if (!ui.draft && !ui.add && !ui.saving && !ui.keyin && !ui.sign && !Object.keys(ui.fixing).length) { await readAll(); render(); } }, 5000);
}
init();
