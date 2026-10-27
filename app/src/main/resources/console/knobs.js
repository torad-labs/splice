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

const createKnobs = (scope) => {
  const { API, setTimeout } = scope;
  const fmt = (n) => Number(n).toLocaleString("en-US");
  const SEC = 1000, MIN = 60000, HR = 3600000, MB = 1048576;
  const dur = (ms) => (ms % HR === 0 ? `${ms / HR} h` : ms % MIN === 0 ? `${ms / MIN} min` : ms % SEC === 0 ? `${ms / SEC} s` : `${ms} ms`);
  const tok = (n) => (n >= 1e6 ? `${+(n / 1e6).toFixed(1)}M` : n >= 1e3 ? `${+(n / 1e3).toFixed(1)}K` : `${n}`);
  const PLUS = G('<path d="M12 5v14M5 12h14"/>');
  const LOCK = G('<rect x="5" y="11" width="14" height="10" rx="2"/><path d="M8 11V7a4 4 0 0 1 8 0v4"/>');
  // ONLY AN EXCEPTION CARRIES A MARK (the polish pass, Oct 26): a setting at splice's default that a change reaches at
  // once is the ordinary case and says nothing. "Default" and "Live" stood on 45 and 38 rows and told him nothing a
  // row without them would not. What is left are the four facts that change what a change does, each a glyph on the
  // value it belongs to, named in its title and said once in words by the page's legend (MARK_WORD).
  const MARK = {
    restart: G('<path d="M20 12a8 8 0 1 1-2.6-5.9"/><path d="M20 4v5h-5"/>'),
    toml: G('<path d="M14 3H7a2 2 0 0 0-2 2v14a2 2 0 0 0 2 2h10a2 2 0 0 0 2-2V8z"/><path d="M14 3v5h5M9 13h6M9 17h4"/>'),
    shell: G('<path d="M4 6l5 6-5 6M12 18h8"/>'),
    changed: '<svg viewBox="0 0 24 24" aria-hidden="true"><circle cx="12" cy="12" r="4.5" fill="currentColor"/></svg>',
    unsaved: G('<circle cx="12" cy="12" r="6" stroke-dasharray="3 3"/>'),
    lock: LOCK,
  };
  const MARK_WORD = {
    changed: "Changed from the default", restart: "Takes a restart", toml: "Set in splice.toml",
    shell: "Set in your shell", unsaved: "Gone at the next start", lock: "Locked",
  };
  const MARK_ORDER = ["changed", "restart", "toml", "shell", "unsaved", "lock"];
  const markHtml = (kind, word = MARK_WORD[kind]) =>
    `<span class="mk ${kind}" role="img" aria-label="${esc(word)}" title="${esc(word)}">${MARK[kind]}</span>`;
  /** The marks on one setting's value, as the daemon's answer makes them: what a reader could not guess from the row. */
  function marksOf(id) {
    const [key, cmd] = id.split("@"), k = KNOB[key];
    if (!k || !st.cfg) return [];
    const out = [];
    if (!cmd) {
      const src = valueOf(k).src;
      if (src === "file") out.push("changed");
      else if (src === "toml") out.push("toml");
      else if (src === "here") out.push("unsaved");
    }
    if (needsRestart(key)) out.push("restart");
    return out;
  }
  /** Every mark [keys] carry, each said once in words: a page draws this where its section names itself.
   *  [bare] gives the items alone, for a page putting its own words in the same line. */
  function legendHtml(keys, { bare = false } = {}) {
    const kinds = new Set();
    for (const key of keys) {
      if (st.cfg?.knobs?.[key]?.editable === false) kinds.add("lock");
      if (valueOf(KNOB[key] ?? { key }).src === "env") kinds.add("shell");
      for (const m of marksOf(key)) kinds.add(m);
    }
    const shown = MARK_ORDER.filter((m) => kinds.has(m));
    const items = shown.map((m) => `<span class="lg">${MARK[m]}${esc(MARK_WORD[m])}</span>`).join("");
    return !items || bare ? items : `<span class="legend">${items}</span>`;
  }

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
    K("firstByteTimeoutMs", "Check the connection if no output arrives for", { k: "dur", opts: [30 * SEC, 60 * SEC, 90 * SEC, 2 * MIN, 5 * MIN], unit: "s" }, 90 * SEC,
      { cmd: true, home: "silent", also: "ask if a silent start is alive after first word first byte" }),
    K("streamIdleMs", "Check a stalled answer is alive after", { k: "dur", opts: [30 * SEC, 60 * SEC, 90 * SEC, 2 * MIN, 5 * MIN], unit: "s" }, 90 * SEC, { cmd: true, home: "silent", also: "silence idle hang" }),
    // arms only on a provider measured to resume from a prefill (HeadBuildInputs.kt:168-171)
    K("stallReanchorMs", "Resume a stalled answer after", { k: "dur", opts: [10 * SEC, 20 * SEC, 30 * SEC, 60 * SEC], unit: "s" }, 20 * SEC,
      { cmd: true, home: "silent", also: "resume", only: [] }),
    // admission per command (Knob.kt:186-194); attempts, the first included
    K("maxInflight", "Requests at once", { k: "count", opts: [4, 8, 12, 16, 24, 32, 100, 0], none: "Unlimited" }, 12, { cmd: true, also: "concurrent parallel per command" }),
    K("maxQueued", "Requests waiting in line", { k: "count", opts: [128, 256, 512, 1024, 0], none: "Unlimited" }, 512, { cmd: true, also: "queue full" }),
    K("quotaPoll", "Read plan limits", { k: "pick", names: { auto: "On", off: "Off" } }, "auto", { home: "plan", also: "quota" }),
    K("quotaPollIntervalMs", "Check plan usage every", { k: "dur", opts: [MIN, 2 * MIN, 5 * MIN, 10 * MIN, 15 * MIN], unit: "min" }, 5 * MIN,
      { home: "plan", also: "read plan limits every quota poll" }),
    K("usageWarnPct", "Warn when a plan reaches", { k: "count", opts: [50, 70, 80, 90, 95], unit: "%" }, 80, { home: "plan", also: "warning" }),
    K("usageWarnTokens5h", "5-hour limit if none is reported", { k: "count", opts: [0, 1e6, 5e6, 10e6, 25e6], none: "Off", tok: true, unit: "tokens out" }, 0,
      { home: "plan", also: "warning" }),
    // what a budget made without an action of its own does once it is spent (PUT /api/budgets, Knob.kt BUDGET_DEFAULT_ACTION)
    K("budgetDefaultAction", "When a budget runs out", { k: "pick", names: { warn: "Warns", block: "Blocks" } }, "warn",
      { home: "plan", also: "spend money new budget spent" }),
    K("effort", "Reasoning effort", { k: "pick", unset: "Model's own", names: { none: "None", minimal: "Minimal", low: "Low", medium: "Medium", high: "High", xhigh: "Extra high", max: "Max" } }, null),
    // A NAME SAYS WHAT THE CONTROL DOES, AND NOTHING UNDER IT EXPLAINS (Marlin, Oct 12, ACCEPTANCE.md): a control he
    // cannot read is renamed first, then shown as an element where the fact exists, and only then given a sentence he
    // approves. The old names stay in `also` so a search for "summaries" still lands on the row that was renamed.
    K("summary", "Reasoning you see", { k: "pick", names: { detailed: "Full", concise: "Short", auto: "Model decides", none: "None" } }, "detailed",
      { home: "reasoning", also: "reasoning summaries thinking summary detailed concise auto" }),
    K("showReasoning", "Where reasoning shows", { k: "pick", names: { text: "In the reply", thinking: "In the thinking block", off: "Hidden" } }, "text",
      { home: "reasoning", also: "show reasoning in claude code display thinking as text" }),
    // OFF THE PAGE, NOT OFF THE DAEMON (Marlin, Oct 12): on by default for every head since Oct 23 (Knob.kt), and
    // turning it off is an environment or TOML choice, so it serves no job on the page. No `home`:
    // SettingsCoverageTest carries it in offPage with that reason.
    K("replayReasoning", "Send earlier reasoning back", { k: "flag" }, true, { also: "thinking replay" }),
    K("progressLine", "Waiting line in the terminal", { k: "flag" }, true,
      { home: "reasoning", also: "progress line while it thinks waiting status" }),
    // foldReasoningModels and foldMaxContinue retired in 0.4.0 (failure-handling slice 4): splice continues a codex
    // answer whose reasoning the backend cut on its own, so there is no setting to draw, and the daemon refuses the keys.
    // a request too large for splice gets a 413 before any turn exists (AdmissionResponses.kt:34)
    K("maxRequestBytes", "Largest request", { k: "count", opts: [8 * MB, 16 * MB, 32 * MB, 64 * MB], unit: "MB", per: MB }, 32 * MB, { home: "busy", also: "too large image size 413" }),
    // how long the daemon waits for a client to send a request body (RequestReadBudgetMs)
    K("requestReadTimeoutMs", "Drop a request still arriving after", { k: "dur", opts: [10 * SEC, 30 * SEC, 60 * SEC, 2 * MIN, 5 * MIN], unit: "s" }, 30 * SEC, { adv: "busy", also: "slow client body timeout longest wait for a request to arrive" }),
    // 0 is the daemon working the budget out from its own heap (Knob.kt:434-444), as maxInflight's 0 is Unlimited
    K("materializationHeapBytes", "Memory for reading requests", { k: "count", opts: [0, 256 * MB, 512 * MB, 1024 * MB, 2048 * MB], none: "Automatic", unit: "MB", per: MB }, 0, { adv: "busy", also: "heap memory large requests" }),
    // upstreamRetries and the three retryBackoff* waits retired in 0.4.0 (failure-handling slice 4): splice decides how
    // often and how soon a failed request is sent again from what failed, and the daemon refuses the keys. The
    // per-command test in tools/console/settings-per-command.test.ts used upstreamRetries as its second row; it moves to
    // another per-command row on the same page.
    K("traceRetentionDays", "Keep prompts and answers for", { k: "count", opts: [1, 3, 7, 14, 30], unit: "days" }, 7, { home: "data" }),
    K("traceMaxBodyChars", "Longest saved prompt", { k: "count", opts: [4 * MB, 8 * MB, 16 * MB, 32 * MB], unit: "M characters", per: MB }, 16 * MB, { home: "data" }),
    // one setting for the usage and request history (the hourly totals and the request records), what Usage and
    // Requests can look back on: 35 days on a fresh install, Forever a real choice (Marlin, Oct 10, from Marcos: "it
    // should keep as much as the user wants it to keep"). Forever is the daemon's word "forever" (null would delete the setting and bring the 35 days
    // back), and Today only is 0, a choice never typed:
    // a daily budget needs today's spend, so it keeps today and drops it at midnight. A Custom field takes whole days
    // from 1. A shorter window waits for the page's yes (ask), since it deletes what falls outside it.
    K("historyRetentionDays", "Keep usage and request history for", { k: "count", opts: [0, 7, 14, 35, 90, 365, "forever"], unit: "days", none: "Forever", zero: "Today only", min: 1 }, 35,
      { home: "data", ask: true, also: "history retention disk space forever usage requests records month year" }),
    K("activityRetentionDays", "Keep what each agent was doing for", { k: "count", opts: [7, 30, 90, 180, 365], unit: "days", min: 1 }, 90, { home: "data", also: "activity labels teams agents file names commands" }),
    K("messageEdges", "Save messages between agents", { k: "flag" }, true, { home: "data" }),
    K("transcriptView", "Read your Claude Code conversations", { k: "flag" }, true, { home: "data" }),
    K("keepTranscriptCopies", "Keep a copy before moving a conversation to another model", { k: "flag" }, true, { home: "data", also: "transcript copies originals continue on another model resume" }),
    K("transcriptCopyDays", "Keep transcript copies for", { k: "count", opts: [7, 14, 30, 90, 365], unit: "days", min: 1 }, 30, { home: "data", also: "transcript copies originals retention days" }),
    K("keepCompactionSummaries", "Keep compaction summaries for a retry", { k: "flag" }, true, { home: "data", also: "compaction summary retry parked" }),
    K("keepCodeModeWork", "Keep code mode's journal on disk", { k: "flag" }, true, { home: "data", also: "code mode journal work scripts restart" }),
    K("mcpMaxServers", "Run at most", { k: "count", opts: [8, 16, 32, 64], unit: "servers" }, 32,
      { home: "mcp", also: "most servers at once ceiling" }),
    // THE SERVER IS SAID ONCE, ON THE KICKER (Oct 26): the three timers sit under "Server limits" in the MCP servers
    // topic, so each name says only what it limits, where "server" on every one said the same word three times over
    K("mcpIdleTimeoutMs", "Stop unused after", { k: "dur", opts: [5 * MIN, 15 * MIN, 30 * MIN, HR], unit: "min" }, 30 * MIN,
      { home: "mcp", also: "stop an unused server idle timeout" }),
    // WHOSE CALL IT IS (fin, Marlin's ruling after the walk of Oct 12): this is the budget for one request splice forwards
    // to an MCP server, and in Claude Code "tool call" means its own Bash too. Both walkers came here to stop a hung test
    // command and left half-believing this covered it. Splice never runs that command. The server's reply is named by the
    // kicker it sits under and by the search words, and a search for a hung command that finds nothing is the honest answer.
    K("mcpRequestTimeoutMs", "Reply within", { k: "dur", opts: [5 * MIN, 15 * MIN, 30 * MIN, HR], unit: "min" }, 30 * MIN,
      { home: "mcp", also: "mcp server reply request timeout wait give up on a server's reply" }),
    K("mcpInitializeTimeoutMs", "Start within", { k: "dur", opts: [30 * SEC, 60 * SEC, 2 * MIN], unit: "s" }, 60 * SEC,
      { home: "mcp", also: "wait for a server to start handshake give up on a starting server" }),
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
  // `adding` holds, per setting, the commands picked from that menu, each keeping its line until the page reloads or its
  // value is cleared, saved or not.
  // `restart` holds the rows saved while the running splice still reads the old value, until a restart brings them in.
  // `providerOf` maps a command to its provider, which is all kit.js knows a colour by; a page fills it from GET /api/models.
  // `cfg` is null until load() returns: a page draws no value it has not read.
  const st = {
    cfg: null, models: [], providerOf: {}, commandOf: {}, perCommand: true, cmds: [], adding: {}, restart: {}, restarting: false,
    said: {}, unsaved: {}, undo: {}, typing: {}, bad: {}, slow: {}, shake: {}, menu: null, custom: null, onAsk: () => false,
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

  // the effective value and the scope that set it, as the daemon's knobs block says (spec 9). Each one a reader could
  // not guess from the row carries its mark: here (applied, never written) unsaved, file changed, toml toml, env shell.
  const SOURCE = { runtime: "here", env: "env", file: "file", toml: "toml" };
  const defOf = (k) => layer("defaults")[k.key] ?? k.def;
  function valueOf(k) {
    const knob = st.cfg?.knobs?.[k.key];
    const src = SOURCE[knob?.scope];
    return src ? { v: knob.value, src, name: k.key } : { v: knob?.value ?? defOf(k), src: "default" };
  }
  const val = (key) => valueOf(KNOB[key]).v;
  // A models setting is a comma list of the ids a command serves; the page names each by the label GET /api/models gives it.
  // `st.models` is every model the commands that take this setting serve, a page fills it from that route.
  const idsOf = (v) => String(v ?? "").split(",").map((x) => x.trim()).filter(Boolean);
  const labelOf = (id) => st.models.find((m) => m.id === id)?.label ?? id;
  // A PICK'S WORDS ARE THE DAEMON'S (gap 458): a knob that sends its words (GET /api/config, KnobWords.kt) offers exactly
  // those, each by fin's name for it where the page has one and by the word itself where it has none, so a word the
  // daemon adds shows, and one it drops goes. A pick with no words on the wire keeps the options written here.
  const pickOpts = (k) => {
    const words = st.cfg?.knobs?.[k.key]?.words;
    const listed = words ? words.map((w) => [w, k.c.names?.[w] ?? w]) : k.c.opts ?? (k.c.names ? Object.entries(k.c.names) : []);
    return k.c.unset ? [[null, k.c.unset], ...listed] : listed;
  };
  function word(k, v) {
    const c = k.c;
    if (c.k === "dur") return v === 0 && c.none ? c.none : dur(Number(v));
    if (c.k === "count") {
      const n = v === null || v === "forever" ? null : Number(v);
      return n === 0 && c.zero ? c.zero : (n === 0 || n === null) && c.none ? c.none : n === 1 && c.unit === "days" ? "1 day"
        : c.tok ? `${tok(n)} ${c.unit}` : `${c.pre || ""}${fmt(n / (c.per || 1))}${c.unit ? (c.unit === "%" ? "%" : ` ${c.unit}`) : ""}`;
    }
    if (c.k === "pick") return (pickOpts(k).find(([o]) => String(o) === String(v)) || [, String(v)])[1];
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
  // [titles]: a switch option's own title, by its value, for what that option costs (Your data's Off buttons)
  function ctlHtml(k, v, cmd = null, { titles = null } = {}) {
    const c = k.c, id = cmd ? `${k.key}@${cmd}` : k.key, open = st.menu === `pick:${id}`;
    // A REFUSED VALUE SHAKES ONCE (Oct 26): the control the daemon turned down moves once where he is looking, and the
    // "Not saved" beside it stays. Only the drawing right after the refusal carries it, so a later redraw is still.
    const bad = st.bad[id] ? ` aria-invalid="true"${st.shake[id] ? ' data-shook="1"' : ""}` : "";
    if ((c.k === "dur" || c.k === "count") && st.cfg?.knobs?.[k.key]?.kind === "number") {
      const unit = c.k === "dur" ? c.unit : c.unit || "";
      const n = Number(v), shown = c.k === "dur" ? n / (unit === "min" ? MIN : SEC) : n / (c.per || 1);
      const blank = v === null || v === undefined || (n === 0 && (c.none || c.zero));
      const typed = st.typing[id], text = typed ?? (blank ? "" : String(+shown.toFixed(2)));
      // a blank field says what blank means ("The model's own"), at the width of those words: in the number's own box
      // it read "The mode" (Version's Advanced, Oct 26)
      const ph = blank && (c.zero || c.none) ? c.zero || c.none : "";
      // an id, so a page's redraw puts the caret back, and the typed text kept, so a redraw mid-word loses nothing
      return `<span class="custom"><input class="field" id="kf-${esc(id.replace("@", "-"))}" inputmode="decimal" data-kfield="${id}" value="${esc(text)}"`
        + `${ph ? ` placeholder="${esc(ph)}" size="${ph.length}"` : ""} aria-label="${esc(k.label)}"${bad}><em>${esc(unit)}</em></span>`;
    }
    if (c.k === "dur" || c.k === "count" || (c.k === "pick" && pickOpts(k).length > 3)) {
      if (st.custom === id) { // Custom: a field in the preset's unit, saved on Enter
        const unit = c.k === "dur" ? c.unit : c.unit || "";
        const shown = c.k === "dur" ? Number(v) / (unit === "min" ? MIN : SEC) : Number(v) / (c.per || 1);
        const empty = v === null || v === undefined || v === "forever" || (Number(v) === 0 && c.zero);
        return `<span class="custom"><input class="field" inputmode="decimal" data-kcustom="${id}" value="${empty ? "" : esc(String(+shown.toFixed(2)))}" aria-label="${esc(k.label)}"${bad}><em>${esc(unit)}</em></span>`;
      }
      const opts = c.k === "pick" ? pickOpts(k) : c.opts.map((o) => [o, word(k, o)]);
      const items = opts.map(([o, w]) => [o, w, String(o) === String(v) ? "now" : String(o) === String(defOf(k)) ? "splice's default" : ""]);
      if (c.k !== "pick" && !opts.some(([o]) => String(o) === String(v))) items.unshift([v, word(k, v), "now"]);
      if (c.k !== "pick") items.push(["__custom", "Custom…", ""]);
      return `<span class="menuwrap"><button class="pickbtn" data-kmenu="pick:${id}" aria-expanded="${open}"${bad}>${esc(word(k, v))}${ICON.caret}</button>${open ? menuHtml(id, items) : ""}</span>`;
    }
    if (c.k === "pick" || c.k === "flag") {
      const opts = c.k === "flag" ? [[true, "On"], [false, "Off"]] : pickOpts(k);
      const now = c.k === "flag" ? v === true || v === "true" : v;
      const shook = st.shake[id] ? " failed" : "";
      return `<span class="switch${shook}">${opts.map(([o, w]) => `<button data-kpick="${id}" data-v='${esc(JSON.stringify(o))}' aria-pressed="${String(o) === String(now)}"${titles?.[String(o)] ? ` title="${esc(titles[String(o)])}"` : ""}>${esc(w)}</button>`).join("")}</span>`;
    }
    if (c.k === "models") {
      // A MODEL IN THE LIST IS ITS ID (Oct 27): the setting holds ids, and the labels the models route gives only some
      // of them put "GPT-5.6-Luna" beside "gpt-5.5" in one list. Each chip is the id in the data face, its label on its title.
      const ids = idsOf(v), left = st.models.filter((m) => !ids.includes(m.id)), mopen = st.menu === `model:${id}`;
      return `<span class="mlist">${ids.map((m) => `<span class="mchip" title="${esc(labelOf(m))}">${esc(m)}<button class="icon" data-kact="unmodel" data-key="${k.key}" data-m="${esc(m)}" aria-label="Remove ${esc(labelOf(m))}">${ICON.close}</button></span>`).join("")}`
        + (left.length ? `<span class="menuwrap"><button class="act quiet small" data-kmenu="model:${id}" aria-expanded="${mopen}">${PLUS}Add model</button>`
          + (mopen ? `<div class="menu" role="menu">${left.map((m) => `<button role="menuitem" data-kact="model" data-key="${k.key}" data-m="${esc(m.id)}">${esc(m.id)}${m.label && m.label !== m.id ? `<span class="mmeta">${esc(m.label)}</span>` : ""}</button>`).join("")}</div>` : "") + "</span>" : "") + "</span>";
    }
    if (c.k === "text") { // a text the daemon reads as it is: a field, applied like a number's
      const text = st.typing[id] ?? (v === null || v === undefined ? "" : String(v));
      return `<span class="custom wide"><input class="field" id="kf-${esc(id.replace("@", "-"))}" data-kfield="${id}" value="${esc(text)}" spellcheck="false" autocomplete="off" aria-label="${esc(k.label)}"${bad}></span>`;
    }
    return "";
  }
  /** The value a pick replaced, struck, and Undo, while they show (gap 458). */
  const undoHtml = (id) => {
    const u = st.undo[id];
    return u ? `<s class="was">${esc(u.word)}</s><button class="act quiet small" data-kact="undo" data-id="${esc(id)}">Undo</button>` : "";
  };
  function saidHtml(id, { rest = true } = {}) {
    if (st.bad[id]) return `<span class="said limit">${esc(st.bad[id])}</span>`;
    // saved to splice.toml, read by the running splice only once it restarts: the act that brings it in sits beside it
    if (st.restart[id]) {
      return st.restarting ? `<span class="said">${ICON.wait}Restarting</span>`
        : st.cut ? armedAsk("Mid-request", st.cut, '<button class="act danger small" data-kact="restart-now">Restart splice</button>',
          '<button class="act quiet small" data-kact="keep-running">Keep running</button>')
        : `<span class="said ok">Saved</span>${undoHtml(id)}<button class="act quiet small" data-kact="restart">Restart splice</button>`;
    }
    if (st.slow[id]) return `<span class="said">${ICON.wait}${esc(st.slow[id])}</span>`; // in flight: the wait ring and the word, like Starting
    // applied but not written: what that means for him is the next start, said with the mark the legend names it by
    if (st.said[id]) return `<span class="said ok">${esc(st.said[id])}</span>${st.unsaved[id] ? `<span class="said limit gone">${MARK.unsaved}${esc(MARK_WORD.unsaved)}</span>` : ""}${undoHtml(id)}`;
    // at rest a setting says where its value comes from and whether a change is read at once or when splice starts, in
    // the spec's words (v0.4.0 section 9, Resolved: boot-only stays editable, labelled "applies on restart"); saving a
    // boot-only one then offers Restart splice above
    return rest ? restHtml(id) : "";
  }
  // THE ORDINARY CASE SAYS NOTHING (the polish pass, Oct 26, from Marcos's "show don't tell"): a row at splice's default
  // whose change lands live carried two tags saying so on 45 rows, read once and never again. What is left is the mark
  // for a value a reader could not guess at: changed, from the file, from the shell, gone at the next start, or read
  // only when splice starts. The words live in the section's legend (legendHtml), beside the same glyph.
  function restHtml(id) {
    const marks = marksOf(id);
    return marks.length ? `<span class="marks">${marks.map((m) => markHtml(m)).join("")}</span>` : "";
  }
  // a setting no change here reaches: its value, and why in a word. Forced in code reads as a lock; a per-command one names
  // where it is set. The daemon's sentence goes to the console log (fin, Marlin, Oct 13)
  const warned = new Set();
  function fixedHtml(k, label, hl, lit, el = "") {
    const knob = st.cfg.knobs[k.key];
    if (knob.read_only_reason && !warned.has(k.key)) { warned.add(k.key); console.warn(`${k.key}: ${knob.read_only_reason}`); }
    // the lock, and the marks every other row carries: a locked row is the one place the value cannot move, so the mark
    // for it is the lock itself (v0.4.0 section 9, Resolved: every knob shows where its value comes from)
    const why = knob.read_only === "pinned" ? markHtml("lock") : markHtml("lock", "Set per command in splice.toml");
    return `<div class="lbl${lit}" data-row="${k.key}">${hl(label)}</div><div class="ctl"><span class="fixed">${esc(word(k, valueOf(k).v))}</span>`
      + `<span class="marks">${why}${marksOf(k.key).filter((m) => m !== "toml").map((m) => markHtml(m)).join("")}</span></div>${el}`;
  }
  // a setting that does nothing on this machine right now: its value, read-only, dimmed with every other row's marks,
  // and the one line saying why, so every setting still shows and nothing offers a change that would reach nothing
  // (splice-console-lead, Oct 13). The dimming is the state; the line is the reason it is in it.
  // A REASON THAT IS ANOTHER SETTING POINTS AT IT (Oct 27): [why] { text, see } names the setting that turns this one
  // on, as a button a page answers by bringing that row into view (Settings' "seerow"); a bare string is a reason
  // no setting on the page changes. Only the value dims, so the pointer reads as the way out it is.
  function inertHtml(k, label, hl, lit, why, el = "") {
    const see = why && typeof why === "object" && KNOB[why.see]
      ? ` <button class="seerow" type="button" data-act="seerow" data-key="${esc(why.see)}">${esc(KNOB[why.see].label)}</button>` : "";
    const text = why && typeof why === "object" ? why.text : why;
    return `<div class="lbl off${lit}" data-row="${k.key}">${hl(label)}</div><div class="ctl col"><div class="line off"><span class="fixed">${esc(word(k, valueOf(k).v))}</span>${restHtml(k.key)}</div>`
      + `<span class="note">${esc(text)}${see}</span></div>${el}`;
  }
  // A command's own value: its control, what the change did, and the way back to the value every other command uses.
  // A command just picked from "One command" has nothing saved yet, so its control shows the shared value until a change.
  // An own value equal to the shared one cannot go "Back to" the value it already reads; clearing it still matters, since a
  // later change for every command would pass it by, so the button says that (splice-usage's walk, Oct 13: "100 · Back to 100").
  const backHtml = (k, c) => {
    if (st.undo[`${k.key}@${c}`]) return "";
    const shared = valueOf(k).v, own = ownValue(k.key, c);
    // a line picked a moment ago holds nothing yet and reads the shared value, so it too follows every command: "512 ·
    // Back to 512" on a fresh line was the Oct 13 confusion over again (Models, Oct 26)
    const words = own === undefined || String(own) === String(shared) ? "Follow every command" : `Back to ${word(k, shared)}`;
    return `<button class="back" data-kact="back" data-key="${k.key}" data-cmd="${esc(c)}">${esc(words)}</button>`;
  };
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
  function rowHtml(k, { hl = esc, lit = "", label = k.label, el = "", inert = null } = {}) {
    // every shape of row ends in the page's element, so a page that lays rows on a grid of columns keeps its columns
    if (inert) return inertHtml(k, label, hl, lit, inert, el);
    const s = valueOf(k);
    if (s.src === "env") { // set in the person's shell: read here, changed there, the variables named as the way to change it
      return `<div class="lbl${lit}" data-row="${k.key}">${hl(label)}</div><div class="ctl"><span class="fixed">${esc(word(k, s.v))}</span><span class="from">${(st.cfg?.knobs?.[k.key]?.env_names ?? [k.key]).map((n) => `<code>${esc(n)}</code>`).join(" or ")}</span>`
        + `<span class="marks">${markHtml("shell")}${needsRestart(k.key) ? markHtml("restart") : ""}</span></div>${el}`;
    }
    if (st.cfg?.knobs?.[k.key]?.editable === false) return fixedHtml(k, label, hl, lit, el);
    const owners = [...ownersOf(k.key), ...(st.adding[k.key] ?? []).filter((c) => !ownersOf(k.key).includes(c))];
    // EVERY COMMAND IS IN THE PICKER (Oct 26): one that already holds its own value is listed with that value, and
    // picking it opens its line here, so changing an exception no longer means leaving for Models. A command whose
    // line is already open is the one thing left out: it is on the row.
    const open = st.menu === `over:${k.key}`, opening = st.adding[k.key] ?? [];
    const left = (k.only || st.cmds).filter((c) => !opening.includes(c));
    // A COMMAND'S OWN VALUE IS A CHIP (Marlin, hitstop, Oct 13, gap 457): its value in mono, and a door to that command's
    // Every setting on Models, where the same value edits. A command being set here keeps its line until the page reloads.
    // COMMANDS THAT AGREE ARE ONE GROUP (Oct 26): six chips reading "6 tries" six times said the value six times and the
    // shape of the exception not once. A group of two or more hangs its commands under the one value they share.
    const setting = st.adding[k.key] ?? [];
    const held = owners.filter((c) => !setting.includes(c));
    const groups = new Map();
    for (const c of held) {
      const v = word(k, ownValue(k.key, c));
      groups.set(v, [...(groups.get(v) ?? []), c]);
    }
    const chip = (c, v) => `<a class="chip own" style="--c:${colorOf(st.providerOf[c])}" `
      + `href="models.html?cmd=${encodeURIComponent(c)}&setting=${encodeURIComponent(k.key)}">${hl(st.commandOf[c] || c)}${v ? ` <span class="v">${esc(v)}</span>` : ""}</a>`;
    const chips = [...groups].map(([v, cs]) => cs.length === 1 ? chip(cs[0], v)
      : `<span class="ogrp"><b class="v">${esc(v)}</b>${cs.map((c) => chip(c, null)).join("")}</span>`).join("");
    const lines = (chips ? `<div class="owners">${chips}</div>` : "") + owners.filter((c) => setting.includes(c)).map((c) => overHtml(k, c, hl)).join("");
    const perCmd = st.cfg?.knobs?.[k.key]?.per_command ?? Boolean(k.cmd);
    // the way to make an exception is an empty slot at the end of the row's own chips, dashed as every "add" on the
    // console is (console.css .add): a shape that reads as "one more fits here", not a fourth button to read
    // THE PICKER IS GROUPED BY WHAT EACH COMMAND RUNS NOW (Oct 27): the commands that follow every command's value
    // first, under that value, then each value some commands hold of their own, under it. A list of eleven names with
    // a value after three of them said which commands differ only to someone who read every line.
    const byValue = new Map();
    for (const c of left) {
      const v = ownersOf(k.key).includes(c) ? word(k, ownValue(k.key, c)) : null;
      byValue.set(v, [...(byValue.get(v) ?? []), c]);
    }
    const picks = [...byValue].sort(([a], [b]) => (a === null ? -1 : b === null ? 1 : 0));
    const item = (c) => `<button role="menuitem" data-kact="over" data-key="${k.key}" data-cmd="${c}"><span class="blot" style="--c:${colorOf(st.providerOf[c])}"></span>${esc(st.commandOf[c] || c)}</button>`;
    const pickGroup = ([v, cs]) => {
      const head = v === null ? ["Every command", word(k, s.v)] : ["Their own", v];
      return `<div class="mgroup" role="group" aria-label="${esc(`${head[0]} ${head[1]}`)}"><span class="mgh" aria-hidden="true">${esc(head[0])}<b>${esc(head[1])}</b></span>${cs.map(item).join("")}</div>`;
    };
    const add = st.perCommand && perCmd && left.length ? `<span class="menuwrap"><button class="chip add one" data-kmenu="over:${k.key}" aria-expanded="${open}" aria-label="Set for one command">${PLUS}One command</button>`
      + (open ? `<div class="menu cmdmenu" role="menu">${picks.map(pickGroup).join("")}</div>` : "") + "</span>" : "";
    // the commands a setting acts on at all, which no chip on the row would say. The rows that act on every command say
    // nothing: their section's head says it once (Settings' scope line), so 45 rows no longer repeat it.
    const only = k.only?.length ? `<span class="only"><span class="word">Only</span>${k.only.map((c) => cmdChip(c, hl)).join("")}</span>` : "";
    // THE SLOT UNDER A CONTROL HOLDS AN ELEMENT, NEVER A SENTENCE (Marlin, Oct 12): the page passes what it already
    // knows — the last read, the mark on a bar, the providers reporting nothing, how many servers run — and passes
    // nothing where that fact is not on the wire. A row with no element is its name and its control.
    return `<div class="lbl${lit}" data-row="${k.key}">${hl(label)}</div><div class="ctl col"><div class="line">${ctlHtml(k, s.v)}${only}${saidHtml(k.key, { rest: false })}${add}${restHtml(k.key)}</div>${lines}</div>${el}`;
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
    // applied but not written to the state file: two true facts, Applied and Not saved, the second in the limit colour
    // (fin, Oct 13); the value is gone at the next start, which follows from the two words
    if (!restart) st.said[id] = "Applied";
    if (body.not_persisted) { st.unsaved[id] = true; console.warn(`${key}: applied, not saved: ${body.not_persisted}`); } else delete st.unsaved[id];
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
    // A command set here keeps its line until the page reloads (c01c0a810), so what the change did stays where it was
    // made: Applied with the struck value and Undo, or Saved with Restart splice. Turned into its chip at the save, the
    // line took those with it, and a value splice reads at start had no Restart splice left. A cleared one leaves.
    if (v === null) st.adding[key] = (st.adding[key] ?? []).filter((c) => c !== cmd);
    const restart = (body.restart_required ?? []).includes(key);
    // a cleared value leaves Settings' row, so what it did is said on the setting's own line there as well
    const at = v === null ? [id, key] : [id];
    for (const x of at) {
      delete st.bad[x];
      if (restart) { st.restart[x] = true; delete st.said[x]; } else { delete st.restart[x]; st.said[x] = "Applied"; }
    }
    return { ok: true, restart, persisted: body.persisted ?? null };
  }
  /** The first press: nothing in flight restarts at once, else the rows arm with the sessions it would cut (kit.js). */
  async function armRestart(render) {
    if (st.restarting || st.arming) return;
    st.arming = true;
    const cut = await midRequest();
    st.arming = false;
    if (!cut.length) { restart(render); return; }
    st.cut = cut; render();
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
    if (c.k === "text") return s === "" ? null : s; // an emptied text setting goes back to splice's default
    if (s === "") return c.none && !c.min ? 0 : defOf(KNOB[key]);
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
      if (KNOB[d.key].c.k === "dur" || KNOB[d.key].c.k === "count" || pickOpts(KNOB[d.key]).length > 3) st.menu = `pick:${d.key}@${d.cmd}`;
      return true;
    }
    if (d.kact === "back") {
      const id = `${d.key}@${d.cmd}`;
      if (ownValue(d.key, d.cmd) === undefined) { st.adding[d.key] = (st.adding[d.key] ?? []).filter((c) => c !== d.cmd); delete st.bad[id]; }
      else sent(id, null, render);
      return true;
    }
    if (d.kact === "undo") { const u = st.undo[d.id]; if (u) { delete st.undo[d.id]; sent(d.id, u.v, render, false, false); } return true; }
    if (d.kact === "restart") { armRestart(render); return true; }
    if (d.kact === "restart-now") { st.cut = null; restart(render); return true; }
    if (d.kact === "keep-running") { st.cut = null; return true; }
    return true;
  }
  /** Sends the change and redraws when the daemon answers; the row says "Saving" with the wait ring while it is out. */
  // A SINGLE PICK CONFIRMS BY SHOWING WHAT IT REPLACED (Marlin, fin, hitstop, Oct 13, gap 458): the row shows the old
  // value struck beside Applied, with Undo, until that row changes again or the page reloads, so a glance at another row
  // never loses the way back. Undo sends the old value back, which is no value at all where the row followed the shared
  // one or splice's default, and its own result reads Applied.
  function sent(id, v, render, sure = false, undoable = true) {
    const [key, cmd] = id.split("@"), k = KNOB[key], at = valueOf(k), own = cmd ? ownValue(key, cmd) : undefined;
    const was = cmd ? own ?? null : at.src === "default" ? null : at.v;
    const wasWord = word(k, cmd ? own ?? at.v : at.v);
    st.slow[id] = "Saving";
    delete st.said[id]; delete st.undo[id];
    // the drawing right after a refusal is the one that shakes: the flag goes as soon as that drawing is made
    const drawn = (r, bad = r?.bad) => { if (bad) st.shake[id] = true; render(r); delete st.shake[id]; };
    patch(id, v, sure).then((r) => {
      delete st.slow[id];
      if (undoable && r?.ok) st.undo[id] = { v: was, word: wasWord };
      drawn(r);
    }).catch(() => { delete st.slow[id]; st.bad[id] = "Not saved"; drawn(undefined, true); });
  }
  // a Custom field saves on Enter; left without Enter it goes back to its menu, and the redraw waits a tick, since
  // Chrome fires focusout while innerHTML replaces the field
  function wire(root, render) {
    // ESCAPE CLOSES WHAT A CLICK OPENED, WHEREVER THE CARET IS (Oct 26): a menu opens by click, so nothing inside it
    // holds focus, and a keydown bound to the form never saw the key. At the window it always does.
    scope.addEventListener?.("keydown", (e) => {
      if (e.key !== "Escape" || !(st.menu || st.custom)) return;
      st.menu = null; st.custom = null; render();
    });
    root.addEventListener("keydown", (e) => {
      const d = e.target.dataset;
      if (e.key === "Enter" && d.kcustom) { const id = d.kcustom; st.custom = null; sent(id, fromField(id, e.target.value), render); render(); }
      if (e.key === "Escape" && (d.kcustom || st.menu)) { st.custom = null; st.menu = null; render(); }
      if (e.key === "Enter" && d.kfield) e.target.blur();
      if (e.key === "Escape" && d.kfield) { delete st.typing[d.kfield]; e.target.dataset.kfield = ""; render(); }
    });
    // a typed field applies when it is left changed; Escape cleared its id above, so leaving it then sends nothing
    root.addEventListener("input", (e) => { const id = e.target.dataset?.kfield; if (id) st.typing[id] = e.target.value; });
    root.addEventListener("change", (e) => {
      const id = e.target.dataset?.kfield;
      if (id) { delete st.typing[id]; sent(id, fromField(id, e.target.value), render); }
    });
    root.addEventListener("focusout", (e) => { if (e.target.dataset.kcustom && st.custom === e.target.dataset.kcustom) { st.custom = null; setTimeout(render); } });
  }
  // THE REST OF THE SETTINGS (gap 457; Marlin, Oct 13 12:01 PM, from Marcos's block at v0.4.0.md:568): each knob on the
  // wire with no row of its own goes behind its section's Advanced, named by its key, its control from the daemon's kind.
  // ADV_HOME is the section each belongs to; one the daemon adds later lands in Version's until it is given a home. Each is
  // kept in KNOB from first sight, so a change saves like any other row's and the page's search finds it.
  const ADV_HOME = {
    ingressReadIdleMs: "silent", authCacheMs: "busy",
    mirrorReasoning: "reasoning",
    usageWarnTokens5h: "plan", mcpSlice: "mcp", toolSurface: "mcp", statuslineGitRoots: "projects",
    activityStoreHeads: "data", debug: "data", trace: "data", wireTap: "data", perfArchiveRetentionDays: "data",
  };
  // A KEY IS NOT A NAME (Oct 26): `ingressReadIdleMs` behind Advanced told him the daemon's field, in the daemon's
  // spelling, and what it does nowhere. Each one named here reads as what it does, in his words, with the unit the
  // control shows; the unit also tells a number apart from a count (a duration's field is in seconds or minutes).
  // A key with no entry still shows, under its own spelling: that is honest, and it is the next one to name.
  // A name that changed keeps the words it had as search words (the third entry), so a search for the old name still
  // lands on the row.
  const NAMES = {
    ingressReadIdleMs: ["Drop a request that stops arriving for", { k: "dur", unit: "s" }, "longest pause while a request arrives"],
    authCacheMs: ["Read the sign-in files again after", { k: "dur", unit: "s" }],
    mirrorReasoning: ["Write a reasoning summary into the conversation", { k: "flag" }],
    // MCP servers (Knob.kt MCP_SLICE, TOOL_SURFACE): the system group a hosted server starts in, whose memory ceiling
    // keeps one server from eating the machine, and the one-way switch back to every tool on every request
    mcpSlice: ["Group that caps their memory", { k: "text" }],
    toolSurface: ["Tools sent with a request", { k: "pick", names: { auto: "As each provider sets", off: "All, every time" } }],
    // Projects (STATUSLINE_GIT_ROOTS): only $HOME and /tmp are trusted for the status line's branch until named here
    statuslineGitRoots: ["Show the git branch for repos under", { k: "text" }],
    // Your data (ACTIVITY_STORE_HEADS, DEBUG, TRACE, WIRE_TAP, the old usage archive)
    activityStoreHeads: ["Commands that save what each agent is doing", { k: "text" }],
    debug: ["Debug logging", { k: "flag" }],
    trace: ["Save prompts and answers", { k: "flag" }],
    wireTap: ["Last requests kept in memory", { k: "count", none: "None" }],
    perfArchiveRetentionDays: ["Keep the old usage archive for", { k: "count", unit: "days" }],
    // the rest land under Version: the ChatGPT and Grok commands' own plumbing, and splice's own
    port: ["Listens on port", { k: "count" }],
    controlPort: ["Console's port", { k: "count" }],
    grokPort: ["claude-grok listens on port", { k: "count" }],
    chatgptApiBase: ["ChatGPT address", { k: "text" }],
    xaiApiBase: ["xAI address", { k: "text" }],
    codexAuthPath: ["ChatGPT sign-in file", { k: "text" }],
    grokAuthPath: ["Grok sign-in file", { k: "text" }],
    pinnedModel: ["Model it always uses", { k: "text" }],
    grokModel: ["claude-grok's model", { k: "text" }],
    contextWindowOverride: ["Context window", { k: "count", unit: "tokens", none: "The model's own" }],
    supervisorUnit: ["Service that runs splice", { k: "text" }],
    codeModeDefaultExecYieldTimeMs: ["Code mode runs a script in front for", { k: "dur", unit: "s" }],
    codeModeToolDescriptionFirst: ["Code mode lists tool descriptions first", { k: "flag" }],
    codeModeShowCellOverhead: ["Code mode shows each cell's overhead", { k: "flag" }],
    codeModeToolInputSchemaMaxBytes: ["Code mode's largest tool schema", { k: "count", unit: "bytes", none: "No limit" }],
    codeModeUnifiedImageBudget: ["Code mode shares one image budget", { k: "flag" }],
  };
  function others() {
    const wire = st.cfg?.knobs ?? {}, drawn = new Set(LIST.map((k) => k.key));
    return Object.keys(wire).filter((key) => !drawn.has(key)).sort().map((key) => {
      const kind = wire[key].kind;
      const [name, c, was = ""] = NAMES[key] ?? [key, kind === "bool" ? { k: "flag" } : kind === "number" ? { k: "count" } : { k: "text" }];
      KNOB[key] ??= K(key, name, c, layer("defaults")[key] ?? null, { adv: ADV_HOME[key] ?? "version", also: `${key} ${was}`.trim() });
      return KNOB[key];
    });
  }
  const focusCustom = (root) => { const f = root.querySelector("[data-kcustom]"); if (f) { f.focus(); f.select(); } };

  return {
    fmt, SEC, MIN, HR, MB, dur, tok, LIST, KNOB, st, load, valueOf, val, word, needsRestart, ownersOf, ownValue,
    cmdChip, ctlHtml, saidHtml, backHtml, rowHtml, commandRowsHtml, patch, sent, click, wire, focusCustom, others,
    legendHtml, markHtml,
  };
};
