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
  // an arrow that turns back: the way to the value every command uses
  const BACK = G('<path d="M9 14L4 9l5-5"/><path d="M4 9h10.5a5.5 5.5 0 0 1 0 11H11"/>');
  const LOCK = G('<rect x="5" y="11" width="14" height="10" rx="2"/><path d="M8 11V7a4 4 0 0 1 8 0v4"/>');
  // ONLY AN EXCEPTION CARRIES A MARK (the polish pass, Oct 26): a setting at splice's default that a change reaches at
  // once is the ordinary case and says nothing. "Default" and "Live" stood on 45 and 38 rows and told him nothing a
  // row without them would not. What is left are the four facts that change what a change does, each a glyph on the
  // value it belongs to, named in its title and said once in words by the page's legend (MARK_WORD).
  const MARK = {
    restart: G('<path d="M20 12a8 8 0 1 1-2.6-5.9"/><path d="M20 4v5h-5"/>'),
    toml: G('<path d="M14 3H7a2 2 0 0 0-2 2v14a2 2 0 0 0 2 2h10a2 2 0 0 0 2-2V8z"/><path d="M14 3v5h5M9 13h6M9 17h4"/>'),
    shell: G('<path d="M4 6l5 6-5 6M12 18h8"/>'),
    changed: '<svg viewBox="0 0 24 24" aria-hidden="true"><circle cx="12" cy="12" r="6" fill="currentColor"/></svg>',
    unsaved: G('<circle cx="12" cy="12" r="6" stroke-dasharray="3 3"/>'),
    lock: LOCK,
    // the dashed slot with its plus: where a value for ONE command is set, at the end of a row (rowHtml's add)
    one: G('<rect x="3" y="3" width="18" height="18" rx="4.5" stroke-dasharray="3.4 2.8"/><path d="M12 8v8M8 12h8"/>'),
  };
  const MARK_WORD = {
    changed: "Changed from the default", restart: "Takes a restart", toml: "Set in your settings file",
    shell: "Set in your shell", unsaved: "Gone at the next start", lock: "Locked", one: "A value for one command",
  };
  const MARK_ORDER = ["one", "changed", "restart", "toml", "shell", "unsaved", "lock"];
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
  /** Whether a setting can hold a value of its own for one command, as its row's add slot offers it. */
  const perCmdOf = (key) => Boolean(st.perCommand && (st.cfg?.knobs?.[key]?.per_command ?? KNOB[key]?.cmd) && st.cfg?.knobs?.[key]?.editable !== false);
  /** Every mark [keys] carry, each said once in words: a page draws this where its VIEW names itself, ONCE.
   *  A VIEW SAYS EACH MARK ONCE (Oct 28): the legend was built per call and pages called it per group and per topic, so
   *  "Takes a restart" stood on eight kickers of one list. A page now calls it once for every key its view draws, or
   *  passes the same [said] set to every call of one view: a kind already in the set is not drawn again, and each kind
   *  drawn goes into it. The first call of a view names everything; a later one names only what is new.
   *  The ring with its plus is the row's slot for ONE COMMAND's own value: the rows draw it as the bare slot and say
   *  its words here, once, so no row repeats "One command".
   *  [bare] gives the items alone, for a page putting its own words in the same line. */
  function legendHtml(keys, { bare = false, said = null, one = true } = {}) {
    const kinds = new Set();
    for (const key of keys) {
      if (st.cfg?.knobs?.[key]?.editable === false) kinds.add("lock");
      if (valueOf(KNOB[key] ?? { key }).src === "env") kinds.add("shell");
      if (one && perCmdOf(key)) kinds.add("one");
      for (const m of marksOf(key)) kinds.add(m);
    }
    const shown = MARK_ORDER.filter((m) => kinds.has(m) && !said?.has(m));
    shown.forEach((m) => said?.add(m));
    const items = shown.map((m) => `<span class="lg ${m}">${MARK[m]}${esc(MARK_WORD[m])}</span>`).join("");
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
    K("upstreamTimeoutMs", "Give up after", { k: "dur", opts: [10 * MIN, 15 * MIN, 20 * MIN, 30 * MIN, HR, 2 * HR], unit: "min" }, 15 * MIN, { cmd: true, home: "silent", also: "give up after no progress for timeout limit cut off long turn wedged stuck" }),
    K("firstByteTimeoutMs", "Check connection after", { k: "dur", opts: [30 * SEC, 60 * SEC, 90 * SEC, 2 * MIN, 5 * MIN], unit: "s" }, 90 * SEC,
      { cmd: true, home: "silent", also: "check the connection after check the connection if no output arrives for ask if a silent start is alive after first word first byte" }),
    K("streamIdleMs", "Check silence after", { k: "dur", opts: [30 * SEC, 60 * SEC, 90 * SEC, 2 * MIN, 5 * MIN], unit: "s" }, 90 * SEC, { cmd: true, home: "silent", also: "check a quiet answer after check a stalled answer is alive after silence idle hang" }),
    // arms only on a provider measured to resume from a prefill (HeadBuildInputs.kt:168-171)
    K("stallReanchorMs", "Resume after", { k: "dur", opts: [10 * SEC, 20 * SEC, 30 * SEC, 60 * SEC], unit: "s" }, 20 * SEC,
      { cmd: true, home: "silent", also: "resume resume a stalled answer after", only: [] }),
    // admission per command (Knob.kt:186-194); attempts, the first included
    K("maxInflight", "Requests at once", { k: "count", opts: [4, 8, 12, 16, 24, 32, 100, 0], none: "Unlimited" }, 12, { cmd: true, also: "concurrent parallel per command" }),
    K("maxQueued", "Waiting in line", { k: "count", opts: [128, 256, 512, 1024, 0], none: "Unlimited" }, 512, { cmd: true, also: "requests waiting in line queue full" }),
    K("quotaPoll", "Read plan limits", { k: "pick", names: { auto: "On", off: "Off" } }, "auto", { home: "plan", also: "quota" }),
    K("quotaPollIntervalMs", "Read every", { k: "dur", opts: [MIN, 2 * MIN, 5 * MIN, 10 * MIN, 15 * MIN], unit: "min" }, 5 * MIN,
      { home: "plan", also: "check plan usage every read plan limits every quota poll" }),
    // NAMES OF ONE TO THREE WORDS (Oct 28): the control beside a name carries the rest (the unit, the bar on its scale), and the
    // words a name used to have stay as search words, so a search for the old name still lands on the row
    K("usageWarnPct", "Warn at", { k: "count", opts: [50, 70, 80, 90, 95], unit: "%" }, 80, { home: "plan", also: "warning warn when a plan reaches" }),
    K("usageWarnTokens5h", "Fallback 5-hour limit", { k: "count", opts: [0, 1e6, 5e6, 10e6, 25e6], none: "Off", tok: true, unit: "tokens out" }, 0,
      { home: "plan", also: "warning 5-hour limit if none is reported" }),
    // what a budget made without an action of its own does once it is spent (PUT /api/budgets, Knob.kt BUDGET_DEFAULT_ACTION)
    K("budgetDefaultAction", "Spent budget", { k: "pick", names: { warn: "Warns", block: "Blocks" } }, "warn",
      { home: "plan", also: "when a budget runs out spend money new budget spent" }),
    // the effort a session starts at, on every head (Marcos, Oct 28: "effort at high by default, but these should be
    // configurable by users in the console and in the toml file", and "claude code picks should also be considered"). A launch
    // hands it to Claude Code as --effort, /effort changes it from there, and each head sends what Claude Code asks; high when
    // nothing is set. Read at each launch (LaunchSpecFactory), so a change here reaches the next session (Knob.kt EFFORT).
    K("effort", "Reasoning effort", { k: "pick", unset: "High", names: { none: "None", minimal: "Minimal", low: "Low", medium: "Medium", high: "High", xhigh: "Extra high", max: "Max" } }, null,
      { home: "reasoning", also: "effort reasoning effort how hard it thinks thinking level gpt responses xhigh max" }),
    // how long a GPT head's answers run: text.verbosity on each of its turns, as Codex's model_verbosity (Knob.kt
    // VERBOSITY, fix 5 of Oct 27). Read as the head is built, so it takes a restart, and a command can hold its own.
    K("verbosity", "Answer length", { k: "pick", unset: "Model's own", names: { low: "Short", medium: "Medium", high: "Long" } }, null,
      { cmd: true, adv: "reasoning", also: "how long answers run verbosity answer length text verbosity model_verbosity gpt responses short long" }),
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
    K("replayReasoning", "Send earlier reasoning back", { k: "flag" }, true, { home: "data", also: "thinking replay reasoning between requests inside your conversations" }),
    K("progressLine", "Terminal waiting line", { k: "flag" }, true,
      { home: "reasoning", also: "waiting line in the terminal progress line while it thinks waiting status" }),
    // foldReasoningModels and foldMaxContinue retired in 0.4.0 (failure-handling slice 4): splice continues a codex
    // answer whose reasoning the backend cut on its own, so there is no setting to draw, and the daemon refuses the keys.
    // a request too large for splice gets a 413 before any turn exists (AdmissionResponses.kt:34)
    K("maxRequestBytes", "Largest request", { k: "count", opts: [8 * MB, 16 * MB, 32 * MB, 64 * MB], unit: "MB", per: MB }, 32 * MB, { home: "busy", also: "too large image size 413" }),
    // how long the daemon waits for a client to send a request body (RequestReadBudgetMs)
    K("requestReadTimeoutMs", "Slow request limit", { k: "dur", opts: [10 * SEC, 30 * SEC, 60 * SEC, 2 * MIN, 5 * MIN], unit: "s" }, 30 * SEC, { adv: "busy", also: "drop a request still arriving after slow client body timeout longest wait for a request to arrive" }),
    // 0 is the daemon working the budget out from its own heap (Knob.kt:434-444), as maxInflight's 0 is Unlimited
    K("materializationHeapBytes", "Request memory", { k: "count", opts: [0, 256 * MB, 512 * MB, 1024 * MB, 2048 * MB], none: "Automatic", unit: "MB", per: MB }, 0, { adv: "busy", also: "memory for reading requests heap memory large requests" }),
    // upstreamRetries and the three retryBackoff* waits retired in 0.4.0 (failure-handling slice 4): splice decides how
    // often and how soon a failed request is sent again from what failed, and the daemon refuses the keys. The
    // per-command test in tools/console/settings-per-command.test.ts used upstreamRetries as its second row; it moves to
    // another per-command row on the same page.
    K("traceRetentionDays", "Keep requests for", { k: "count", opts: [1, 3, 7, 14, 30], unit: "days" }, 7, { home: "data", also: "keep prompts and answers for" }),
    K("traceMaxBodyChars", "Longest saved prompt", { k: "count", opts: [4 * MB, 8 * MB, 16 * MB, 32 * MB], unit: "M characters", per: MB }, 16 * MB, { home: "data" }),
    // one setting for the usage and request history (the hourly totals and the request records), what Usage and
    // Requests can look back on: 35 days on a fresh install, Forever a real choice (Marlin, Oct 10, from Marcos: "it
    // should keep as much as the user wants it to keep"). Forever is the daemon's word "forever" (null would delete the setting and bring the 35 days
    // back), and Today only is 0, a choice never typed:
    // a daily budget needs today's spend, so it keeps today and drops it at midnight. A Custom field takes whole days
    // from 1. A shorter window waits for the page's yes (ask), since it deletes what falls outside it.
    K("historyRetentionDays", "Keep history for", { k: "count", opts: [0, 7, 14, 35, 90, 365, "forever"], unit: "days", none: "Forever", zero: "Today only", min: 1 }, 35,
      { home: "data", ask: true, also: "keep usage and request history for history retention disk space forever usage requests records month year" }),
    K("activityRetentionDays", "Keep activity for", { k: "count", opts: [7, 30, 90, 180, 365], unit: "days", min: 1 }, 90, { home: "data", also: "keep what each agent was doing for activity labels teams agents file names commands" }),
    K("messageEdges", "Save messages between agents", { k: "flag" }, true, { home: "data" }),
    // [offCost] is what turning it off costs, said on the Off button before it is pressed and under the switch once it is
    K("transcriptView", "Read conversations", { k: "flag" }, true,
      { home: "data", also: "read your claude code conversations transcripts", offCost: "When off, Sessions, Requests and Teams show no conversations · Questions are answered in the terminal · Nothing is deleted" }),
    K("keepTranscriptCopies", "Copy before moving", { k: "flag" }, true, { home: "data", also: "keep a copy before moving a conversation to another model transcript copies originals continue on another model resume" }),
    K("transcriptCopyDays", "Keep transcript copies for", { k: "count", opts: [7, 14, 30, 90, 365], unit: "days", min: 1 }, 30, { home: "data", also: "transcript copies originals retention days" }),
    K("keepCompactionSummaries", "Keep compaction summaries", { k: "flag" }, true, { home: "data", also: "keep compaction summaries for a retry compaction summary retry parked" }),
    K("keepCodeModeWork", "Code mode journal", { k: "flag" }, true, { home: "data", also: "keep code mode's journal on disk code mode journal work scripts restart" }),
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
  // the topics Settings files the settings under, in its rail's order, for a menu that lists settings it does not hold yet
  const TOPIC_WORD = { silent: "Stalled answers", busy: "Many agents at once", reasoning: "Reasoning", plan: "Plan limits", mcp: "MCP servers", projects: "Projects", data: "Your data", more: "More" };
  const TOPIC_ORDER = ["silent", "busy", "reasoning", "plan", "mcp", "projects", "data", "more"];
  const TOPIC_OF = { maxInflight: "busy", maxQueued: "busy", effort: "reasoning" };

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
    said: {}, unsaved: {}, undo: {}, typing: {}, bad: {}, slow: {}, shake: {}, menu: null, menuAt: -Infinity, custom: null, onAsk: () => false,
    find: "",
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
  /** WHAT A COUNT, A SIZE OR A NUMBER OF DAYS IS A LENGTH OF (Oct 28): { kind, value } for a setting a page may draw as a bar
   *  on one linear scale shared by the settings of its kind: "days" (retention), "bytes" (a size in bytes or characters) and
   *  "count:<unit>" (a count with a unit of its own). null for a setting that is not such a length: a wait (the log axis is
   *  that one), a share, a port, a count with no unit, and a value that is no figure (0 as a word, "forever"). [v] is the
   *  value to read, the setting's own by default and a command's own where the page holds one. */
  function scaleOf(key, v) {
    const k = KNOB[key], c = k?.c;
    if (c?.k !== "count" || c.plain || c.tok || c.unit === "%") return null;
    const n = Number(v === undefined ? valueOf(k).v : v);
    if (!Number.isFinite(n) || n <= 0) return null;
    const kind = c.unit === "days" ? "days" : c.per || c.unit === "bytes" ? "bytes" : c.unit ? `count:${c.unit}` : null;
    return kind ? { kind, value: n } : null;
  }
  // A models setting is a comma list of the ids a command serves; the page names each by the label GET /api/models gives it.
  // `st.models` is every model the commands that take this setting serve, a page fills it from that route.
  const idsOf = (v) => String(v ?? "").split(",").map((x) => x.trim()).filter(Boolean);
  // A LIST SETTING THE DAEMON KEEPS AS ONE TEXT (statuslineGitRoots): its entries in order, split where the daemon splits them
  const listOf = (k, v) => String(v ?? "").split(k.c.sep ?? ",").map((x) => x.trim()).filter(Boolean);
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
        : c.tok ? `${tok(n)} ${c.unit}` : `${c.pre || ""}${c.plain ? n : fmt(n / (c.per || 1))}${c.unit ? (c.unit === "%" ? "%" : ` ${c.unit}`) : ""}`;
    }
    if (c.k === "pick") return (pickOpts(k).find(([o]) => String(o) === String(v)) || [, String(v)])[1];
    if (c.k === "flag") return v === true || v === "true" ? "On" : "Off";
    if (c.k === "models") return idsOf(v).map(labelOf).join(", ");
    if (c.k === "list") return listOf(k, v).join(", ") || (c.none || "");
    // the commands a setting reaches: "*" is every one, a comma list some of them, and an empty value none
    if (c.k === "heads") return String(v) === "*" ? c.star : idsOf(v).map((h) => st.commandOf[h] || h).join(", ") || "No command";
    // a text setting that holds "*" for all reads as the word its own control gives that value, wherever it is read
    return v === null || v === undefined ? (c.none || "") : c.star && String(v) === "*" ? c.star : String(v);
  }

  // ---------- controls ----------
  // a chip names the command a person types, claude-openrouter, never the head key splice files it under (`commandOf`,
  // filled from GET /api/heads' label; marlin, Oct 12: Today read openrouter and side-project)
  const cmdChip = (c, hl = esc) => `<span class="chip" style="--c:${colorOf(st.providerOf[c])}">${hl(st.commandOf[c] || c)}</span>`;
  // A MENU DROPS IN ONCE (Oct 27): it arrives over --t-pop where it was asked for, and a page that draws again while it is
  // open (a read landing) finds it at the same point of that arrival through --menu-at, a negative delay, so it never
  // arrives twice and never stands still for a frame before it moves. [cls] is the menu's own class.
  let popMs = null;
  function menuTag(cls = "") {
    popMs ??= parseFloat(getComputedStyle(document.documentElement).getPropertyValue("--t-pop")) || 0;
    const gone = performance.now() - st.menuAt, arriving = gone < popMs;
    return `<div class="menu${cls ? ` ${cls}` : ""}${arriving ? " opening" : ""}" role="menu"${arriving ? ` style="--menu-at:${-Math.round(gone)}ms"` : ""}>`;
  }
  function menuHtml(id, items) { // items: [value, word, meta]; "now" marks the value it holds
    return `${menuTag()}${items.map(([o, w, m]) => `<button role="menuitem" data-kpick="${id}" data-v='${esc(JSON.stringify(o))}' aria-current="${m === "now"}">${esc(w)}${m && m !== "now" ? `<span class="mmeta">${esc(m)}</span>` : ""}</button>`).join("")}</div>`;
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
          + (mopen ? `${menuTag()}${left.map((m) => `<button role="menuitem" data-kact="model" data-key="${k.key}" data-m="${esc(m.id)}">${esc(m.id)}${m.label && m.label !== m.id ? `<span class="mmeta">${esc(m.label)}</span>` : ""}</button>`).join("")}</div>` : "") + "</span>" : "") + "</span>";
    }
    // THE COMMANDS A SETTING REACHES ARE THEIR CHIPS, EACH ONE A SWITCH (Oct 28): a list typed into a field said "Every command"
    // inside the field and made him write the names the page already draws. A lit chip is a command it reaches; all lit is "*".
    if (c.k === "heads") {
      const on = new Set(String(v) === "*" ? st.cmds : idsOf(v));
      return `<span class="hchips" role="group" aria-label="${esc(k.label)}">${st.cmds.map((h) => `<button class="chip hpick" type="button" style="--c:${colorOf(st.providerOf[h])}" data-kact="head" data-key="${k.key}" data-m="${esc(h)}" aria-pressed="${on.has(h)}"${bad}>${esc(st.commandOf[h] || h)}</button>`).join("")}</span>`;
    }
    // A LIST IS ITS ENTRIES (Oct 28): each folder a chip with the glyph that removes it, and at the end of the list the dashed tile
    // where the next one lands, as Add header and Add model are drawn beside it; with none yet the tile is the whole control,
    // where an empty text box stood among rows that are ruled and drawn. Pressing the tile turns it into the field that takes
    // the next path, saved on Enter (the Custom field's own keys).
    if (c.k === "list") {
      const items = listOf(k, v), adding = st.custom === id;
      const chips = items.map((p) => `<span class="mchip lchip" title="${esc(p)}"><span class="lp">${esc(p)}</span>`
        + `<button class="icon" data-kact="unlist" data-key="${k.key}" data-m="${esc(p)}" aria-label="Remove ${esc(p)}">${ICON.close}</button></span>`).join("");
      const tail = adding
        ? `<span class="custom wide"><input class="field" data-kcustom="${id}" placeholder="/absolute/folder" spellcheck="false" autocomplete="off" aria-label="${esc(c.add)}"${bad}></span>`
        : DRAW.addTile({ word: c.add, act: "", cls: "inline", data: ` data-kact="listadd" data-key="${k.key}"` });
      return `<span class="mlist">${chips}${tail}</span>`;
    }
    if (c.k === "text") { // a text the daemon reads as it is: a field, applied like a number's
      const star = c.star && String(v) === "*";
      const text = st.typing[id] ?? (v === null || v === undefined || star ? "" : String(v));
      return `<span class="custom wide"><input class="field" id="kf-${esc(id.replace("@", "-"))}" data-kfield="${id}" value="${esc(text)}"${star ? ` placeholder="${esc(c.star)}"` : ""} spellcheck="false" autocomplete="off" aria-label="${esc(k.label)}"${bad}></span>`;
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
  /** The commands that hold a value of their own as chips, those that agree grouped under the one value (a row, and a locked row's exceptions). */
  function ownerChips(k, held, hl = esc) {
    const groups = new Map();
    for (const c of held) {
      const v = word(k, ownValue(k.key, c));
      groups.set(v, [...(groups.get(v) ?? []), c]);
    }
    const chip = (c, v) => `<a class="chip own" style="--c:${colorOf(st.providerOf[c])}" `
      + `href="models.html?cmd=${encodeURIComponent(c)}&setting=${encodeURIComponent(k.key)}">${hl(st.commandOf[c] || c)}${v ? ` <span class="v">${esc(v)}</span>` : ""}</a>`;
    return [...groups].map(([v, cs]) => cs.length === 1 ? chip(cs[0], v)
      : `<span class="ogrp"><b class="v">${esc(v)}</b>${cs.map((c) => chip(c, null)).join("")}</span>`).join("");
  }
  /** A VALUE THAT CANNOT MOVE IS THE CONTROL IT WOULD BE, HELD (Oct 28): a setting that is a choice among words reads as one of
   *  its choices, dimmed and pressing nothing, never as a stray word standing where a control would. An on-or-off, and a pick of
   *  up to three, is its segmented switch with the held one lit; a longer pick, and a count that reads as a word ("Automatic",
   *  "Unlimited"), is its menu's button; a list is its folders as chips, or the dashed empty slot. A figure stays a figure, and a
   *  text with nothing in it is the dash. The lock or the shell mark beside it says why it cannot move. */
  function heldHtml(k, v, label = k.label) {
    const c = k.c;
    if (c.k === "flag" || (c.k === "pick" && pickOpts(k).length <= 3)) {
      const opts = c.k === "flag" ? [[true, "On"], [false, "Off"]] : pickOpts(k);
      const now = c.k === "flag" ? v === true || v === "true" : v;
      return `<span class="switch held" role="group" aria-label="${esc(label)}">${opts.map(([o, w]) => `<button type="button" disabled aria-pressed="${String(o) === String(now)}">${esc(w)}</button>`).join("")}</span>`;
    }
    const wordy = (c.k === "count" || c.k === "dur") && (c.none || c.zero) && (v === null || v === undefined || v === "forever" || Number(v) === 0);
    if (c.k === "pick" || wordy) {
      return `<span class="menuwrap held"><button type="button" class="pickbtn" disabled aria-label="${esc(`${label}: ${word(k, v)}`)}">${esc(word(k, v))}${ICON.caret}</button></span>`;
    }
    if (c.k === "list" || c.k === "paths") {
      const items = c.k === "list" ? listOf(k, v) : [];
      if (items.length) return `<span class="mlist held">${items.map((p) => `<span class="mchip lchip" title="${esc(p)}"><span class="lp">${esc(p)}</span></span>`).join("")}</span>`;
      if (!word(k, v)) return DRAW.unread.object("None");
    }
    const text = word(k, v);
    return `<span class="fixed">${text ? esc(text) : DRAW.dash}</span>`;
  }
  function fixedHtml(k, label, hl, lit, el = "", tag = "") {
    const knob = st.cfg.knobs[k.key];
    if (knob.read_only_reason && !warned.has(k.key)) { warned.add(k.key); console.warn(`${k.key}: ${knob.read_only_reason}`); }
    // the lock, and the marks every other row carries: a locked row is the one place the value cannot move, so the mark
    // for it is the lock itself (v0.4.0 section 9, Resolved: every knob shows where its value comes from)
    const why = knob.read_only === "pinned" ? markHtml("lock") : markHtml("lock", "Set per command in splice.toml");
    // A LOCKED SETTING IS THE CONTROL IT WOULD BE, HELD: its value is read where its control shows it, and the lock beside it says it cannot move
    const value = heldHtml(k, valueOf(k).v, label);
    const held = ownersOf(k.key).filter((c) => String(ownValue(k.key, c)) !== String(valueOf(k).v)), own = held.length ? `<span class="owners">${ownerChips(k, held, hl)}</span>` : "";
    return `<div class="lbl${lit}" data-row="${k.key}">${tag}${hl(label)}</div><div class="ctl">${value}`
      + `<span class="marks">${why}${marksOf(k.key).filter((m) => m !== "toml").map((m) => markHtml(m)).join("")}</span>${own}</div>${el}`;
  }
  // a setting that does nothing on this machine right now: its value, read-only, dimmed with every other row's marks,
  // and the one line saying why, so every setting still shows and nothing offers a change that would reach nothing
  // (splice-console-lead, Oct 13). The dimming is the state; the line is the reason it is in it.
  // A REASON THAT IS ANOTHER SETTING POINTS AT IT (Oct 27): [why] { text, see } names the setting that turns this one
  // on, as a button a page answers by bringing that row into view (Settings' "seerow"); a bare string is a reason
  // no setting on the page changes. Only the value dims, so the pointer reads as the way out it is.
  function inertHtml(k, label, hl, lit, why, el = "", tag = "") {
    const see = why && typeof why === "object" && KNOB[why.see]
      ? ` <button class="seerow" type="button" data-act="seerow" data-key="${esc(why.see)}">${esc(KNOB[why.see].label)}</button>` : "";
    const text = why && typeof why === "object" ? why.text : why;
    return `<div class="lbl off${lit}" data-row="${k.key}">${tag}${hl(label)}</div><div class="ctl col"><div class="line off"><span class="fixed">${esc(word(k, valueOf(k).v))}</span>${restHtml(k.key)}</div>`
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
    const follows = own === undefined || String(own) === String(shared);
    const words = follows ? "Follow every command" : `Back to ${word(k, shared)}`;
    // THE WAY BACK IS THE BACK GLYPH (Oct 28): "Follow every command" read as a placeholder box on every line, so it is the
    // glyph alone and its words open on the pointer or the keyboard, as the one-command slot's do. "Back to 512" names a value
    // he may want to see before he presses, so that one keeps its words.
    return `<button class="back${follows ? " bare" : ""}" data-kact="back" data-key="${k.key}" data-cmd="${esc(c)}" aria-label="${esc(words)}"${follows ? ` title="${esc(words)}"` : ""}>${BACK}<span class="bw">${esc(words)}</span></button>`;
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
    // ADD SETTING IS THE PANE'S DASHED ADD ROW (Oct 27), the place the next one lands, as Add header, Add model and Add
    // project are beside it: a quiet button with a plus read as a second kind of control for the same act. Past twelve
    // settings its menu is found by typing, with the count of what matches said once, as the pane's other long menus are.
    const q = open ? st.find.trim().toLowerCase() : "";
    const found = q ? left.filter((key) => KNOB[key].label.toLowerCase().includes(q) || key.toLowerCase().includes(q)) : left;
    const find = left.length > 12
      ? `<div class="kfind"><input class="field" data-kfind="own:${cmd}" value="${esc(st.find)}" placeholder="Find a setting" autocomplete="off" spellcheck="false" aria-label="Find a setting">`
        + `<span class="pd-count"><b>${DRAW.n(found.length)}</b> of ${DRAW.n(left.length)}</span></div>` : "";
    // THE MENU IS GROUPED BY THE TOPIC SETTINGS FILES EACH SETTING UNDER, and a setting that waits for a restart or cannot be
    // changed carries the same mark its row will (MARK), so what a pick will do is read before it is made
    const marksIn = (key) => (st.cfg?.knobs?.[key]?.editable === false ? markHtml("lock") : "") + (needsRestart(key) ? markHtml("restart") : "");
    const itemOf = (key) => `<button role="menuitem" data-kact="over" data-key="${key}" data-cmd="${cmd}">${esc(KNOB[key].label)}<span class="mmeta">${esc(word(KNOB[key], valueOf(KNOB[key]).v))}${marksIn(key)}</span></button>`;
    const topics = new Map();
    for (const key of found) {
      const t = TOPIC_OF[key] ?? KNOB[key].home ?? KNOB[key].adv ?? "more";
      topics.set(t, [...(topics.get(t) ?? []), key]);
    }
    const items = found.length ? [...topics].sort(([a], [b]) => TOPIC_ORDER.indexOf(a) - TOPIC_ORDER.indexOf(b)).map(([t, keys]) => {
      const name = TOPIC_WORD[t] ?? "More";
      return `<div class="mgroup" role="group" aria-label="${esc(name)}"><span class="mgh" aria-hidden="true">${esc(name)}</span>${keys.map(itemOf).join("")}</div>`;
    }).join("") : `<p class="kfnone">No setting matches</p>`;
    const add = left.length ? `<div class="lbl"></div><div class="ctl"><span class="menuwrap">`
      + DRAW.addTile({ word: "Add setting", act: "", cls: "row", data: ` data-kmenu="own:${cmd}" aria-expanded="${open}" aria-haspopup="menu"` })
      + (open ? `${menuTag("kadd")}${find}${items}</div>` : "") + "</span></div>" : "";
    return rows + add;
  }
  // a setting's row in a form: its name, its control, what the change did, and the commands that hold their own value
  function rowHtml(k, { hl = esc, lit = "", label = k.label, el = "", inert = null, tag = "" } = {}) {
    // every shape of row ends in the page's element, so a page that lays rows on a grid of columns keeps its columns
    if (inert) return inertHtml(k, label, hl, lit, inert, el, tag);
    const s = valueOf(k);
    if (s.src === "env") { // set in the person's shell: read here, changed there, the variables named as the way to change it
      return `<div class="lbl${lit}" data-row="${k.key}">${tag}${hl(label)}</div><div class="ctl">${heldHtml(k, s.v, label)}<span class="from">${(st.cfg?.knobs?.[k.key]?.env_names ?? [k.key]).map((n) => `<code>${esc(n)}</code>`).join(" or ")}</span>`
        + `<span class="marks">${markHtml("shell")}${needsRestart(k.key) ? markHtml("restart") : ""}</span></div>${el}`;
    }
    if (st.cfg?.knobs?.[k.key]?.editable === false) return fixedHtml(k, label, hl, lit, el, tag);
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
    const chips = ownerChips(k, held, hl);
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
    // THE ACT STAYS ON EVERY ROW THAT TAKES IT AND IS SAID ONCE (Oct 28): at rest the slot is the legend's dashed ring with
    // its plus, no word, so a list of nineteen settings no longer prints "One command" nineteen times; under the pointer or
    // the keyboard (and while its menu is down) the ring opens to its words (knobs.css .oc). A row that already holds a
    // command's own value shows it drawn, as its chips, and keeps the slot after them.
    const add = st.perCommand && perCmd && left.length ? `<span class="menuwrap"><button class="chip add one" data-kmenu="over:${k.key}" aria-expanded="${open}" aria-label="Set for one command" title="Set for one command">${PLUS}<span class="oc">One command</span></button>`
      + (open ? `${menuTag("cmdmenu")}${picks.map(pickGroup).join("")}</div>` : "") + "</span>" : "";
    // the commands a setting acts on at all, which no chip on the row would say. The rows that act on every command say
    // nothing: their section's head says it once (Settings' scope line), so 45 rows no longer repeat it.
    const only = k.only?.length ? `<span class="only"><span class="word">Only</span>${k.only.map((c) => cmdChip(c, hl)).join("")}</span>` : "";
    // THE SLOT UNDER A CONTROL HOLDS AN ELEMENT, NEVER A SENTENCE (Marlin, Oct 12): the page passes what it already
    // knows — the last read, the mark on a bar, the providers reporting nothing, how many servers run — and passes
    // nothing where that fact is not on the wire. A row with no element is its name and its control.
    return `<div class="lbl${lit}" data-row="${k.key}">${tag}${hl(label)}</div><div class="ctl col"><div class="line">${ctlHtml(k, s.v, null, k.offCost ? { titles: { false: k.offCost.replace(/ · /g, ". ") } } : {})}${only}${saidHtml(k.key, { rest: false })}${restHtml(k.key)}${add}</div>${lines}</div>${el}`;
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
    // a folder added to a list: the daemon reads absolute folders only (it drops a relative entry), so anything else is refused
    // here as it would be there; a folder already in the list is the list as it stands
    if (c.k === "list") {
      const items = listOf(KNOB[key], valueOf(KNOB[key]).v);
      return !s.startsWith("/") ? NaN : [...new Set([...items, s.replace(/\/+$/, "") || "/"])].join(c.sep);
    }
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
    if (d.kmenu) { st.menu = st.menu === d.kmenu ? null : d.kmenu; st.find = ""; if (st.menu) st.menuAt = performance.now(); return true; }
    st.menu = null;
    if (d.kpick) {
      const v = JSON.parse(d.v);
      if (v === "__custom") st.custom = d.kpick;
      else sent(d.kpick, v, render);
      return true;
    }
    if (d.kact === "head") {
      const v = String(valueOf(KNOB[d.key]).v), on = new Set(v === "*" ? st.cmds : idsOf(v));
      if (on.has(d.m)) on.delete(d.m); else on.add(d.m);
      sent(d.key, st.cmds.every((h) => on.has(h)) ? "*" : st.cmds.filter((h) => on.has(h)).join(","), render);
      return true;
    }
    // a list's tile turns into the field for its next entry; a chip's glyph takes that entry out (the last one out returns the
    // setting to splice's default, as an emptied text does)
    if (d.kact === "listadd") { st.custom = d.key; return true; }
    if (d.kact === "unlist") {
      const k = KNOB[d.key], rest = listOf(k, valueOf(k).v).filter((x) => x !== d.m);
      sent(d.key, rest.length ? rest.join(k.c.sep) : null, render);
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
      if (KNOB[d.key].c.k === "dur" || KNOB[d.key].c.k === "count" || pickOpts(KNOB[d.key]).length > 3) { st.menu = `pick:${d.key}@${d.cmd}`; st.menuAt = performance.now(); }
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
      if (e.key === "Enter" && d.kcustom) {
        const id = d.kcustom; st.custom = null;
        // nothing typed in a list's field adds nothing: the tile comes back
        if (KNOB[id.split("@")[0]].c.k === "list" && !e.target.value.trim()) { render(); return; }
        sent(id, fromField(id, e.target.value), render); render();
      }
      if (e.key === "Escape" && (d.kcustom || st.menu)) { st.custom = null; st.menu = null; render(); }
      if (e.key === "Enter" && d.kfield) e.target.blur();
      if (e.key === "Escape" && d.kfield) { delete st.typing[d.kfield]; e.target.dataset.kfield = ""; render(); }
    });
    // a typed field applies when it is left changed; Escape cleared its id above, so leaving it then sends nothing
    root.addEventListener("input", (e) => {
      const id = e.target.dataset?.kfield;
      if (id) st.typing[id] = e.target.value;
      // a menu's find field narrows the menu as he types, and keeps his caret through the redraw
      const menu = e.target.dataset?.kfind;
      if (menu) {
        st.find = e.target.value;
        const at = e.target.selectionStart;
        render();
        const again = root.querySelector(`[data-kfind="${CSS.escape(menu)}"]`);
        if (again) { again.focus(); again.setSelectionRange(at, at); }
      }
    });
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
    ingressReadIdleMs: ["Drop a request that pauses for", { k: "dur", unit: "s" }, "drop a request that stops arriving for longest pause while a request arrives"],
    authCacheMs: ["Reread sign-in files after", { k: "dur", unit: "s" }, "read the sign-in files again after"],
    mirrorReasoning: ["Summary in the conversation", { k: "flag" }, "write a reasoning summary into the conversation"],
    // MCP servers (Knob.kt MCP_SLICE, TOOL_SURFACE): the system group a hosted server starts in, whose memory ceiling
    // keeps one server from eating the machine, and the one-way switch back to every tool on every request
    mcpSlice: ["Group that caps their memory", { k: "text" }],
    toolSurface: ["Tools sent with a request", { k: "pick", names: { auto: "As each provider sets", off: "All, every time" } }],
    // Projects (STATUSLINE_GIT_ROOTS): only $HOME and /tmp are trusted for the status line's branch until named here
    // a list of absolute folders the daemon reads as one colon-separated text: each is a chip, and the empty list is the dashed
    // tile where the first one lands
    statuslineGitRoots: ["Git branch folders", { k: "list", sep: ":", add: "Add folder" }, "show the git branch for repos under"],
    // Your data (ACTIVITY_STORE_HEADS, DEBUG, TRACE, WIRE_TAP, the old usage archive)
    // an asterisk is the daemon's word for every command, which the field says in his, as a blank field where it holds it
    activityStoreHeads: ["Commands saving activity", { k: "heads", star: "Every command" }, "commands that save what each agent is doing"],
    debug: ["Debug logging", { k: "flag" }],
    trace: ["Save prompts and answers", { k: "flag" }],
    wireTap: ["Requests in memory", { k: "count", none: "None" }, "last requests kept in memory"],
    perfArchiveRetentionDays: ["Old usage archive", { k: "count", unit: "days" }, "keep the old usage archive for"],
    // the rest land under Version: the ChatGPT and Grok commands' own plumbing, and splice's own
    // a port is spelled with no thousands separator: it names a place, it is not a quantity
    port: ["Listens on port", { k: "count", plain: true }],
    controlPort: ["Console's port", { k: "count", plain: true }],
    grokPort: ["claude-grok listens on port", { k: "count", plain: true }],
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
    // the pooled Responses websocket: how long an idle one stays open, and how old an idle one may grow before it is retired
    wsIdleCloseMs: ["Close an idle websocket after", { k: "dur", unit: "min" }],
    wsMaxAgeMs: ["Retire an idle websocket older than", { k: "dur", unit: "min" }],
  };
  // SETTINGS SPLICE NO LONGER HAS (0.4.0): it decides when to send a failed request again, and when to continue an answer
  // whose reasoning was cut, from what happened, so there is nothing to set. A daemon from before 0.4.0 still reports
  // their keys, and every setting on the wire with no entry of its own is drawn under its key: this is where the kit
  // decides what the wire's keys become, so a retired one is left out HERE and no page, panel or gear can offer it.
  const RETIRED = new Set(["upstreamRetries", "retryBackoffBaseMs", "retryBackoffCapMs", "retryBackoffJitterPct", "foldReasoningModels",
    "foldMaxContinue", "foldMarkerText", "foldMaxTier"]);
  function others() {
    const wire = st.cfg?.knobs ?? {}, drawn = new Set(LIST.map((k) => k.key));
    return Object.keys(wire).filter((key) => !drawn.has(key) && !RETIRED.has(key)).sort().map((key) => {
      const kind = wire[key].kind;
      const [name, c, was = ""] = NAMES[key] ?? [key, kind === "bool" ? { k: "flag" } : kind === "number" ? { k: "count" } : { k: "text" }];
      KNOB[key] ??= K(key, name, c, layer("defaults")[key] ?? null, { adv: ADV_HOME[key] ?? "version", also: `${key} ${was}`.trim() });
      return KNOB[key];
    });
  }
  const focusCustom = (root) => { const f = root.querySelector("[data-kcustom]"); if (f) { f.focus(); f.select(); } };

  return {
    fmt, SEC, MIN, HR, MB, dur, tok, LIST, KNOB, st, load, valueOf, val, word, scaleOf, needsRestart, ownersOf, ownValue,
    cmdChip, ctlHtml, heldHtml, saidHtml, backHtml, rowHtml, commandRowsHtml, patch, sent, click, wire, focusCustom, others,
    legendHtml, markHtml, RETIRED,
  };
};
