// Models: every command he runs, what it serves and how it is doing, every model on every plan and key with its window
// and price, and what applies inside a project. Create a command from a template or a custom endpoint, change its model,
// window and system prompt, run the fix splice offers, and choose whether plain `claude` runs through splice.
// BUILT AS DRAWN (Splice-Animator/design/console/models.html), on the daemon's own routes: /api/heads, /api/topology,
// /api/models, /api/projects, /api/add, /api/doctor, /api/logs, /api/claude-head. Where the drawing and the daemon
// disagree on a fact the daemon wins, and the element shows only where the fact exists. Rename is out of 0.4.0, and
// Remove is not on this page yet.
"use strict";

const TIERS = ["opus", "sonnet", "haiku", "fable"];
const tierName = (t) => t[0].toUpperCase() + t.slice(1);
const root = document.getElementById("models");
KNOBS.wire(root, () => render());

// ---------- what the daemon says ----------
const data = { heads: [], topo: null, models: [], projects: [], sessions: [], doctor: null, claude: null, accounts: [], profiles: [], loaded: false, err: null };
// What he is doing: the open command and its unsaved draft, a new command in progress, the saved words.
const ui = { view: "commands", open: null, add: null, draft: null, said: null, pick: null, saving: false, restart: null,
  logs: {}, proj: null, projCmd: null, file: null, files: {}, more: new Set(), q: "", claudeSaid: null, fixing: {}, wide: true };

