// splice's settings as one kit: each setting, its control and what a change did, drawn the same wherever a page reaches
// it (Settings, the gear on Requests, a command's pane on Models). Everything sits in KNOBS, so a page that loads it
// gains one global. A page wires it once (KNOBS.wire), loads it (KNOBS.load), asks it first on every click
// (KNOBS.click), and redraws when it answers true. Load after kit.js (esc, ICON, G, colorOf) and api.js (API).
//
// WHERE EACH VALUE COMES FROM. GET /api/config answers the effective value and every layer behind it, and the kit
// reads them in the daemon's own order (runtime > env > file > splice.toml > default, ConfigService). A command's own
// value is that command's [heads.KEY.overrides] layer, which the payload carries per head.
//
// WHAT A CHANGE DOES, HONESTLY. PATCH /api/config answers which keys it applied, which it refused and with what
// reason, and which of the applied ones the running daemon does not read until it restarts. The kit carries all three
// back to the page as the result of the change and never writes a word of its own for the third: nearly every knob is
// snapshotted at daemon start (Knob.kt:12-16), and what the screen says about that is a product call, not this file's.
// A page draws only settings whose change the daemon takes live until that is built (BUILD.md, "see it applied
// without managing a restart").
"use strict";

const KNOBS = (() => {
  const fmt = (n) => Number(n).toLocaleString("en-US");
  const SEC = 1000, MIN = 60000, HR = 3600000, MB = 1048576;
  const dur = (ms) => (ms % HR === 0 ? `${ms / HR} h` : ms % MIN === 0 ? `${ms / MIN} min` : ms % SEC === 0 ? `${ms / SEC} s` : `${ms} ms`);
  const tok = (n) => (n >= 1e6 ? `${+(n / 1e6).toFixed(1)}M` : n >= 1e3 ? `${+(n / 1e3).toFixed(1)}K` : `${n}`);
  const PLUS = G('<path d="M12 5v14M5 12h14"/>');
  const BACK = G('<path d="M9 14L4 9l5-5"/><path d="M4 9h11a5 5 0 0 1 0 10h-3"/>'); // what removing a command's own value goes back to

  // c: the control. dur: preset durations with a Custom value in `unit`. count: preset counts, 0 the `none` word.
  // pick: named choices (a switch up to three, else a menu). flag: On and Off. models: a list of models, picked.
  // only: the commands a setting acts on, drawn as their chips on its row. cmds: one switch per command.
  // more: cmd (a command can hold its own value), also (words a person might search with), home (the Settings job it
  // is drawn on), ask (a change the page holds until the person says yes, because it deletes).
  const K = (key, label, c, def, more = {}) => ({ key, label, c, def, ...more });
  const LIST = [
    // the no-progress limit, the one wall that ends a turn: every model, tool or reasoning event renews it, so it bounds a
    // silence, never a turn's length (Watchdog.kt:6-7, :148-155, :390-398). The three tiers under it ask whether the
    // provider is alive and never end a turn on their own (Knob.kt:237-300)
    K("upstreamTimeoutMs", "Give up after no progress for", { k: "dur", opts: [10 * MIN, 15 * MIN, 20 * MIN, 30 * MIN, HR, 2 * HR], unit: "min" }, 15 * MIN, { cmd: true, also: "timeout limit cut off long turn wedged stuck", short: "Give up after" }),
    K("firstByteTimeoutMs", "Ask if a silent start is alive after", { k: "dur", opts: [30 * SEC, 60 * SEC, 90 * SEC, 2 * MIN, 5 * MIN], unit: "s" }, 90 * SEC, { cmd: true, home: "silent", also: "first word first byte" }),
    K("streamIdleMs", "Ask if a silent answer is alive after", { k: "dur", opts: [30 * SEC, 60 * SEC, 90 * SEC, 2 * MIN, 5 * MIN], unit: "s" }, 90 * SEC, { cmd: true, also: "silence idle hang" }),
    // arms only on a provider measured to resume from a prefill (HeadBuildInputs.kt:168-171)
    K("stallReanchorMs", "Resume a silent answer after", { k: "dur", opts: [10 * SEC, 20 * SEC, 30 * SEC, 60 * SEC], unit: "s" }, 20 * SEC, { cmd: true, home: "silent", also: "resume", only: ["claude-deepseek", "claude-kimi"] }),
    // admission per command (Knob.kt:186-194); attempts, the first included
    K("maxInflight", "Requests at once", { k: "count", opts: [4, 8, 12, 16, 24, 32, 0], none: "Unlimited" }, 12, { cmd: true, also: "concurrent parallel per command" }),
    K("maxQueued", "Requests waiting in line", { k: "count", opts: [128, 256, 512, 1024, 0], none: "Unlimited" }, 512, { cmd: true, also: "queue full" }),
    K("upstreamRetries", "Tries before a request fails", { k: "count", opts: [1, 2, 3, 4, 6, 8], unit: "tries" }, 4, { cmd: true, also: "retry retries attempts" }),
    K("quotaPoll", "Read plan limits", { k: "pick", opts: [["auto", "On"], ["off", "Off"]] }, "auto", { also: "quota" }),
    K("quotaPollIntervalMs", "Read plan limits every", { k: "dur", opts: [MIN, 2 * MIN, 5 * MIN, 10 * MIN, 15 * MIN], unit: "min" }, 5 * MIN, { home: "plan" }),
    K("usageWarnPct", "Warn when a plan reaches", { k: "count", opts: [50, 70, 80, 90, 95], unit: "%" }, 80, { home: "plan", also: "warning" }),
    K("usageWarnTokens5h", "5-hour limit if none is reported", { k: "count", opts: [0, 1e6, 5e6, 10e6, 25e6], none: "Off", tok: true, unit: "tokens out" }, 0, { home: "plan", also: "warning" }),
    K("budgetDefaultAction", "A new budget, when it's spent", { k: "pick", opts: [["warn", "Warns"], ["block", "Blocks"]] }, "warn", { home: "plan", also: "spend money" }),
    K("effort", "Reasoning effort", { k: "pick", opts: [[null, "Model's own"], ["minimal", "Minimal"], ["low", "Low"], ["medium", "Medium"], ["high", "High"], ["xhigh", "Extra high"]] }, null),
    K("summary", "Reasoning summaries", { k: "pick", opts: [["detailed", "Detailed"], ["concise", "Concise"], ["auto", "Auto"]] }, "detailed"),
    K("showReasoning", "Show reasoning in Claude Code", { k: "pick", opts: [["text", "As text"], ["thinking", "As thinking"], ["off", "Off"]] }, "text", { also: "display" }),
    K("replayReasoning", "Send earlier reasoning back", { k: "flag" }, false),
    K("progressLine", "Progress line while it thinks", { k: "flag" }, true),
    K("foldMaxContinue", "Keep thinking at most", { k: "count", opts: [1, 2, 3, 5, 8], unit: "times" }, 3, { also: "continue" }),
    // a request too large for splice gets a 413 before any turn exists (AdmissionResponses.kt:34)
    K("maxRequestBytes", "Largest request", { k: "count", opts: [8 * MB, 16 * MB, 32 * MB, 64 * MB], unit: "MB", per: MB }, 8 * MB, { home: "busy", also: "too large image size 413" }),
    // how long the daemon waits for a client to send a request body (RequestReadBudgetMs)
    K("requestReadTimeoutMs", "Longest wait for a request to arrive", { k: "dur", opts: [10 * SEC, 30 * SEC, 60 * SEC, 2 * MIN, 5 * MIN], unit: "s" }, 30 * SEC, { home: "busy", also: "slow client body timeout" }),
    // 0 is the daemon working the budget out from its own heap (Knob.kt:434-444), as maxInflight's 0 is Unlimited
    K("materializationHeapBytes", "Memory for reading requests", { k: "count", opts: [0, 256 * MB, 512 * MB, 1024 * MB, 2048 * MB], none: "Automatic", unit: "MB", per: MB }, 0, { home: "busy", also: "heap memory large requests" }),
    // the curve for a failure with no known cause: a 429 keeps its Retry-After and DNS its own plan (Knob.kt:231-234)
    K("retryBackoffBaseMs", "First wait between tries", { k: "dur", opts: [100, 200, 500, SEC, 2 * SEC], unit: "s" }, 200, { home: "busy", also: "retry backoff" }),
    K("retryBackoffCapMs", "Longest wait between tries", { k: "dur", opts: [5 * SEC, 10 * SEC, 30 * SEC, MIN], unit: "s" }, 10 * SEC, { home: "busy", also: "retry backoff ceiling" }),
    K("retryBackoffJitterPct", "Each wait varies by", { k: "count", opts: [0, 10, 25, 50], unit: "%", pre: "±" }, 10, { home: "busy", also: "retry backoff jitter random" }),
    K("traceRetentionDays", "Keep prompts and answers for", { k: "count", opts: [1, 3, 7, 14, 30], unit: "days" }, 7, { home: "data" }),
    K("traceMaxBodyChars", "Longest saved prompt", { k: "count", opts: [4 * MB, 8 * MB, 16 * MB, 32 * MB], unit: "M characters", per: MB }, 16 * MB, { home: "data" }),
    // one setting for the usage and request history (the hourly totals and the request records), what Usage and
    // Requests can look back on: 35 days on a fresh install, Forever a real choice (Marlin, Oct 10, from Marcos: "it
    // should keep as much as the user wants it to keep"). Forever is null, and Today only is 0, a choice never typed:
    // a daily budget needs today's spend, so it keeps today and drops it at midnight. A Custom field takes whole days
    // from 1. A shorter window waits for the page's yes (ask), since it deletes what falls outside it.
    K("historyRetentionDays", "Keep usage and request history for", { k: "count", opts: [0, 7, 14, 35, 90, 365, null], unit: "days", none: "Forever", zero: "Today only", min: 1 }, 35,
      { home: "data", ask: true, also: "history retention disk space forever usage requests records month year" }),
    K("messageEdges", "Save who messaged whom", { k: "flag" }, true, { home: "data" }),
    K("transcriptView", "Read your Claude Code conversations", { k: "flag" }, true, { home: "data" }),
    K("mcpMaxServers", "Most servers at once", { k: "count", opts: [8, 16, 32, 64], unit: "servers" }, 32, { home: "mcp" }),
    K("mcpIdleTimeoutMs", "Stop an unused server after", { k: "dur", opts: [5 * MIN, 15 * MIN, 30 * MIN, HR], unit: "min" }, 30 * MIN, { home: "mcp" }),
    K("mcpRequestTimeoutMs", "Longest tool call", { k: "dur", opts: [5 * MIN, 15 * MIN, 30 * MIN, HR], unit: "min" }, 30 * MIN, { home: "mcp" }),
    K("mcpInitializeTimeoutMs", "Wait for a server to start", { k: "dur", opts: [30 * SEC, 60 * SEC, 2 * MIN], unit: "s" }, 60 * SEC, { home: "mcp" }),
  ];
  const KNOB = Object.fromEntries(LIST.map((k) => [k.key, k]));

  // What the daemon answered for GET /api/config, the menu or Custom field a click opened, and what each change did.
  // `perCommand` stays false until a command's own value can be written (PUT /api/topology, a splice.toml write): a row never offers "One command" it cannot save.
  // `providerOf` maps a command to its provider, which is all kit.js knows a colour by; a page fills it from GET /api/models.
  // `cfg` is null until load() returns: a page draws no value it has not read.
  const st = {
    cfg: null, providerOf: {}, perCommand: false, cmds: [], said: {}, bad: {}, slow: {}, menu: null, custom: null, onAsk: () => false,
  };

  /** GET /api/config, and the commands a per-command value can name. Answers the error text, or null when it read. */
  async function load(cmds) {
    const read = await API.get("/api/config");
    if (!read.ok || read.body === null) return read.status === 0 ? "splice is not answering" : `splice answered ${read.status}`;
    st.cfg = read.body;
    if (cmds) st.cmds = cmds;
    return null;
  }

  const layer = (name) => (st.cfg?.layers?.[name] ?? {});
  /** The commands that hold their own value for [key], from the per-head layer the payload carries. */
  function ownersOf(key) {
    const per = layer("perHead");
    return Object.keys(per).filter((c) => per[c] !== null && typeof per[c] === "object" && key in per[c]).sort();
  }
  const ownValue = (key, cmd) => layer("perHead")[cmd]?.[key];
  /** The restart-required keys the daemon itself names, so this file never carries a second copy of that list. */
  const needsRestart = (key) => (st.cfg?.restart_required_keys ?? []).includes(key);

  // where the effective value comes from, in ConfigService's own order
  function valueOf(k) {
    for (const [name, src] of [["runtime", "here"], ["env", "env"], ["file", "file"], ["toml", "toml"]]) {
      const l = layer(name);
      if (k.key in l && l[k.key] !== null) return { v: l[k.key], src, name: k.key };
    }
    const effective = st.cfg?.effective ?? {};
    return { v: k.key in effective ? effective[k.key] : k.def, src: "default" };
  }
  const val = (key) => valueOf(KNOB[key]).v;
  function word(k, v) {
    const c = k.c;
    if (c.k === "dur") return v === 0 && c.none ? c.none : dur(Number(v));
    if (c.k === "count") {
      const n = v === null ? null : Number(v);
      return n === 0 && c.zero ? c.zero : (n === 0 || n === null) && c.none ? c.none : n === 1 && c.unit === "days" ? "1 day"
        : c.tok ? `${tok(n)} ${c.unit}` : `${c.pre || ""}${fmt(n / (c.per || 1))}${c.unit ? (c.unit === "%" ? "%" : ` ${c.unit}`) : ""}`;
    }
    if (c.k === "pick") return (c.opts.find(([o]) => String(o) === String(v)) || [, String(v)])[1];
    if (c.k === "flag") return v === true || v === "true" ? "On" : "Off";
    if (c.k === "models") return (Array.isArray(v) ? v : []).join(", ");
    return v === null || v === undefined ? (c.none || "") : String(v);
  }

  // ---------- controls ----------
  const cmdChip = (c, hl = esc) => `<span class="chip" style="--c:${colorOf(st.providerOf[c])}">${hl(c)}</span>`;
  function menuHtml(id, items) { // items: [value, word, meta]; "now" marks the value it holds
    return `<div class="menu" role="menu">${items.map(([o, w, m]) => `<button role="menuitem" data-kpick="${id}" data-v='${esc(JSON.stringify(o))}' aria-current="${m === "now"}">${esc(w)}${m && m !== "now" ? `<span class="mmeta">${esc(m)}</span>` : ""}</button>`).join("")}</div>`;
  }
  function ctlHtml(k, v, cmd = null) {
    const c = k.c, id = cmd ? `${k.key}@${cmd}` : k.key, open = st.menu === `pick:${id}`, bad = st.bad[id] ? ' aria-invalid="true"' : "";
    if (c.k === "dur" || c.k === "count" || (c.k === "pick" && c.opts.length > 3)) {
      if (st.custom === id) { // Custom: a field in the preset's unit, saved on Enter
        const unit = c.k === "dur" ? c.unit : c.unit || "";
        const shown = c.k === "dur" ? Number(v) / (unit === "min" ? MIN : SEC) : Number(v) / (c.per || 1);
        const empty = v === null || v === undefined || (Number(v) === 0 && c.zero);
        return `<span class="custom"><input class="field" inputmode="decimal" data-kcustom="${id}" value="${empty ? "" : esc(String(+shown.toFixed(2)))}" aria-label="${esc(k.label)}"${bad}><em>${esc(unit)}</em></span>`;
      }
      const opts = c.k === "pick" ? c.opts : c.opts.map((o) => [o, word(k, o)]);
      const items = opts.map(([o, w]) => [o, w, String(o) === String(v) ? "now" : String(o) === String(k.def) ? "splice's default" : ""]);
      if (c.k !== "pick" && !opts.some(([o]) => String(o) === String(v))) items.unshift([v, word(k, v), "now"]);
      if (c.k !== "pick") items.push(["__custom", "Custom…", ""]);
      return `<span class="menuwrap"><button class="pickbtn" data-kmenu="pick:${id}" aria-expanded="${open}"${bad}>${esc(word(k, v))}${ICON.caret}</button>${open ? menuHtml(id, items) : ""}</span>`;
    }
    if (c.k === "pick" || c.k === "flag") {
      const opts = c.k === "flag" ? [[true, "On"], [false, "Off"]] : c.opts;
      const now = c.k === "flag" ? v === true || v === "true" : v;
      return `<span class="switch">${opts.map(([o, w]) => `<button data-kpick="${id}" data-v='${esc(JSON.stringify(o))}' aria-pressed="${String(o) === String(now)}">${esc(w)}</button>`).join("")}</span>`;
    }
    return "";
  }
  function saidHtml(id) {
    if (st.bad[id]) return `<span class="said limit">${esc(st.bad[id])}</span>`;
    if (st.slow[id]) return `<span class="said">${ICON.wait}${esc(st.slow[id])}</span>`; // in flight: the wait ring and the word, like Starting
    return st.said[id] ? `<span class="said ok">${esc(st.said[id])}</span>` : "";
  }
  // one command's own value, and the value it goes back to: the shared one, drawn as the way back. It says it in
  // words, because a bare "↩ 16" changed nothing a walker could see and the next one guessed (fin, p75 and p76).
  function backHtml(k, c) {
    return `<button class="back" data-kact="unover" data-key="${k.key}" data-cmd="${c}">${BACK}<span>Back to ${esc(word(k, valueOf(k).v))}</span></button>`;
  }
  function overHtml(k, c, hl = esc) {
    return `<div class="over">${cmdChip(c, hl)}${ctlHtml(k, ownValue(k.key, c), c)}${backHtml(k, c)}${saidHtml(`${k.key}@${c}`)}</div>`;
  }
  // a setting's row in a form: its name, its control, what the change did, and the commands that hold their own value
  function rowHtml(k, { hl = esc, lit = "", label = k.label } = {}) {
    const s = valueOf(k);
    if (s.src === "env") { // set in the person's shell: read here, changed there
      return `<div class="lbl${lit}">${hl(label)}</div><div class="ctl"><span class="fixed">${esc(word(k, s.v))}</span><span class="from">Set in your shell, <code>${esc(k.key)}</code></span></div>`;
    }
    const owners = ownersOf(k.key), left = (k.only || st.cmds).filter((c) => !owners.includes(c)), open = st.menu === `over:${k.key}`;
    const lines = owners.map((c) => overHtml(k, c, hl)).join("");
    const add = st.perCommand && k.cmd && left.length ? `<span class="menuwrap"><button class="act quiet small one" data-kmenu="over:${k.key}" aria-expanded="${open}">${PLUS}One command</button>`
      + (open ? `<div class="menu" role="menu">${left.map((c) => `<button role="menuitem" data-kact="over" data-key="${k.key}" data-cmd="${c}"><span class="blot" style="--c:${colorOf(st.providerOf[c])}"></span>${esc(c)}</button>`).join("")}</div>` : "") + "</span>" : "";
    const only = k.only ? `<span class="only">${k.only.map((c) => cmdChip(c, hl)).join("")}</span>` : "";
    return `<div class="lbl${lit}" data-row="${k.key}">${hl(label)}</div><div class="ctl col"><div class="line">${ctlHtml(k, s.v)}${only}${saidHtml(k.key)}${add}</div>${lines}</div>`;
  }
  const formHtml = (keys, opts) => `<div class="form">${keys.map((key) => rowHtml(KNOB[key], opts)).join("")}</div>`;

  // ---------- changing a setting: PATCH /api/config with the one key ----------
  // The answer decides the word: the daemon says what it applied, what it refused and why, and which applied keys it
  // does not read until it restarts. `restart` is handed back rather than written on the row.
  async function patch(id, v, sure = false) {
    const [key, cmd] = id.split("@");
    const k = KNOB[key];
    if (k.ask && !sure && !Number.isNaN(v) && st.onAsk(id, v)) return { held: true };
    if (Number.isNaN(v)) { st.bad[id] = "Not saved"; delete st.said[id]; return { bad: true }; }
    if (cmd) return { unsupported: true }; // a command's own value is a splice.toml write (PUT /api/topology), not this
    delete st.bad[id];
    const answer = await API.patch("/api/config", { [key]: v });
    const body = answer.body ?? {};
    const refused = body.rejected?.[key];
    if (!answer.ok || refused || !(body.applied && key in body.applied)) {
      st.bad[id] = refused ? `Not saved: ${refused}` : "Not saved";
      delete st.said[id];
      return { bad: true, reason: refused };
    }
    delete st.bad[id];
    const restart = (body.restart_required ?? []).includes(key) || needsRestart(key);
    if (!restart) st.said[id] = "Applied";
    return { ok: true, restart, persisted: body.persisted ?? null };
  }
  function fromField(id, raw) {
    const [key] = id.split("@"), c = KNOB[key].c, s = String(raw).trim();
    if (s === "") return c.none && !c.min ? 0 : KNOB[key].def;
    const n = Number(s.replace(/,/g, ""));
    if (!Number.isFinite(n) || n < 0 || (c.min && n < c.min)) return NaN;
    if (c.k === "dur") return n * (c.unit === "min" ? MIN : SEC);
    return Math.round(n * (c.per || 1));
  }
  // A click the kit owns: it answers true when the page should redraw. A change goes to the daemon after that redraw
  // and calls [render] again with its answer, so the row never shows a word the daemon has not given.
  function click(e, render = () => {}) {
    const t = e.target.closest("[data-kmenu], [data-kpick], [data-kact], [data-kon]");
    if (!t) {
      if (st.menu && !e.target.closest(".menu")) { st.menu = null; return true; }
      return false;
    }
    const d = t.dataset;
    if (d.kmenu) { st.menu = st.menu === d.kmenu ? null : d.kmenu; return true; }
    st.menu = null;
    if (d.kpick) {
      const v = JSON.parse(d.v);
      if (v === "__custom") st.custom = d.kpick;
      else sent(d.kpick, v, render);
      return true;
    }
    if (d.kact === "over" || d.kact === "unover") { st.bad[`${d.key}@${d.cmd}`] = "Not saved: one command's own value is a splice.toml change"; return true; }
    return true;
  }
  /** Sends the change and redraws when the daemon answers; the row says "Saving" with the wait ring while it is out. */
  function sent(id, v, render, sure = false) {
    st.slow[id] = "Saving";
    delete st.said[id];
    patch(id, v, sure).then((r) => { delete st.slow[id]; render(r); }).catch(() => { delete st.slow[id]; st.bad[id] = "Not saved"; render(); });
  }
  // a Custom field saves on Enter; left without Enter it goes back to its menu, and the redraw waits a tick, since
  // Chrome fires focusout while innerHTML replaces the field
  function wire(root, render) {
    root.addEventListener("keydown", (e) => {
      const d = e.target.dataset;
      if (e.key === "Enter" && d.kcustom) { const id = d.kcustom; st.custom = null; sent(id, fromField(id, e.target.value), render); render(); }
      if (e.key === "Escape" && (d.kcustom || st.menu)) { st.custom = null; st.menu = null; render(); }
    });
    root.addEventListener("focusout", (e) => { if (e.target.dataset.kcustom && st.custom === e.target.dataset.kcustom) { st.custom = null; setTimeout(render); } });
  }
  const focusCustom = (root) => { const f = root.querySelector("[data-kcustom]"); if (f) { f.focus(); f.select(); } };

  return {
    fmt, SEC, MIN, HR, MB, dur, tok, LIST, KNOB, st, load, valueOf, val, word, needsRestart, ownersOf, ownValue,
    cmdChip, ctlHtml, saidHtml, rowHtml, formHtml, patch, sent, click, wire, focusCustom,
  };
})();
