// splice's settings as one kit: each setting, its control and what a change did, drawn the same wherever a page reaches
// it (Settings, the gear on Requests, a command's pane on Models). Everything sits in KNOBS, so a page that loads it
// gains one global. A page wires it once (KNOBS.wire), loads it (KNOBS.load), asks it first on every click
// (KNOBS.click), and redraws when it answers true. Load after kit.js (esc, ICON, G, colorOf) and api.js (API).
//
// WHERE EACH VALUE COMES FROM. GET /api/config's knobs block answers each setting's effective value, the scope that set
// it (runtime > env > file > splice.toml > default, ConfigService's order, worked out by the daemon alone: KnobViews)
// and whether a change applies live or on restart. A command's own value is that command's [heads.KEY.overrides] layer,
// which the payload carries per head.
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

  // c: the control. dur: preset durations with a Custom value in `unit`. count: preset counts, 0 the `none` word.
  // pick: named choices (a switch up to three, else a menu). flag: On and Off. models: a list of models, picked.
  // only: the commands a setting acts on, drawn as their chips on its row. cmds: one switch per command.
  // more: cmd (a command can hold its own value), also (words a person might search with), home (the Settings job it
  // is drawn on), adv (the job whose Advanced disclosure holds it, v0.4.0 section 9), ask (a change the page holds until
  // the person says yes, because it deletes).
  const K = (key, label, c, def, more = {}) => ({ key, label, c, def, ...more });
  const LIST = [
    // the no-progress limit, the one wall that ends a turn: every model, tool or reasoning event renews it, so it bounds a
    // silence, never a turn's length (Watchdog.kt:6-7, :148-155, :390-398). The three tiers under it ask whether the
    // provider is alive and never end a turn on their own (Knob.kt:237-300)
    K("upstreamTimeoutMs", "Give up after no progress for", { k: "dur", opts: [10 * MIN, 15 * MIN, 20 * MIN, 30 * MIN, HR, 2 * HR], unit: "min" }, 15 * MIN, { cmd: true, home: "silent", also: "timeout limit cut off long turn wedged stuck" }),
    K("firstByteTimeoutMs", "Check the connection after no first word for", { k: "dur", opts: [30 * SEC, 60 * SEC, 90 * SEC, 2 * MIN, 5 * MIN], unit: "s" }, 90 * SEC,
      { cmd: true, home: "silent", also: "ask if a silent start is alive after first word first byte" }),
    K("streamIdleMs", "Ask if a silent answer is alive after", { k: "dur", opts: [30 * SEC, 60 * SEC, 90 * SEC, 2 * MIN, 5 * MIN], unit: "s" }, 90 * SEC, { cmd: true, home: "silent", also: "silence idle hang" }),
    // arms only on a provider measured to resume from a prefill (HeadBuildInputs.kt:168-171)
    K("stallReanchorMs", "Resume a silent answer after", { k: "dur", opts: [10 * SEC, 20 * SEC, 30 * SEC, 60 * SEC], unit: "s" }, 20 * SEC,
      { cmd: true, home: "silent", also: "resume", only: [] }),
    // admission per command (Knob.kt:186-194); attempts, the first included
    K("maxInflight", "Requests at once", { k: "count", opts: [4, 8, 12, 16, 24, 32, 100, 0], none: "Unlimited" }, 12, { cmd: true, also: "concurrent parallel per command" }),
    K("maxQueued", "Requests waiting in line", { k: "count", opts: [128, 256, 512, 1024, 0], none: "Unlimited" }, 512, { cmd: true, also: "queue full" }),
    K("upstreamRetries", "Tries before a request fails", { k: "count", opts: [1, 2, 3, 4, 6, 8, 10], unit: "tries" }, 4, { cmd: true, home: "busy", also: "retry retries attempts" }),
    K("quotaPoll", "Read plan limits", { k: "pick", opts: [["auto", "On"], ["off", "Off"]] }, "auto", { home: "plan", also: "quota" }),
    K("quotaPollIntervalMs", "Check plan usage every", { k: "dur", opts: [MIN, 2 * MIN, 5 * MIN, 10 * MIN, 15 * MIN], unit: "min" }, 5 * MIN,
      { home: "plan", also: "read plan limits every quota poll" }),
    K("usageWarnPct", "Warn when a plan reaches", { k: "count", opts: [50, 70, 80, 90, 95], unit: "%" }, 80, { home: "plan", also: "warning" }),
    K("usageWarnTokens5h", "5-hour limit if none is reported", { k: "count", opts: [0, 1e6, 5e6, 10e6, 25e6], none: "Off", tok: true, unit: "tokens out" }, 0,
      { home: "plan", also: "warning" }),
    K("budgetDefaultAction", "A new budget, when it's spent", { k: "pick", opts: [["warn", "Warns"], ["block", "Blocks"]] }, "warn",
      { home: "plan", also: "spend money" }),
    K("effort", "Reasoning effort", { k: "pick", opts: [[null, "Model's own"], ["minimal", "Minimal"], ["low", "Low"], ["medium", "Medium"], ["high", "High"], ["xhigh", "Extra high"]] }, null),
    // A NAME SAYS WHAT THE CONTROL DOES, AND NOTHING UNDER IT EXPLAINS (Marlin, Oct 12, ACCEPTANCE.md): a control he
    // cannot read is renamed first, then shown as an element where the fact exists, and only then given a sentence he
    // approves. The old names stay in `also` so a search for "summaries" still lands on the row that was renamed.
    K("summary", "Reasoning you see", { k: "pick", opts: [["detailed", "Full"], ["concise", "Short"], ["auto", "Model decides"]] }, "detailed",
      { home: "reasoning", also: "reasoning summaries thinking summary detailed concise auto" }),
    K("showReasoning", "Where reasoning shows", { k: "pick", opts: [["text", "In the reply"], ["thinking", "In the thinking block"], ["off", "Nowhere"]] }, "text",
      { home: "reasoning", also: "show reasoning in claude code display thinking as text" }),
    // OFF THE PAGE, NOT OFF THE DAEMON (Marlin, Oct 12): its only measured effect is thinner fresh reasoning
    // (Knob.kt:106-109, ~4x), it is off by default, and it is meant as an environment opt-in, so it serves no job he
    // has. No `home`: SettingsCoverageTest carries it in offPage with that reason.
    K("replayReasoning", "Send earlier reasoning back", { k: "flag" }, false, { also: "thinking replay" }),
    K("progressLine", "Waiting line in the terminal", { k: "flag" }, true,
      { home: "reasoning", also: "progress line while it thinks waiting status" }),
    // one row, read as a sentence: "When thinking stops short, continue [models] up to [3 times]" (foldCard below)
    K("foldReasoningModels", "When thinking stops short, continue", { k: "models" }, "gpt-5.6-luna,gpt-5.6-terra,gpt-5.5",
      { home: "reasoning", also: "keep thinking on continue fold" }),
    K("foldMaxContinue", "up to", { k: "count", opts: [1, 2, 3, 5, 8], unit: "times" }, 3,
      { home: "reasoning", also: "keep thinking at most continue" }),
    // a request too large for splice gets a 413 before any turn exists (AdmissionResponses.kt:34)
    K("maxRequestBytes", "Largest request", { k: "count", opts: [8 * MB, 16 * MB, 32 * MB, 64 * MB], unit: "MB", per: MB }, 8 * MB, { home: "busy", also: "too large image size 413" }),
    // how long the daemon waits for a client to send a request body (RequestReadBudgetMs)
    K("requestReadTimeoutMs", "Longest wait for a request to arrive", { k: "dur", opts: [10 * SEC, 30 * SEC, 60 * SEC, 2 * MIN, 5 * MIN], unit: "s" }, 30 * SEC, { adv: "busy", also: "slow client body timeout" }),
    // 0 is the daemon working the budget out from its own heap (Knob.kt:434-444), as maxInflight's 0 is Unlimited
    K("materializationHeapBytes", "Memory for reading requests", { k: "count", opts: [0, 256 * MB, 512 * MB, 1024 * MB, 2048 * MB], none: "Automatic", unit: "MB", per: MB }, 0, { adv: "busy", also: "heap memory large requests" }),
    // the curve for a failure with no known cause: a 429 keeps its Retry-After and DNS its own plan (Knob.kt:231-234)
    K("retryBackoffBaseMs", "First wait between tries", { k: "dur", opts: [100, 200, 500, SEC, 2 * SEC], unit: "s" }, 200, { adv: "busy", also: "retry backoff" }),
    K("retryBackoffCapMs", "Longest wait between tries", { k: "dur", opts: [5 * SEC, 10 * SEC, 30 * SEC, MIN], unit: "s" }, 10 * SEC, { adv: "busy", also: "retry backoff ceiling" }),
    K("retryBackoffJitterPct", "Each wait varies by", { k: "count", opts: [0, 10, 25, 50], unit: "%", pre: "±" }, 10, { adv: "busy", also: "retry backoff jitter random" }),
    K("traceRetentionDays", "Keep prompts and answers for", { k: "count", opts: [1, 3, 7, 14, 30], unit: "days" }, 7, { home: "data" }),
    K("traceMaxBodyChars", "Longest saved prompt", { k: "count", opts: [4 * MB, 8 * MB, 16 * MB, 32 * MB], unit: "M characters", per: MB }, 16 * MB, { home: "data" }),
    // one setting for the usage and request history (the hourly totals and the request records), what Usage and
    // Requests can look back on: 35 days on a fresh install, Forever a real choice (Marlin, Oct 10, from Marcos: "it
    // should keep as much as the user wants it to keep"). Forever is null, and Today only is 0, a choice never typed:
    // a daily budget needs today's spend, so it keeps today and drops it at midnight. A Custom field takes whole days
    // from 1. A shorter window waits for the page's yes (ask), since it deletes what falls outside it.
    K("historyRetentionDays", "Keep usage and request history for", { k: "count", opts: [0, 7, 14, 35, 90, 365, null], unit: "days", none: "Forever", zero: "Today only", min: 1 }, 35,
      { home: "data", ask: true, also: "history retention disk space forever usage requests records month year" }),
    K("activityRetentionDays", "Keep what each agent was doing for", { k: "count", opts: [7, 30, 90, 180, 365], unit: "days", min: 1 }, 90, { home: "data", also: "activity labels teams agents file names commands" }),
    K("messageEdges", "Save who messaged whom", { k: "flag" }, true, { home: "data" }),
    K("transcriptView", "Read your Claude Code conversations", { k: "flag" }, true, { home: "data" }),
    K("keepTranscriptCopies", "Keep a copy before moving a conversation to another model", { k: "flag" }, true, { home: "data", also: "transcript copies originals continue on another model resume" }),
    K("transcriptCopyDays", "Keep transcript copies for", { k: "count", opts: [7, 14, 30, 90, 365], unit: "days", min: 1 }, 30, { home: "data", also: "transcript copies originals retention days" }),
    K("keepCompactionSummaries", "Keep compaction summaries for a retry", { k: "flag" }, true, { home: "data", also: "compaction summary retry parked" }),
    K("keepCodeModeWork", "Keep code mode's journal on disk", { k: "flag" }, true, { home: "data", also: "code mode journal work scripts restart" }),
    K("mcpMaxServers", "Run at most", { k: "count", opts: [8, 16, 32, 64], unit: "servers" }, 32,
      { home: "mcp", also: "most servers at once ceiling" }),
    K("mcpIdleTimeoutMs", "Stop an unused server after", { k: "dur", opts: [5 * MIN, 15 * MIN, 30 * MIN, HR], unit: "min" }, 30 * MIN, { home: "mcp" }),
    // WHOSE CALL IT IS, IN THE NAME (fin, Marlin's ruling after the walk of Oct 12): this is the budget for one request
    // splice forwards to an MCP server, and in Claude Code "tool call" means its own Bash too. Both walkers came here
    // to stop a hung test command and left half-believing this covered it. Splice never runs that command. The old
    // words are gone from the search as well: a search for a hung command that finds nothing is the honest answer.
    K("mcpRequestTimeoutMs", "Give up on a server's reply after", { k: "dur", opts: [5 * MIN, 15 * MIN, 30 * MIN, HR], unit: "min" }, 30 * MIN,
      { home: "mcp", also: "mcp server reply request timeout wait" }),
    K("mcpInitializeTimeoutMs", "Give up on a starting server after", { k: "dur", opts: [30 * SEC, 60 * SEC, 2 * MIN], unit: "s" }, 60 * SEC,
      { home: "mcp", also: "wait for a server to start handshake" }),
    // THE KIT HOLDS THE VALUE, SETTINGS DRAWS THE ROW: a list of folders is a row of its own, each path with the way to
    // remove it and the projects it makes under it, which no control in this kit draws. `paths` is a kind ctlHtml has
    // no branch for, so the generic row would be a name with nothing beside it; Settings skips the key and draws its
    // own card. Here for the value, the search words and the daemon's live/restart answer.
    K("projectsFolders", "Projects folder", { k: "paths" }, "",
      { home: "projects", also: "projects folder folders group grouping workspace monorepo" }),
  ];
  const KNOB = Object.fromEntries(LIST.map((k) => [k.key, k]));

  // What the daemon answered for GET /api/config, the menu or Custom field a click opened, and what each change did.
  // `perCommand` is whether a page offers "One command" on a setting's row. A command's own value is written by
  // PATCH /api/config?head=<command> (HeadOverrideWrite.kt), which edits [heads.<command>.overrides] in splice.toml.
  // `adding` holds, per setting, the commands picked from that menu whose own value is not saved yet.
  // `restart` holds the rows saved while the running splice still reads the old value, until a restart brings them in.
  // `providerOf` maps a command to its provider, which is all kit.js knows a colour by; a page fills it from GET /api/models.
  // `cfg` is null until load() returns: a page draws no value it has not read.
  const st = {
    cfg: null, models: [], providerOf: {}, commandOf: {}, perCommand: true, cmds: [], adding: {}, restart: {}, restarting: false,
    said: {}, bad: {}, slow: {}, menu: null, custom: null, onAsk: () => false,
  };

  /** GET /api/config, and the commands a per-command value can name. Answers the error text, or null when it read. */
  async function load(cmds) {
    const read = await API.get("/api/config");
    if (!read.ok || read.body === null) { console.warn(`knobs: ${read.status || "no answer"}`); return read.status === 0 ? "Not answering" : "Not read"; }
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
  /** Whether the running daemon reads [key] only at start: the knob's own disposition, as the daemon names it. */
  const needsRestart = (key) => st.cfg?.knobs?.[key]?.disposition === "restart";

  // the effective value and the scope that set it, as the daemon's knobs block says (spec 9)
  const SOURCE = { runtime: "here", env: "env", file: "file", toml: "toml" };
  function valueOf(k) {
    const knob = st.cfg?.knobs?.[k.key];
    const src = SOURCE[knob?.scope];
    return src ? { v: knob.value, src, name: k.key } : { v: knob?.value ?? k.def, src: "default" };
  }
  const val = (key) => valueOf(KNOB[key]).v;
  // A models setting is a comma list of the ids a command serves; the page names each by the label GET /api/models gives it.
  // `st.models` is every model the commands that take this setting serve, a page fills it from that route.
  const idsOf = (v) => String(v ?? "").split(",").map((x) => x.trim()).filter(Boolean);
  const labelOf = (id) => st.models.find((m) => m.id === id)?.label ?? id;
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
    if (c.k === "models") return idsOf(v).map(labelOf).join(", ");
    return v === null || v === undefined ? (c.none || "") : String(v);
  }

  // ---------- controls ----------
  // a chip names the command a person types, claude-openrouter, never the head key splice files it under (`commandOf`,
  // filled from GET /api/heads' label; marlin, Oct 12: Today read openrouter and side-project)
  const cmdChip = (c, hl = esc) => `<span class="chip" style="--c:${colorOf(st.providerOf[c])}">${hl(st.commandOf[c] || c)}</span>`;
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
    if (c.k === "models") {
      const ids = idsOf(v), left = st.models.filter((m) => !ids.includes(m.id)), mopen = st.menu === `model:${id}`;
      return `<span class="mlist">${ids.map((m) => `<span class="mchip">${esc(labelOf(m))}<button class="icon" data-kact="unmodel" data-key="${k.key}" data-m="${esc(m)}" aria-label="Remove ${esc(labelOf(m))}">${ICON.close}</button></span>`).join("")}`
        + (left.length ? `<span class="menuwrap"><button class="act quiet small" data-kmenu="model:${id}" aria-expanded="${mopen}">${PLUS}Add model</button>`
          + (mopen ? `<div class="menu" role="menu">${left.map((m) => `<button role="menuitem" data-kact="model" data-key="${k.key}" data-m="${esc(m.id)}">${esc(m.label)}</button>`).join("")}</div>` : "") + "</span>" : "") + "</span>";
    }
    return "";
  }
  function saidHtml(id) {
    if (st.bad[id]) return `<span class="said limit">${esc(st.bad[id])}</span>`;
    // saved to splice.toml, read by the running splice only once it restarts: the act that brings it in sits beside it
    if (st.restart[id]) {
      return st.restarting ? `<span class="said">${ICON.wait}Restarting</span>`
        : `<span class="said ok">Saved</span><button class="act quiet small" data-kact="restart">Restart splice</button>`;
    }
    if (st.slow[id]) return `<span class="said">${ICON.wait}${esc(st.slow[id])}</span>`; // in flight: the wait ring and the word, like Starting
    if (st.said[id]) return `<span class="said ok">${esc(st.said[id])}</span>`;
    // a setting the running splice reads only when it starts says so at rest, in the spec's words (v0.4.0 section 9,
    // Resolved: boot-only stays editable, labelled "applies on restart"); saving it then offers Restart splice above
    return needsRestart(id.split("@")[0]) ? `<span class="said">Applies on restart</span>` : "";
  }
  // A command's own value: its control, what the change did, and the way back to the value every other command uses.
  // A command just picked from "One command" has nothing saved yet, so its control shows the shared value until a change.
  const backHtml = (k, c) =>
    `<button class="back" data-kact="back" data-key="${k.key}" data-cmd="${esc(c)}">Back to ${esc(word(k, valueOf(k).v))}</button>`;
  function overHtml(k, c, hl = esc) {
    const own = ownValue(k.key, c);
    return `<div class="over">${cmdChip(c, hl)}${ctlHtml(k, own === undefined ? valueOf(k).v : own, c)}${saidHtml(`${k.key}@${c}`)}${backHtml(k, c)}</div>`;
  }
  /** One command's own settings, for a page that shows one command at a time (a command's pane on Models). Only the
   *  settings this command holds its own value for show, each under its Settings name with the control on that value
   *  and the way back; "Add setting" lists the rest, each beside the value every command uses. A change saves the
   *  command's own value through the same PATCH as Settings' rows. Clicks go through KNOBS.click as any row. */
  function commandRowsHtml(cmd, keys, { hl = esc } = {}) {
    const all = keys.filter((key) => KNOB[key]);
    const held = (key) => ownValue(key, cmd) !== undefined || (st.adding[key] ?? []).includes(cmd);
    const open = st.menu === `own:${cmd}`, left = all.filter((key) => !held(key));
    const rows = all.filter(held).map((key) => {
      const k = KNOB[key], own = ownValue(key, cmd);
      return `<div class="lbl" data-row="${key}">${hl(k.label)}</div><div class="ctl col"><div class="line">`
        + `${ctlHtml(k, own === undefined ? valueOf(k).v : own, cmd)}${saidHtml(`${key}@${cmd}`)}${backHtml(k, cmd)}</div></div>`;
    }).join("");
    const add = left.length ? `<div class="lbl"></div><div class="ctl"><span class="menuwrap"><button class="act quiet small" data-kmenu="own:${cmd}" aria-expanded="${open}">${PLUS}Add setting</button>`
      + (open ? `<div class="menu" role="menu">${left.map((key) => `<button role="menuitem" data-kact="over" data-key="${key}" data-cmd="${cmd}">${esc(KNOB[key].label)}<span class="mmeta">${esc(word(KNOB[key], valueOf(KNOB[key]).v))}</span></button>`).join("")}</div>` : "") + "</span></div>" : "";
    return rows + add;
  }
  // a setting's row in a form: its name, its control, what the change did, and the commands that hold their own value
  function rowHtml(k, { hl = esc, lit = "", label = k.label, everyCommand = false, el = "" } = {}) {
    const s = valueOf(k);
    if (s.src === "env") { // set in the person's shell: read here, changed there
      return `<div class="lbl${lit}">${hl(label)}</div><div class="ctl"><span class="fixed">${esc(word(k, s.v))}</span><span class="from">Set in your shell, <code>${esc(k.key)}</code></span></div>`;
    }
    const owners = [...ownersOf(k.key), ...(st.adding[k.key] ?? []).filter((c) => !ownersOf(k.key).includes(c))];
    const left = (k.only || st.cmds).filter((c) => !owners.includes(c)), open = st.menu === `over:${k.key}`;
    const lines = owners.map((c) => overHtml(k, c, hl)).join("");
    const add = st.perCommand && k.cmd && left.length ? `<span class="menuwrap"><button class="act quiet small one" data-kmenu="over:${k.key}" aria-expanded="${open}">${PLUS}One command</button>`
      + (open ? `<div class="menu" role="menu">${left.map((c) => `<button role="menuitem" data-kact="over" data-key="${k.key}" data-cmd="${c}"><span class="blot" style="--c:${colorOf(st.providerOf[c])}"></span>${esc(st.commandOf[c] || c)}</button>`).join("")}</div>` : "") + "</span>" : "";
    const only = k.only ? `<span class="only">${k.only.map((c) => cmdChip(c, hl)).join("")}</span>` : (everyCommand && k.cmd ? `<span class="only every">${owners.length ? "Every other command" : "Every command"}</span>` : "");
    // THE SLOT UNDER A CONTROL HOLDS AN ELEMENT, NEVER A SENTENCE (Marlin, Oct 12): the page passes what it already
    // knows — the last read, the mark on a bar, the providers reporting nothing, how many servers run — and passes
    // nothing where that fact is not on the wire. A row with no element is its name and its control.
    return `<div class="lbl${lit}" data-row="${k.key}">${hl(label)}</div><div class="ctl col"><div class="line">${ctlHtml(k, s.v)}${only}${saidHtml(k.key)}${add}</div>${lines}</div>${el}`;
  }

  // ---------- changing a setting: PATCH /api/config with the one key ----------
  // The answer decides the word: the daemon says what it applied, what it refused and why, and which applied keys it
  // does not read until it restarts. `restart` is handed back rather than written on the row.
  async function patch(id, v, sure = false) {
    const [key, cmd] = id.split("@");
    const k = KNOB[key];
    if (k.ask && !sure && !Number.isNaN(v) && st.onAsk(id, v)) return { held: true };
    if (Number.isNaN(v)) { st.bad[id] = "Not saved"; delete st.said[id]; return { bad: true }; }
    if (cmd) return patchOwn(id, key, cmd, v);
    delete st.bad[id];
    const answer = await API.patch("/api/config", { [key]: v });
    const body = answer.body ?? {};
    const refused = body.rejected?.[key];
    if (!answer.ok || refused || !(body.applied && key in body.applied)) {
      if (refused || !answer.ok) console.warn(`${key}: ${refused ?? "no answer"}`);
      st.bad[id] = "Not saved";
      delete st.said[id];
      return { bad: true, reason: refused };
    }
    // the daemon said what it applied: the row reads that now, not the value the page last fetched
    st.cfg = { ...st.cfg, knobs: { ...st.cfg?.knobs, [key]: { ...st.cfg?.knobs?.[key], value: body.applied[key], scope: "runtime" } } };
    delete st.bad[id];
    const restart = (body.restart_required ?? []).includes(key) || needsRestart(key);
    if (!restart) st.said[id] = "Applied";
    return { ok: true, restart, persisted: body.persisted ?? null };
  }
  // A command's own value: PATCH /api/config?head=<command> writes it to [heads.<command>.overrides], and null clears it
  // so the command takes the shared value again. The answer names the keys the running splice reads only at start.
  async function patchOwn(id, key, cmd, v) {
    delete st.bad[id];
    const answer = await API.patch(`/api/config?head=${encodeURIComponent(cmd)}`, { [key]: v });
    const body = answer.body ?? {};
    const refused = body.rejected?.[key];
    if (!answer.ok || refused || !(body.applied && key in body.applied)) {
      console.warn(`${key} for ${cmd}: ${refused ?? body.error ?? "no answer"}`);
      st.bad[id] = "Not saved";
      delete st.said[id];
      return { bad: true, reason: refused };
    }
    const mine = { ...(layer("perHead")[cmd] ?? {}) };
    if (v === null) delete mine[key]; else mine[key] = v;
    st.cfg = { ...st.cfg, layers: { ...st.cfg?.layers, perHead: { ...layer("perHead"), [cmd]: mine } } };
    st.adding[key] = (st.adding[key] ?? []).filter((c) => c !== cmd);
    const restart = (body.restart_required ?? []).includes(key);
    // a cleared value leaves Settings' row, so what it did is said on the setting's own line there as well
    const at = v === null ? [id, key] : [id];
    for (const x of at) {
      delete st.bad[x];
      if (restart) { st.restart[x] = true; delete st.said[x]; } else { delete st.restart[x]; st.said[x] = "Applied"; }
    }
    return { ok: true, restart, persisted: body.persisted ?? null };
  }
  /** Restart splice for the rows saved under it, then read the values it came back with: those rows read Applied. */
  async function restart(render) {
    if (st.restarting) return;
    const ids = Object.keys(st.restart);
    st.restarting = true; render();
    const go = await API.post("/api/daemon/restart", {});
    const done = async (word) => {
      st.restarting = false;
      for (const x of ids) { if (word === "Applied") { delete st.restart[x]; st.said[x] = word; } else st.bad[x] = word; }
      if (word === "Applied") await load();
      render();
    };
    if (!go.ok) { if (go.body?.error) console.warn(`restart: ${go.body.error}`); await done("Not restarted"); return; }
    // every read fails while splice is down; GET /api/daemon/restart answers idle once the new one is up
    for (let i = 0; i < 60; i++) {
      await new Promise((r) => setTimeout(r, 2000));
      const r = await API.get("/api/daemon/restart");
      if (r.ok && r.body?.status === "idle") { await done("Applied"); return; }
    }
    await done("Not back yet");
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
    if (d.kact === "model" || d.kact === "unmodel") {
      const ids = idsOf(valueOf(KNOB[d.key]).v);
      sent(d.key, (d.kact === "model" ? [...ids, d.m] : ids.filter((x) => x !== d.m)).join(","), render);
      return true;
    }
    // "One command": the command gets its line, with its value's menu open, and nothing is saved until a value is picked
    if (d.kact === "over") {
      st.adding[d.key] = [...(st.adding[d.key] ?? []), d.cmd];
      if (KNOB[d.key].c.k === "dur" || KNOB[d.key].c.k === "count" || KNOB[d.key].c.opts?.length > 3) st.menu = `pick:${d.key}@${d.cmd}`;
      return true;
    }
    if (d.kact === "back") {
      const id = `${d.key}@${d.cmd}`;
      if (ownValue(d.key, d.cmd) === undefined) { st.adding[d.key] = (st.adding[d.key] ?? []).filter((c) => c !== d.cmd); delete st.bad[id]; }
      else sent(id, null, render);
      return true;
    }
    if (d.kact === "restart") { restart(render); return true; }
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
    cmdChip, ctlHtml, saidHtml, rowHtml, commandRowsHtml, patch, sent, click, wire, focusCustom,
  };
})();