async function readAll() {
  const paths = ["/api/heads", "/api/topology", "/api/models", "/api/projects", "/api/sessions", "/api/claude-head", "/api/accounts"];
  const [heads, topo, models, projects, sessions, claude, accounts] = await Promise.all(paths.map((p) => API.get(p)));
  if (!heads.ok || !topo.ok) {
    data.err = heads.status === 0 ? "splice is not answering" : `splice answered ${heads.status}`;
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
}

// ---------- the commands: the daemon's heads joined to the topology file and the model roster ----------
const topology = () => data.topo?.topology ?? {};
function window_(row) { return row?.context_window ?? 0; }
function commandsOf() {
  const t = topology(), hs = t.heads ?? {}, ps = t.providers ?? {};
  return data.heads.map((h) => {
    const key = h.key, cfg = hs[key] ?? {}, provKey = cfg.provider ?? "", pcfg = ps[provKey] ?? {};
    const row = data.models.find((r) => r.head === key);
    const models = (row?.models ?? []).filter((m) => m.resolved).map((m) => ({
      id: m.id, label: m.label || m.id, window: window_(m), rates: m.rates ?? null, slot: m.slot ?? null,
    }));
    const slots = {};
    for (const m of models) if (m.slot && TIERS.includes(m.slot)) slots[m.slot] = m.id;
    for (const [slot, id] of Object.entries(cfg.model_slots ?? {})) slots[String(slot).toLowerCase()] ??= id;
    const first = TIERS.map((s) => slots[s]).find(Boolean) ?? row?.pinned_model ?? models[0]?.id;
    const shown = models.find((m) => m.id === first);
    const family = PROVIDER_FAMILY[provKey] ?? provKey;
    const lives = data.sessions.filter((s) => s.head === key && (s.status === "working" || s.status === "busy")).length;
    return {
      key, cmd: h.label || cfg.claude?.command || key, provKey, family, prov: PROVIDER_NAME[family] ?? provKey,
      url: pcfg.base_url ?? "", models, slots, model: shown?.label ?? "None", window: shown?.window ?? 0, running: lives,
      prompt: { mode: cfg.system_prompt_mode ?? "append", text: cfg.system_prompt ?? "", file: cfg.system_prompt_file ?? "" },
      up: h.running && h.healthy, live: h.running,
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
  const word = bad.id.startsWith("auth/") ? "Signed out" : bad.id === "installation/wrapper" ? "Command not found" : "Not started";
  const label = bad.id.startsWith("auth/") ? "Sign in" : bad.id === "installation/wrapper" ? "Restore" : "Start";
  return { state: bad.id, word, fix: bad.fix_id ? { id: bad.fix_id, label } : null };
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
  if (ui.fixing[c.key]) return { cls: "working", lamp: '<svg class="wait" viewBox="0 0 40 40"><circle cx="20" cy="20" r="15"/></svg>', word: ui.fixing[c.key] };
  const s = stateOf(c);
  if (s?.state === "stopped") return { cls: "stalled", lamp: GL.notstarted, word: s.word };
  if (s?.state === "installation/wrapper") return { cls: "stalled", lamp: GL.relink, word: s.word };
  if (s) return { cls: "stalled", lamp: ICON.signout, word: s.word };
  if (c.running) return { cls: "working", lamp: ICON.trace, word: `${c.running} running` };
  return { cls: "ready", lamp: "<i></i>", word: "Ready" };
}
const order = (cs) => [...cs.filter((c) => stateOf(c)), ...cs.filter((c) => !stateOf(c))];
function cardHtml(c) {
  const k = look(c), s = stateOf(c);
  const fix = s?.fix && !ui.fixing[c.key] ? `<div class="fix"><button class="act primary" data-act="fix" data-h="${esc(c.key)}">${esc(s.fix.label)}</button></div>` : "";
  return `<article class="card h ${k.cls}" style="--c:${colorOf(c.family)}" data-open="${esc(c.key)}" aria-current="${ui.open === c.key && !ui.add}">`
    + `<span class="lamp ${k.cls}" aria-hidden="true">${k.lamp}</span>`
    + `<div class="top"><span class="chip">${c.running && !s ? "<i></i>" : ""}${esc(c.cmd)}</span><span class="prov">${esc(c.prov)}</span></div>`
    + `<div class="meta"><span class="word ${k.cls}">${esc(k.word)}</span><span>${esc(c.model)}</span>${c.window ? `<span>${big(c.window)}</span>` : ""}</div>${fix}</article>`;
}

// ---------- the open command: its settings, and its log ----------
const modeSwitch = (id, mode) => `<div class="switch" role="group">${["append", "replace", "strip"].map((x) =>
  `<button data-act="mode" data-id="${esc(id)}" data-v="${x}" aria-pressed="${mode === x}">${x[0].toUpperCase() + x.slice(1)}</button>`).join("")}</div>`;
// the project layers the topology declares for one command: [projects."ROOT".heads.KEY], by project root
const perOf = (c) => Object.fromEntries(Object.entries(topology().projects ?? {}).map(([root, p]) => [root, p?.heads?.[c.key]]).filter(([, l]) => l)
  .map(([root, l]) => [root, { mode: l.system_prompt_mode ?? "append", text: l.system_prompt ?? "", file: l.system_prompt_file ?? "" }]));
const projectOf = (root) => data.projects.find((p) => p.root === root || p.id === root);
const nameOfRoot = (root) => projectOf(root)?.name ?? root.split("/").filter(Boolean).pop();
function draftOf(c) {
  return {
    tiers: Object.fromEntries(TIERS.map((t) => {
      const row = c.models.find((m) => m.id === c.slots[t]);
      return [t, { model: row?.id ?? null, win: row ? big(row.window) : "" }];
    })),
    mode: c.prompt.mode, text: c.prompt.text, file: c.prompt.file, per: perOf(c),
  };
}
function draftState(c) {
  const d = ui.draft;
  const tiers = Object.fromEntries(TIERS.map((t) => [t, { ok: !d.tiers[t].model || Boolean(parseWindow(d.tiers[t].win)) }]));
  const same = JSON.stringify(d) === JSON.stringify(draftOf(c));
  return { tiers, ok: TIERS.every((t) => tiers[t].ok), same };
}
const logsOf = (key) => ui.logs[key];
function paneHtml() {
  if (ui.add) return addPaneHtml();
  const c = cmdByKey(ui.open);
  if (!c) return "";
  ui.draft ??= draftOf(c);
  const d = ui.draft, k = look(c), st = draftState(c), s = stateOf(c);
  const fix = s?.fix && !ui.fixing[c.key] ? `<button class="act primary small" data-act="fix" data-h="${esc(c.key)}">${esc(s.fix.label)}</button>` : "";
  const head = `<header style="--c:${colorOf(c.family)}"><span class="lamp ${k.cls}" aria-hidden="true">${k.lamp}</span><div class="who"><div class="top"><span class="name">${esc(c.cmd)}</span><span class="prov">${esc(c.prov)}</span></div>`
    + `<div class="meta"><span class="word ${k.cls}">${esc(k.word)}</span>${fix}</div></div>`
    + `<div class="acts"><button class="icon close" data-act="close" aria-label="Close">${GL.close}</button></div></header>`;
  const tierRows = TIERS.map((t) => {
    const sel = d.tiers[t], ts = st.tiers[t];
    const items = [`<button role="menuitem" data-act="tier" data-t="${t}" data-v=""><span>None</span></button>`,
      ...c.models.map((x) => `<button role="menuitem" data-act="tier" data-t="${t}" data-v="${esc(x.id)}"><span>${esc(x.label)}</span><span class="mmeta">${big(x.window)}</span></button>`)].join("");
    const picked = c.models.find((x) => x.id === sel.model);
    const win = sel.model ? `<input class="field win" id="h-win-${t}" data-t="${t}" value="${esc(sel.win)}" spellcheck="false" autocomplete="off" aria-label="${tierName(t)} window">` : "";
    return `<span class="lbl">${tierName(t)}</span><div class="ctl menuwrap"><button class="pickbtn${sel.model ? "" : " empty"}" data-act="tierpick" data-t="${t}" style="--c:${colorOf(c.family)}">${esc(picked?.label ?? "None")}${ICON.caret}</button>`
      + `${ui.pick === `tier:${t}` ? `<div class="menu">${items}</div>` : ""}${win}${ts.ok ? "" : `<span class="said limit">Not a window</span>`}</div>`;
  }).join("");
  const per = Object.entries(d.per).map(([root, l]) => `<div class="per"><div class="per-top"><span class="pname">${esc(nameOfRoot(root))}</span>${modeSwitch(`per:${root}`, l.mode)}`
    + `<button class="icon" data-act="unper" data-p="${esc(root)}" aria-label="Remove">${GL.close}</button></div>${l.file ? pfile(l.file) : `<textarea class="field ptext" data-ptext="${esc(root)}" rows="2">${esc(l.text)}</textarea>`}</div>`).join("");
  const free = data.projects.filter((p) => !d.per[p.root]);
  const addPer = free.length ? `<div class="menuwrap"><button class="act quiet" data-act="perpick">Add project</button>${ui.pick === "per" ? `<div class="menu">${free.map((p) => `<button data-act="per" data-p="${esc(p.root)}">${esc(p.name)}</button>`).join("")}</div>` : ""}</div>` : "";
  const saidHtml = ui.said ? `<span class="said${ui.said === "Applied" || ui.said === "Saved" ? "" : " limit"}">${esc(ui.said)}</span>` : "";
  const restart = ui.said === "Saved" && ui.pending ? `<button class="act" data-act="restart-splice">Restart splice</button>` : "";
  const form = `<section class="form">`
    + `<span class="lbl">Sends to</span><div class="ctl"><span class="urlread">${esc(c.url)}</span></div>`
    + tierRows
    + `<span class="lbl">System prompt</span><div class="ctl col">${modeSwitch("head", d.mode)}${d.file ? pfile(d.file) : `<textarea class="field ptext" id="h-text" rows="3">${esc(d.text)}</textarea>`}</div>`
    + `<span class="lbl">Projects</span><div class="ctl col">${per}${addPer}</div>`
    + `<div class="foot"><button class="act primary" data-act="save"${st.ok && !st.same && !ui.saving ? "" : " disabled"}>Save</button>${saidHtml}${restart}</div></section>`;
  const lines = logsOf(c.key);
  const log = `<section class="hlog"><h3>Log</h3><ol>${(lines ?? []).map((l) => `<li><time>${esc(l.at)}</time><span>${esc(l.text)}</span></li>`).join("")}</ol></section>`;
  return `<div class="pane" style="--c:${colorOf(c.family)}">${head}<div class="body">${form}${log}</div></div>`;
}
// daemon.log lines: "[2026-10-12 04:12:52] [claudex] text"; the log is already this command's, so its tag drops
function parseLog(lines) {
  return lines.map((raw) => {
    const m = /^\[(\d{4}-\d\d-\d\d) (\d\d:\d\d:\d\d)\]\s*(?:\[[^\]]+\]\s*)?(.*)$/.exec(raw);
    return m ? { at: m[2], text: m[3] } : { at: "", text: raw };
  });
}
async function readLog(key) {
  const res = await API.get(`/api/logs/${encodeURIComponent(key)}?tail=40`);
  ui.logs[key] = res.ok ? parseLog(res.body?.lines ?? []) : [];
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
    ui.said = res.body?.findings?.[0]?.message ?? "Not saved";
    render();
    return;
  }
  ui.pending = res.body.restart_required;
  ui.said = ui.pending ? "Saved" : "Applied";
  ui.draft = null;
  await readAll();
  render();
}
async function restartSplice() {
  ui.said = "Restarting"; render();
  const res = await API.post("/api/daemon/restart");
  if (!res.ok) { ui.said = "Not restarted"; render(); return; }
  for (let i = 0; i < 90; i++) {
    await new Promise((r) => setTimeout(r, 1000));
    const up = await API.get("/api/heads");
    if (up.ok) { ui.pending = false; ui.said = "Applied"; ui.draft = null; await readAll(); render(); return; }
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
      return `<tr><td class="mname">${esc(x.label)}</td><td class="num">${big(x.window)}</td>${price}</tr>`;
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
  const list = ps.map((x) => `<button class="proj-tab" data-act="proj" data-p="${esc(x.id)}" aria-current="${x.id === p.id}"><span class="pname">${esc(x.name)}</span><span class="ppath">${esc(x.place ?? x.root ?? "")}</span></button>`).join("");
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
  const chips = `<div class="cmdpick">${cs.map((x) => `<button class="chip${x.key === c.key ? " on" : ""}" style="--c:${colorOf(x.family)}" data-act="projcmd" data-h="${esc(x.key)}" aria-pressed="${x.key === c.key}">${esc(x.cmd)}</button>`).join("")}</div>`;
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
  const right = `<div class="proj"><header><h2>${esc(p.name)}</h2><span class="ppath">${esc(p.place ?? p.root ?? "")}</span></header>${files}`
    + `<section class="pblock">${chips}<div class="twin"><div><h3>System prompt</h3>${stack}</div><div><h3>Compaction</h3>${ladder}${bar}</div></div></section></div>`;
  return `<div class="projects"><nav class="proj-list">${list}</nav>${right}</div>`;
}

// ---------- claude: plain `claude`, through splice or direct ----------
function claudeHtml() {
  const m = data.claude?.mode;
  if (!data.claude) return "";
  const through = m === "wrapped";
  const can = Boolean(data.claude.shim_path);
  const said = ui.claudeSaid ? `<span class="said limit">${esc(ui.claudeSaid)}</span>` : "";
  return `<span class="clabel">claude</span><div class="switch" role="group">`
    + `<button data-act="claude" data-v="wrap" aria-pressed="${through}"${can ? "" : " disabled"}>Through splice</button>`
    + `<button data-act="claude" data-v="unwrap" aria-pressed="${!through}"${can ? "" : " disabled"}>Direct</button></div>${said}`;
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
const askable = (p) => p.name !== "claude" || !data.heads.some((h) => h.key === p.head_key);
const taken = (p) => p.command && data.heads.some((h) => h.label === p.command || h.key === p.head_key);
function addPaneHtml() {
  const a = ui.add;
  const p = a.profile && data.profiles.find((x) => x.name === a.profile);
  const head = `<header style="--c:${p ? colorOf(PROVIDER_FAMILY[p.head_key] ?? p.head_key) : "var(--key-light)"}"><div class="who"><div class="top"><span class="name">New command</span></div></div>`
    + `<div class="acts"><button class="icon close" data-act="cancel-add" aria-label="Close">${GL.close}</button></div></header>`;
  if (!p) {
    const tiles = data.profiles.filter(askable).map((x) => {
      const fam = PROVIDER_FAMILY[x.head_key] ?? x.head_key, off = taken(x);
      const label = PROVIDER_NAME[fam] ?? (x.name === "local" ? "Local model" : x.name === "api-key" ? "Other provider" : x.name);
      return `<button class="pick tpl" style="--c:${colorOf(fam)}" data-act="tpl" data-t="${esc(x.name)}"${off ? " disabled" : ""}><span class="blot"></span><span class="tname">${esc(label)}</span>`
        + `${off ? `<span class="said">Added</span>` : ""}<span class="chip ghost">${esc(x.command || "claude-…")}</span></button>`;
    }).join("");
    return `<div class="pane add-pane">${head}<div class="body"><div class="tpls">${tiles}</div></div></div>`;
  }
  const asks = (k) => p.asks.includes(k);
  const field = (id, lbl, val, extra = "") => `<label class="lbl" for="${id}">${lbl}</label><div class="ctl"><input class="field" id="${id}" value="${esc(val)}" spellcheck="false" autocomplete="off" ${extra}></div>`;
  let f = "";
  if (asks("name")) f += field("a-name", "Name", a.name);
  if (asks("base_url")) f += field("a-url", "URL", a.url);
  if (p.requires_key || (p.auth_kind === "api-key" && p.asks.length)) f += field("a-key", "Key", a.key, 'type="password"');
  if (asks("models")) {
    const rows = a.rows.map((r, i) => `<div class="mrow"><input class="field" id="a-m${i}" data-i="${i}" data-f="id" value="${esc(r.id)}" spellcheck="false" autocomplete="off" aria-label="Model">`
      + `<input class="field win" id="a-w${i}" data-i="${i}" data-f="win" value="${esc(r.win)}" spellcheck="false" autocomplete="off" aria-label="Window"></div>`).join("");
    f += `<span class="lbl"></span><div class="ctl col"><div class="mrow labels"><span>Model</span><span>Window</span></div>${rows}<button class="act quiet" data-act="addrow">Add model</button></div>`;
  }
  const fam = PROVIDER_FAMILY[p.head_key] ?? p.head_key;
  const cmd = p.command || (a.name ? `claude-${a.name}` : "");
  const headRow = `<div class="tplhead"><span class="blot"></span><span class="tname">${esc(PROVIDER_NAME[fam] ?? p.name)}</span><span class="chip${cmd ? "" : " ghost"}">${esc(cmd || "claude-…")}</span></div>`;
  const checks = a.checks ? `<ol class="checks">${a.checks.map((c) => `<li class="${c.ok ? "pass" : "fail"}"><span class="lamp ${c.ok ? "ready" : "stalled"}">${c.ok ? "<i></i>" : GL.notstarted}</span><span class="cname">${esc(c.name)}</span>${c.ok ? "" : `<span class="said limit">${esc(c.detail ?? "")}</span>`}</li>`).join("")}</ol>` : "";
  const sg = a.sign;
  const sign = sg ? `<div class="outbox"><span class="waitbox" role="status">${ICON.wait}<span class="sr">Signing in</span></span>`
    + `${sg.code ? `<button class="usercode" data-act="copy-code">${esc(sg.code)}</button><span class="code-hint">Enter this code on the sign-in page</span>` : ""}`
    + `${sg.url ? `<button class="act" data-act="open-signin">Open sign-in</button>` : ""}<button class="act quiet" data-act="cancel-signin">Cancel</button></div>` : "";
  const said = a.said ? `<span class="said limit">${esc(a.said)}</span>` : "";
  const ready = (!asks("name") || a.name.trim()) && (!asks("base_url") || a.url.trim()) && (!asks("models") || a.rows.some((r) => r.id.trim()));
  const foot = `<div class="foot"><button class="act primary" data-act="add"${ready && !a.busy ? "" : " disabled"}>${a.busy ? "Adding" : "Add"}</button><button class="act quiet" data-act="cancel-add">Cancel</button>${said}</div>`;
  return `<div class="pane add-pane" style="--c:${colorOf(fam)}">${head}<div class="body">${headRow}${f ? `<section class="form">${f}</section>` : ""}${checks}${sign}${foot}</div></div>`;
}
const failWord = (r) => (r.status === 409 ? "Already added" : "Not added");
async function startAdd() {
  const a = ui.add, p = data.profiles.find((x) => x.name === a.profile);
  a.busy = true; a.said = null; a.checks = null; render();
  const body = { profile: p.name };
  if (p.asks.includes("name")) body.name = a.name.trim();
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
  if (!res.ok) { a.sign = null; a.busy = false; a.said = "Not signed in"; render(); return; }
  for (let i = 0; i < 300 && a.sign; i++) {
    const st = (await API.get(`/api/add/${encodeURIComponent(a.id)}`)).body?.sign_in ?? {};
    if (st.state === "failed") { a.sign = null; a.busy = false; a.said = "Not signed in"; render(); return; }
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
  if (!v.ok || a.checks.some((c) => !c.ok)) { a.busy = false; a.said = null; render(); return; }
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

// ---------- the fix splice offers: Start, Sign in, Restore ----------
async function fix(key) {
  const c = cmdByKey(key), s = stateOf(c);
  if (!s?.fix) return;
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
  if (KNOBS.click?.(e)) { render(); return; }
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
    case "open-signin": if (a?.sign?.url) window.open(a.sign.url, "_blank", "noopener"); break;
    case "copy-code": navigator.clipboard?.writeText(a.sign.code).catch(() => {}); break;
    case "cancel-signin": a.sign = null; a.busy = false; render(); break;
    case "tierpick": ui.pick = ui.pick === `tier:${d.t}` ? null : `tier:${d.t}`; render(); break;
    case "tier": {
      const c = cmdByKey(ui.open), m = c.models.find((x) => x.id === d.v);
      ui.draft.tiers[d.t] = { model: d.v || null, win: m ? big(m.window) : "" };
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
  if (t.dataset.t) { ui.draft.tiers[t.dataset.t].win = t.value; ui.said = null; render(); }
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
  setInterval(async () => { if (!ui.draft && !ui.add && !ui.saving && !Object.keys(ui.fixing).length) { await readAll(); render(); } }, 5000);
}
init();
