// The language every console page shares: the commands and their colours, the icons, a session's state as a
// lamp and a word, the outcome words, and the one nav. A page owns its own data, layout and acts; this file
// owns what would otherwise be written eight times and drift seven ways.
//
// FROM THE DRAWING, NOT REINVENTED. Every word, icon path and rule here is the drawing's own
// (captures/console-acceptance/frozen/console-bad6381/kit.js), carried across unchanged. What did NOT come
// across is the drawing's mock: its frozen afternoon, its static command and model tables, its sessionStorage
// stand-in for splice's state, and its canned transcripts. Those were the drawing standing in for routes that
// now exist, and a page that read them would be drawing the mock's data against the daemon's.
//
// TWO CHANGES FROM THE DRAWING, both because a route replaced a table.
//   · NOW is a LIVE clock. The drawing froze it at Wed Oct 7, 3:12 PM so every shot was reproducible. Here it
//     is read again at each render through [tick], so a window's reset and a session's "Seen" read in the
//     person's own present and not in the moment the page happened to load.
//   · colorOf takes a PROVIDER id, not a command. The drawing could map a command to its provider from a
//     table because it knew every command; the real console reads them from /api/heads, so the page that has
//     the head resolves it and this file only ever knows a provider's colour.
//
// LOADED FIRST, before api.js and before the page's own script. Nothing here reads the network or the DOM at
// load except the one nav draw, which needs the sidebar the page already wrote.

// ---------- the present ----------
// why read it again: a page open across a window's reset drew the reset as still ahead of a NOW from before it.
let NOW = new Date();
const tick = () => (NOW = new Date());

// ---------- the providers, as the stylesheet names their colours ----------
const COLORS = { claude: "--claude", gpt: "--gpt", grok: "--grok", kimi: "--kimi", muse: "--muse", router: "--router", deepseek: "--deepseek", local: "--local", vast: "--local" };
const PROVIDER_NAME = { claude: "Claude", gpt: "ChatGPT", grok: "Grok", kimi: "Kimi", muse: "Muse", router: "OpenRouter", deepseek: "DeepSeek", local: "This computer" };
// /api/models names a head's provider in the provider module's own words, which are not always the colour's. A family
// with no colour of its own draws in none, rather than borrowing another's.
const PROVIDER_FAMILY = { anthropic: "claude", openai: "gpt", codex: "gpt", xai: "grok", grok: "grok", moonshot: "kimi",
  kimi: "kimi", meta: "muse", muse: "muse", openrouter: "router", router: "router", deepseek: "deepseek",
  local: "local", vast: "vast" };
// A provider splice has no colour for draws in the muted text colour rather than in another provider's.
const colorOf = (provider) => `var(${COLORS[provider] || "--text-mute"})`;

// ---------- a command removed from Models (marlin's ruling: "History stays" must mean findable) ----------
/** The commands /api/heads/retired lists, as heads beside the live ones: key and name, marked retired, with no
 *  provider (so the muted colour). A key /api/heads still names wins: re-adding a removed command drops its record
 *  only at the daemon's next start (builder3, 4e93eb2a3). A daemon that cannot answer leaves the list as it is. */
function retiredHeads(res, live) {
  if (!res.ok) { console.warn(`removed commands: ${res.status || "no answer"}`); return []; }
  return (res.body?.retired || []).filter((r) => r.key && !live.some((h) => h.key === r.key))
    .map((r) => ({ key: r.key, command: r.command || r.key, reanchorMs: null, retired: true }));
}
/** Beside a removed command's name in a menu: one muted word. */
const REMOVED_TAG = `<span class="rtag">Removed</span>`;

// ---------- what to call a model ----------
/** How a request's row spells the model it ran on: the declared id without its trailing tier hint, the
 *  `[<digits><k|m>]` grammar ModelTierSuffix owns (DR-27). splice sends the stripped id upstream and records
 *  that, while the picker entry keeps the hint, so "muse-spark-1.3[1m]" serves rows spelled
 *  "muse-spark-1.3". The catalog already covers both spellings for windows and pricing (Topology.kt:367);
 *  the labels have to cover both too, or every row of a "[1m]" model reads a raw id beside a sibling that
 *  reads its name. */
const modelKey = (id) => String(id ?? "").replace(/\[\d+[km]]$/i, "");
/** One /api/models head row's labels learned into [into]: each under its own id, and a suffixed id's label
 *  also under the spelling its rows carry. A declared id's own label always wins over that alias, whichever
 *  row is read first, and an id with no label is left for [modelName] to spell. */
function learnModels(into, row) {
  for (const m of row.models || []) {
    if (!m.id || !m.label) continue;
    const key = modelKey(m.id);
    if (key !== m.id && !(key in into)) into[key] = m.label;
    into[m.id] = m.label;
  }
}
/** The words these read as themselves rather than as a capitalised word, proved by his own declared labels:
 *  "GPT-5.6", "GLM-5.3-Flash Uncensored", "Kimi K3 (256k)". */
const MODEL_WORD = { gpt: "GPT", glm: "GLM", k3: "K3" };
/** What a model is called: the label its head declares, else the name its own id spells. THE CLIENT PICKS
 *  MODELS THE CATALOG NEVER DECLARED — Claude Code runs its own small model for titles and summaries — so
 *  those live in the rows and in no catalog, and "claude-haiku-5-5" sat beside "Claude Sonnet 5.5" in one
 *  list (splice-lead, Oct 11). Only an id whose shape proves the convention is respelled: lowercase words
 *  and version numbers between hyphens, which is the spelling every declared Claude label already follows.
 *  Anything else keeps its own spelling, because a GGUF filename or a vendor-prefixed id has no convention
 *  to read and a guess at one would misname a real product. */
function modelName(labels, id) {
  if (!id) return null;
  const known = labels[id] ?? labels[modelKey(id)];
  if (known) return known;
  const raw = String(id);
  if (!/^[a-z][a-z\d.]*(-[a-z\d.]+)*$/.test(raw)) return raw;
  const words = [];
  for (const part of raw.split("-")) {
    const last = words.at(-1);
    if (last && /^[\d.]+$/.test(last) && /^[\d.]+$/.test(part)) words[words.length - 1] = `${last}.${part}`;
    else words.push(part);
  }
  return words.map((w) => MODEL_WORD[w] ?? w[0].toUpperCase() + w.slice(1)).join(" ");
}

// ---------- fin's words for how a request ended (OutcomeTag.kt's tags) ----------
// A clean request and a spent window have no word: the window shows its own lock and reset time. A stop is not
// a failure (isStopped, OutcomeTag.kt:75-76).
// Who stopped it (fin, Marlin on Requests, Oct 12): client_abort is Claude Code letting go (Esc, the client closing, its own
// 600 s give-up, LaunchTypes.kt:208); error:stopped is the console's Stop or End (OperatorStop, CancellationSeal.kt:133-136).
const OUTCOME_WORD = { client_abort: "Stopped in Claude Code", "error:stopped": "Stopped in the console", "error:cancelled": "Cancelled", "error:restarted": "splice restarted", "error:at-capacity": "Too many at once",
  "error:plan-limit": "", "error:rate-limited": "Rate limited", "error:rate-limit-relayed": "Rate limited", "error:all-accounts-exhausted": "No quota", "error:budget-blocked": "Over budget",
  "error:auth-missing": "Signed out", "error:upstream-failed": "Provider error", "failure:overloaded_error": "Overloaded",
  "error:conn-reset": "Network error", "error:upstream-frame-too-large": "Stream error", empty_model: "Empty answer", "error:unexpected": "Internal error" };
// A turn splice gave up on after no progress from the model for the limit reads "Given up". splice writes the tag
// as of V4-444 (CancellationSeal's TotalCap arm, OutcomeTag.TURN_CAP): before that it landed as a plain
// error:cancelled and a quiet model was indistinguishable from a provider under load.
// Both give-up forms read "Given up after a silence" on a row (fin, Marlin, Oct 12): error:turn-cap here, and an overload
// stalled past its re-anchors through Requests' outcomeOf, which reads this entry. A silence is what the two share, and
// it is the console's word for the event: resumed, started over or given up. Not "No output": a turn can stream for
// minutes, then go quiet. No figure: the limit ignores pings (Watchdog.kt:148-150) and up_gap_max_ms counts them.
// WHY.gaveup.word stays "Given up" for the filter and Settings' tile.
OUTCOME_WORD["error:turn-cap"] = "Given up after a silence";
// THE `failure:` FAMILY CLOSES IN CODE: splice writes `failure:` plus one of seven ErrorType names
// (TurnOutcome.kt:138-146, OutcomeTags.failure), so all seven are worded here and the reader below rarely fires.
// Rate limited is the SAME FACT as error:rate-limited and takes the same word, and api_error the same fact as
// error:upstream-failed. "Credentials refused" is deliberately not "Signed out": the provider rejected the key or
// token, where Signed out is splice having none at all (error:auth-missing) -- different things to do about them.
Object.assign(OUTCOME_WORD, {
  "failure:rate_limit_error": "Rate limited", "failure:invalid_request_error": "Invalid request",
  "failure:authentication_error": "Credentials refused", "failure:permission_error": "No access",
  "failure:not_found_error": "Not found", "failure:api_error": "Provider error",
});
// A tag the words above do not cover is READ OFF THE TAG, never drawn as a generic "Failed" and never left blank
// (fin): drop the prefix, underscores and hyphens become spaces, the first letter rises, "api" is "API". So
// failure:context_window_error reads "Context window error". It is for an older row, a type a provider adds later,
// or an error: kind no page words yet. A tag with no prefix (ok, client_abort) is not read this way: it keeps
// whatever its page says about it, which for a clean request is nothing.
const tagWord = (tag) => {
  const at = typeof tag === "string" ? tag.indexOf(":") : -1;
  if (at < 0) return undefined;
  const words = tag.slice(at + 1).split(/[_-]/).map((w) => (w === "api" ? "API" : w)).join(" ");
  return words.charAt(0).toUpperCase() + words.slice(1);
};
// A COUNT of turns an outcome ended reads with "when", so "60 when splice restarted" is sixty turns cut, not
// sixty restarts (fin, after p85 and p78); a single request keeps the outcome's own word.
const COUNT_WORD = { "error:restarted": "when splice restarted" };
const countWord = (o) => COUNT_WORD[o] ?? OUTCOME_WORD[o] ?? tagWord(o);
const STOPPED = new Set(["client_abort", "error:stopped"]);
const isClean = (o) => o === "ok" || o === "empty_message";
const isFailed = (o) => !isClean(o) && !STOPPED.has(o);
// What happened, as a door on Settings names it and Requests filters it (?why=gaveup). "Overloaded" is every
// request that came back overloaded, from the provider or from splice (OutcomeTag.kt:68-69). "Waited in line"
// was held by the gate because its command's limit was full (admit_held_ms), never a pass through an open gate.
// "Waited out" is a silence the watchdog asked about and held (Watchdog.kt:303-362). "Resumed" and "Started over"
// are a request's re-anchor tick: splice re-sent a broken stream from its partial answer, or ran the round over
// from scratch (his own two phrases).
// "Resumed after a silence" is the stall tier's resume (Knob.kt:270-283), the one Silent models counts.
// A chat stream that ended before its terminal is tagged overloaded on the wire (WireType.kt) but was cut by the provider, not refused.
// Who ended a request, not the error splice handed Claude Code: the provider's own overload stays Overloaded, splice's watchdog giving up
// after its stall re-anchors is Given up (cause UPSTREAM_STALLED), and a stream cut short is Cut off (UPSTREAM_TRUNCATED).
const isCutOff = (r) => r.cause === "UPSTREAM_TRUNCATED";
const isStalled = (r) => r.cause === "UPSTREAM_STALLED";
const WHY = {
  gaveup: { word: "Given up", has: (r) => r.outcome === "error:turn-cap" || (r.outcome === "failure:overloaded_error" && isStalled(r)) },
  overloaded: { word: "Overloaded", has: (r) => r.outcome === "failure:overloaded_error" && !isCutOff(r) && !isStalled(r) },
  cutoff: { word: "Cut off", has: (r) => r.outcome === "failure:overloaded_error" && isCutOff(r) },
  queued: { word: "Waited in line", has: (r) => r.queued != null }, // held by the gate behind a full limit (Marlin, Oct 12)
  waited: { word: "Waited out", has: (r) => !!r.silent },
  resumed: { word: "Resumed", has: (r) => !!r.resumed && !r.resumedFresh },
  startedover: { word: "Started over", has: (r) => !!r.resumedFresh },
  silentover: { word: "Started over after a silence", has: (r) => !!r.resumedFresh && !!r.stallMs },
  silentresume: { word: "Resumed after a silence", has: (r) => !!r.resumed && !!r.stallMs },
  restarted: { word: "splice restarted", has: (r) => r.outcome === "error:restarted" },
};

// What a door's "what happened" asks the daemon for (TurnsFilter.kt), so it narrows over the WHOLE window and not
// over the newest 2,000 the route already cut: the doors that name a wait open requests that are mostly old, which is
// exactly where a browser filter finds nothing. What the daemon reads is a SUPERSET of what the word means -- a
// silence must also have begun after the first byte, and only this page compares that -- so WHY's own check still
// runs over the answer and the two together are exact. `tier` is this command's own silence setting, not a figure
// this page chose. "Resumed" and "Started over" are asked APART, as fin's two phrases mean them: a request whose
// re-anchor re-posted it verbatim started over, and the resumed doors leave it out.
const WHY_ASKS = {
  gaveup: { outcome: "failed" }, overloaded: { outcome: "failure:overloaded_error" }, cutoff: { outcome: "failure:overloaded_error" },
  restarted: { outcome: "error:restarted" }, queued: { queued_ms: "0" }, waited: { silence_ms: "tier" },
  resumed: { resumed: "1", started_over: "0" }, silentresume: { resumed: "1", started_over: "0" },
  startedover: { started_over: "1" }, silentover: { started_over: "1" },
};

// The words whose test of a row the DAEMON applies, over every row of the window (TurnsTest.kt): Settings counts with it and
// Requests lists with it, so a tile and the list its door opens hold the same requests however busy the command is.
const ROW_TEST = { gaveup: "gaveup", overloaded: "overloaded", cutoff: "cutoff", waited: "waited", silentresume: "silent", silentover: "silent" };

/** The silence tier splice acts on mid-answer for one command: its own value where it holds one, the daemon's
 *  otherwise. Infinity is a tier nobody answered, which draws no band and asks for no floor. [cfg] is GET /api/config. */
const idleTierOf = (cfg, headKey) => {
  if (!cfg) return Infinity;
  const own = cfg.layers?.perHead?.[headKey] ?? {};
  const of = (key) => own[key] ?? cfg.effective?.[key];
  const tiers = [of("stallReanchorMs"), of("streamIdleMs")].filter((v) => typeof v === "number" && v > 0);
  return tiers.length ? Math.min(...tiers) : Infinity;
};

// ---------- drawing ----------
const G = (d, extra = "") => `<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" ${extra}>${d}</svg>`;
const ICON = {
  trace: '<svg class="wave" viewBox="0 0 40 40" aria-hidden="true"><polyline class="base" points="5,20 13,20 16,12 20,28 24,15 27,20 35,20"/><polyline class="beat" points="5,20 13,20 16,12 20,28 24,15 27,20 35,20"/></svg>',
  flat: '<svg class="wave" viewBox="0 0 40 40" aria-hidden="true"><polyline points="5,20 35,20"/></svg>',
  dialog: '<svg viewBox="0 0 24 24" aria-hidden="true"><path d="M12 3l7 3v5.5c0 4.4-3 8-7 9.5-4-1.5-7-5.1-7-9.5V6z"/></svg>',
  input: '<svg viewBox="0 0 24 24" aria-hidden="true"><path d="M4 5h16v11h-9l-4.5 3.5V16H4z"/><path d="M12 13.2h0"/><path d="M10 9.2a2 2 0 1 1 2.6 1.9"/></svg>',
  retries: '<svg viewBox="0 0 24 24" aria-hidden="true"><path d="M4.5 11a7.5 7.5 0 0 1 13.2-4.6M19 4v4.5h-4.5M19.5 13a7.5 7.5 0 0 1-13.2 4.6M5 20v-4.5h4.5"/></svg>',
  limit: '<svg viewBox="0 0 24 24" aria-hidden="true"><rect x="4.5" y="10.5" width="15" height="10" rx="2"/><path d="M8 10.5V7.5a4 4 0 0 1 8 0v3"/></svg>',
  signout: '<svg viewBox="0 0 24 24" aria-hidden="true"><path d="M14 4h5v16h-5M10 8l-4 4 4 4M6 12h9"/></svg>',
  branch: G('<circle cx="6" cy="5" r="2.2"/><circle cx="6" cy="19" r="2.2"/><circle cx="18" cy="7" r="2.2"/><path d="M6 7.2v9.6M18 9.2c0 4.5-6 3.6-11.3 7.4"/>', 'aria-hidden="true"'),
  team: G('<circle cx="8.5" cy="9" r="3"/><circle cx="16.5" cy="9.5" r="2.5"/><path d="M3 19c.8-3.3 2.9-5 5.5-5s4.7 1.7 5.5 5M15 14.4c2.5-.4 5 .9 6 4.6"/>', 'aria-hidden="true"'),
  stop: G('<rect x="6" y="6" width="12" height="12" rx="1.5"/>', 'aria-hidden="true"'),
  send: G('<path d="M4 12l16-8-6 16-2.6-6.4z"/><path d="M11.4 13.6L20 4"/>', 'aria-hidden="true"'),
  close: G('<path d="M6 6l12 12M18 6L6 18"/>', 'aria-hidden="true"'),
  watch: (sec) => G(`<circle cx="12" cy="13.5" r="7.5"/><path d="M10 3h4M12 3v3"/><path class="hand" d="M12 13.5V9" style="transform: rotate(${sec * 6}deg)"/>`, 'class="watch" aria-hidden="true"'),
  caret: G('<path d="M9 5l7 7-7 7"/>', 'class="caret" aria-hidden="true"'),
  door: G('<path d="M7 17L17 7M9 7h8v8"/>', 'aria-hidden="true"'),
  wait: '<svg class="wait" viewBox="0 0 40 40" aria-hidden="true"><circle cx="20" cy="20" r="15"/></svg>',
  lock: G('<rect x="4.5" y="10.5" width="15" height="10" rx="2"/><path d="M8 10.5V7.5a4 4 0 0 1 8 0v3"/>', 'aria-hidden="true"'),
};
// A refused Stop, in fin's words, by the refusal's reason key and never its sentence (SessionDrive.Refusal). Ended and
// a head's 404 draw no line: the card's re-read state says it. A closed pane splice opened says only that, because
// nothing runs there to stop. Anything unmatched is "Not stopped", and every cause goes to the log.
const STOP_REFUSED = { closed: "Terminal closed", not_ours: "Not stopped · Started elsewhere", pane_gone: "Not stopped · Terminal closed",
  pane_taken: "Not stopped · Terminal reused", no_terminal: "Not stopped · No terminal" };
const stopRefusal = (res) => {
  if (res.ok || res.status === 404 || res.body?.reason === "ended") return null;
  if (res.body?.error) console.warn("stop refused:", res.body.error);
  return STOP_REFUSED[res.body?.reason] ?? "Not stopped";
};
// AN ABSENT FIELD IS NOTHING, NOT THE WORD "undefined". The drawing's own esc was String(s), which was safe
// there because the mock's data was always complete; a real route leaves a field out whenever splice does not
// know it, and `String(undefined)` prints "undefined" on the screen as though splice knew that. Caught by
// splice-builder3 reading teams.js against this file (Oct 10, 2026) before it reached a page.
const esc = (s) => String(s ?? "").replace(/[&<>"]/g, (c) => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;" })[c]);
// WHAT IS NOT READ YET DRAWS AS ITS OWN SHAPE (Marcos, Oct 12: "we can do better than that reading indicator"): a
// figure or a line is a striped bar the size of what will land there, a card a drawn card of such bars, a chart its
// columns, so a page reads as arriving, never as empty or as a lone word. Widths are in rem; console.css draws them.
const GHOST = {
  bar: (rem, cls = "") => `<i class="ghost${cls ? ` ${cls}` : ""}" style="width:${rem}rem" aria-hidden="true"></i>`,
  lines: (widths) => `<div class="ghost-lines" aria-hidden="true">${widths.map((w) => GHOST.bar(w)).join("")}</div>`,
  // [i] fades the card down the page: the first is whole, each later one lighter
  card: (widths, i = 0, cls = "") => `<article class="card ghosted gcard${cls ? ` ${cls}` : ""}" style="--i:${i}" aria-hidden="true">${GHOST.bar(widths[0], "big")}${GHOST.lines(widths.slice(1))}</article>`,
  chart: (heights) => `<div class="card ghost-chart" aria-hidden="true">${heights.map((h) => `<i class="ghost" style="--h:${h}%"></i>`).join("")}</div>`,
};
const clock = (d) => {
  const h = d.getHours() % 12 || 12, m = d.getMinutes(), ap = d.getHours() < 12 ? "AM" : "PM";
  return `${h}:${String(m).padStart(2, "0")} ${ap}`;
};
// A key is an account: its variable and the day splice first saw it, "OPENROUTER_API_KEY · Sep 14" (fin).
const dayWord = (d) => d.toLocaleDateString("en-US", { month: "short", day: "numeric" });
// When a window comes back, as Accounts says it: a time today, else the day and the time.
const backWord = (d) => (d.toDateString() === NOW.toDateString() ? clock(d) : `${d.toLocaleDateString("en-US", { weekday: "short" })} ${clock(d)}`);
const counter = (ms) => { const s = Math.floor(ms / 1000); return `${Math.floor(s / 60)}:${String(s % 60).padStart(2, "0")}`; };
/** A dollar figure as every page says it (Marlin's money form, fin, Oct 10 and 12): an estimate from the rate card is
 *  "≈". Under a cent it keeps two significant digits, so a tiny reply reads "≈$0.00012" and never "<$0.0001" or
 *  zeros, and "<$0.000001" only below a millionth. A cent and up is cents. Nothing charged is exact, "$0". */
const dollars = (v) => (v === 0 ? "$0" : v < 0.000001 ? "<$0.000001" : `≈$${v.toLocaleString("en-US", v < 0.0095 ? { minimumSignificantDigits: 2, maximumSignificantDigits: 2 } : { minimumFractionDigits: 2, maximumFractionDigits: 2 })}`);
const kTok = (n) => (n >= 999500 ? `${+(n / 1e6).toFixed(n >= 1e7 ? 0 : 1)}M` : n >= 1e4 ? `${Math.round(n / 1000)}K` : n >= 1000 ? `${+(n / 1000).toFixed(1)}K` : `${n}`);
const inline = (s) => esc(s).replace(/`([^`]+)`/g, "<code>$1</code>");
function md(text) { // the agent's own text, formatted as it wrote it: paragraphs, lists, code
  const out = []; let list = null;
  for (const line of text.split("\n")) {
    if (line.startsWith("- ")) { (list ??= []).push(`<li>${inline(line.slice(2))}</li>`); continue; }
    if (list) { out.push(`<ul>${list.join("")}</ul>`); list = null; }
    if (line.trim()) out.push(`<p>${inline(line)}</p>`);
  }
  if (list) out.push(`<ul>${list.join("")}</ul>`);
  return out.join("");
}
const acctPlanHtml = (acct, plan) => `<span class="acct">${esc(acct)}${plan ? `<span class="pv">${esc(plan)}</span>` : ""}</span>`;

// ---------- what a page says about a session: fin's states, element first and the word small ----------
//
// THESE READ THE DRAWING'S SESSION SHAPE, NOT THE DAEMON'S ROW: `s.state`, `s.ask` and `s.stall`. Sessions builds that
// shape in its sessionOf, with `stall` from stallOf over the live turn (/api/heads/{head}/turns/live), the turns-side
// read Marlin ruled into 0.4.0 for Sessions and Teams both. Teams reads the daemon's row through its own `stateOf`
// (splice-builder3) and takes stallOf the same way.
// why: half a minute with no word from the provider is a silence he would want to see and maybe stop, while shorter
// pauses are ordinary between thoughts. A display threshold only: nothing is ended by it (Idle is a probe, never an
// error). A ping is heard: every byte the provider sends resets idle_ms (SseReader onBytes), so a model thinking
// with pings never reads Stalled (Marlin; desk walk, Oct 10: idle stayed under 5 s across a 36 s pinging hold).
const STALL_SHOWN_MS = 30000;
// The ceiling: a head whose gate serves a tighter tier (/api/heads gate.first_byte_timeout_ms before any output,
// gate.stream_idle_ms after) reads Stalled sooner, once idle passes that tier; a head at the defaults keeps Marlin's
// half minute (splice-lead, Oct 13, gap 396).
// why: under five seconds of quiet is ordinary streaming; a countdown that flickered on every pause would be noise.
const RESUME_SHOWN_MS = 5000;
// why: the continuations a round may spend on re-anchors (DEFAULT_MAX_CONTINUATIONS, Turn.kt:266); past them nothing resumes.
const MAX_CONTINUATIONS = 5;
// The endings that hold a session back until something changes, as the session row's ended_by names them (SessionsWiring).
const LIMIT_ENDINGS = new Set(["error:plan-limit", "error:all-accounts-exhausted"]);
// The endings where splice re-sent a request until it gave up: the provider kept failing, or the turn ran past its cap.
// Nothing will send it again, so the session needs him (Marlin #2).
const GAVE_UP_ENDINGS = new Set(["error:upstream-failed", "error:turn-cap"]);
/** What the session is waiting on. With a request in flight, from the live turn splice holds (LiveTurnsRoutes): refused
 *  and re-sent before any answer, quiet with a re-send coming (the head's stall_reanchor_ms, /api/heads gate), or quiet
 *  with none. With none, from how its newest request ended (the row's ended_by): turned away at a spent plan or pool
 *  (At limit, until the window named comes back) or for want of a credential (Signed out). A plan name has no source
 *  yet, so `plan` is null. Sessions and Teams both read it (Marlin, Oct 10). `at` is when it was read, so the
 *  counters move each second between reads. [head] is the page's /api/heads entry ({ reanchorMs, idleMs, firstByteMs });
 *  a bare number is read as reanchorMs alone, so a caller not yet passing the entry keeps the fixed stall threshold. */
function stallOf(turn, head, endedBy) {
  const at = Date.now();
  const { reanchorMs, idleMs, firstByteMs } = head !== null && typeof head === "object" ? head : { reanchorMs: head };
  if (!turn) {
    if (endedBy?.outcome === "error:auth-missing") return { kind: "signout", at };
    if (GAVE_UP_ENDINGS.has(endedBy?.outcome)) return { kind: "gaveup", word: OUTCOME_WORD[endedBy.outcome], n: endedBy.retries ?? 0, since: endedBy.run_since_ms ?? null, at };
    if (!LIMIT_ENDINGS.has(endedBy?.outcome) || (endedBy.reset_ms != null && endedBy.reset_ms <= at)) return null;
    return { kind: "limit", acct: endedBy.account ?? null, plan: null, until: endedBy.reset_ms ? new Date(endedBy.reset_ms) : null, at };
  }
  const idle = turn.idle_ms ?? 0;
  if (turn.retries > 0 && !turn.seen_output) return { kind: "retries", n: turn.retries, at };
  // will_resume is the wire saying whether splice would re-send this round now; a round that already emitted a tool
  // call never is, whatever the head's tier. Only an explicit false says so: a daemon that does not publish it, or a
  // turn it has not decided, says nothing, and the card stays bare Stalled (fin, Oct 10).
  // Before the first byte a refusal or a dropped connection is retried, not resumed, so the fact speaks only once the
  // provider has begun answering.
  const wontResume = turn.seen_output === true && turn.will_resume === false;
  if (reanchorMs && !wontResume && (turn.resumes ?? 0) < MAX_CONTINUATIONS && idle >= RESUME_SHOWN_MS) return { kind: "silent", ms: idle, resumeMs: Math.max(0, reanchorMs - idle), at };
  const stallMs = Math.min(STALL_SHOWN_MS, (turn.seen_output ? idleMs : firstByteMs) ?? STALL_SHOWN_MS);
  if (idle >= stallMs) return { kind: "silent", ms: idle, wontResume, at };
  return null;
}

// A silence splice will end itself (the head's re-anchor tier, continuations left) is still Working on the card: nothing
// for him to do. Opened, its line counts down to the re-send in the working colour (fin, console-lead's stall states,
// Oct 10). Only a silence nothing will resume is Stalled.
const resuming = (s) => s.state === "working" && s.stall?.resumeMs != null;
// A limit or a sign-out ended the turn, so it holds an idle session as well as a working one.
const heldByEnding = (s) => s.stall?.kind === "limit" || s.stall?.kind === "signout";
// splice gave up re-sending his newest request: it waits on him as a question does, with the outcome's own word and
// how many times it was re-sent (fin), the count in the key's ink and the retries glyph in the key-light ring (hitstop 605ab5c)
// IDLE OR NOT. splice launches every client in its persistent-retry mode (CLAUDE_CODE_RETRY_WATCHDOG, LaunchService), so
// a client whose provider keeps failing does not go idle: it waits up to five minutes and re-sends, up to 300 times,
// and reads busy the whole while. Requiring idle meant this state never drew on the session it exists for (march-csv,
// busy nine hours on a provider that refused every request), and that session fell to the Stalled branch, which has no
// lamp for "gaveup" and printed the word "undefined" (console-lead, desk walk, Oct 11). stallOf only answers gaveup
// when no request is in flight, so the moment the client re-sends, the live turn's own state takes the card again.
const gaveUp = (s) => s.stall?.kind === "gaveup";
// THE WORDS NEVER CHANGE; ONLY THE COLOUR AND RANK MOVE WITH THE CLOCK (Marlin, Oct 11, 10:53; fin's words). While the
// client is still re-sending and the run of refusals is under half an hour old, the card is Stalled: the client may
// yet get through, as "Resumes in" is for a silence. Idle, or busy half an hour or more, it is Needs you: the provider
// is not coming back on its own soon, and moving or stopping it is his act. The run is dated by the daemon (the row's
// run_since_ms, the first request splice gave up on since the last clean answer), never from when the page loaded, so
// a busy card whose daemon names no start stays Stalled rather than guessing.
const GAVE_UP_NEEDS_MS = 30 * 60_000;
const gaveUpNeeds = (s) => gaveUp(s)
  && (s.state !== "working" || (s.stall.since != null && s.stall.at - s.stall.since >= GAVE_UP_NEEDS_MS));
const stalled = (s) => Boolean(s.stall) && (s.state === "working" ? !resuming(s) : s.state === "idle" && heldByEnding(s));
/** A /api/sessions row as every page reads it: its state, and its stall from the live turn splice holds for it (the
 *  head's re-anchor tier from /api/heads). Sessions draws its cards from this; Teams and Requests rank projects by it. */
function rowLook(row, turn, head) {
  const ended = row.availability === "gone";
  // A stale row (alive, but its registry entry not refreshed in 30 minutes) is not read as working off its own status,
  // only off a live turn splice holds for it (spec §4: unavailable is never shown active; splice-providers' spec pass,
  // Oct 12). Sessions' card already says "Not heard from since", so it takes no word here.
  const working = !ended && (Boolean(turn) || (row.availability !== "stale" && (row.status === "working" || row.status === "busy")));
  const state = ended ? "ended" : row.waiting_for ? "needs" : working ? "working" : "idle";
  return { state, stall: state === "working" || state === "idle" ? stallOf(turn, head, row.ended_by) : null };
}
/** How urgent a session is, in the cards' rank: waiting on him, stalled, working, the rest. */
const rankOf = (s) => (s.state === "needs" || gaveUpNeeds(s) ? 0 : stalled(s) ? 1 : s.state === "working" ? 2 : 3);
const rowAt = (row) => +new Date(row.updated_at || row.status_updated_at || row.started_at || 0);
/** How long a live turn has been held since the gate admitted it (age_ms, splice-turns d42eeda99), counted up from
 *  when it was read: seconds, then minutes (fin). */
const heldFor = (t) => {
  const secs = Math.floor(Math.max(0, t.age_ms + (t.readAt ? Date.now() - t.readAt : 0)) / 1000);
  return secs < 60 ? `${secs} s` : `${Math.floor(secs / 60)} min`;
};
/** A working session in its gate's words (fin, Oct 13): the turn's time rides the word, a compaction reads Compacting,
 *  and a turn whose request is sent with no first byte back says No output yet beside it. A turn the gate holds no
 *  slot for reads Working, as before. */
function workingLook(s) {
  const t = s.turn;
  // a turn the gate has queued and not let in yet reads In line, as a held request reads Waited in line (fin), counted
  // since it entered the queue (splice-turns cc0181422)
  if (t?.phase == null && s.wait) {
    return { cls: "working", lamp: ICON.trace, word: `In line · <span data-held="${esc(s.id)}">${heldFor(s.wait)}</span>` };
  }
  if (t?.phase == null) return { cls: "working", lamp: ICON.trace, word: "Working" };
  const word = `${t.compact ? "Compacting" : "Working"} · <span data-held="${esc(s.id)}">${heldFor(t)}</span>`;
  return { cls: "working", lamp: ICON.trace, word, detail: t.phase === "connect" ? `<span class="detail">No output yet</span>` : "" };
}
function look(s) {
  if (s.state === "needs") return { cls: "needs", lamp: ICON[s.ask.kind], word: "Needs you" };
  if (gaveUp(s)) {
    // ONE INK PER PHASE (fin; hitstop, Oct 11): the word and both details take the limit's ink while it is Stalled, and
    // the key's ink once it is Needs you. The plain detail class is the limit's; "retried" is the key's.
    const needs = gaveUpNeeds(s), ink = needs ? "detail retried" : "detail";
    const n = s.stall.n, count = n ? `<span class="${ink}">${n} ${n === 1 ? "retry" : "retries"}</span>` : "";
    const since = s.stall.since != null ? `<span class="${ink}">since ${backWord(new Date(s.stall.since))}</span>` : "";
    return { cls: needs ? "needs" : "stalled", lamp: ICON.retries, word: s.stall.word, detail: count + since };
  }
  if (resuming(s)) return { cls: "working", lamp: ICON.trace, word: "Working",
    paneDetail: `<span class="detail resumes" data-resume="${s.id}">${ICON.watch(Math.floor(s.stall.resumeMs / 1000))}<span>Resumes in ${counter(s.stall.resumeMs)}</span></span>` };
  if (stalled(s)) {
    const k = s.stall.kind, lamp = k === "silent" ? ICON.flat : ICON[k];
    // "Won't resume" ends the line Stalled begins, in its colour and with no glyph of its own (fin, hitstop 131ad54)
    const detail = k === "silent" ? `<span class="detail" data-silent="${s.id}">${ICON.watch(Math.floor(s.stall.ms / 1000))}<span>${counter(s.stall.ms)}</span></span>${s.stall.wontResume ? `<span class="detail noresume">Won't resume</span>` : ""}`
      : k === "retries" ? `<span class="detail">${s.stall.n} ${s.stall.n === 1 ? "retry" : "retries"}</span>`
      : k === "limit" ? `${s.stall.acct ? acctPlanHtml(s.stall.acct, s.stall.plan) : ""}${s.stall.until ? `<span class="detail">Resets ${backWord(s.stall.until)}</span>` : ""}` : "";
    // a plan's limit ended the turn; nothing stalled, so the word is Accounts' own (fin): lock, At limit, the plan, its reset
    if (k === "limit") return { cls: "stalled", lamp, word: `${ICON.lock}At limit`, detail };
    // no credential ended it, so nothing stalled either: the word is the state itself, as Accounts says it (fin)
    // its act is the way back: Accounts opened on that command's sign-in or key, when the page knows the command (fix)
    const fix = s.stall.fix ? `<a class="detail fix" href="${esc(s.stall.fix.href)}">${esc(s.stall.fix.word)}</a>` : "";
    if (k === "signout") return { cls: "stalled", lamp, word: "Signed out", detail: fix };
    return { cls: "stalled", lamp, word: "Stalled", detail };
  }
  if (s.state === "working") return workingLook(s);
  if (s.state === "stopped") return { cls: "stopped", lamp: "<i></i>", word: "Stopped" };
  if (s.state === "ended") return { cls: "ended", lamp: "<i></i>", word: "Ended" };
  return { cls: "idle", lamp: "<i></i>", word: "Idle" };
}
// Stop shows while splice holds the turn's stream open (LiveTurns.kt:3-8): Working, a silent stall and retries,
// which splice runs inside the one turn. A limit and a sign-out end the turn with an outcome
// (OutcomeSentences.kt:58-68), and a question or a permission arrives after the stream has ended, so a Needs
// you card's act is its answer and Deny its stop.
const live = (s) => Boolean(s.live);

// list and open item sit side by side exactly where the .page container query (min-width: 104rem) puts them:
// the page's content box, padding out, so a page never opens an item the CSS can only show in place of its list
const sideBySide = () => {
  const p = document.querySelector(".page"), cs = getComputedStyle(p);
  return (p.clientWidth - parseFloat(cs.paddingLeft) - parseFloat(cs.paddingRight)) / parseFloat(getComputedStyle(document.documentElement).fontSize) >= 104;
};

// ---------- the one nav ----------
// A LINK IS HERE ONLY ONCE ITS PAGE IS (Marlin, Oct 10, 2026). The control plane serves what is packaged under
// console/ and nothing else can resolve, and builder2's walk of the installed build found seven of eight links
// leading to a 404 with an empty body, which a first-time reader cannot tell from a slow page. 0.4.0 ships
// eight pages in this order: Accounts, Sessions, Teams, Requests, Usage, Models, Compare models, Settings. A
// page joins this list in the commit that brings it, and until then its link does not exist. THIS LIST IS THE
// ONE PLACE: a page that hand-wrote its own nav could disagree with every other page's, and seven of them did.
// The seat that lands a page adds ITS OWN line here in the same commit, because a line added early is the
// 404 this list exists to prevent: the jar carries what the commit carries, not what a working tree has.
const PAGES = [
  ["accounts.html", "Accounts"],
  ["sessions.html", "Sessions"],
  ["teams.html", "Teams"],
  ["requests.html", "Requests"],
  ["usage.html", "Usage"],
  ["models.html", "Models"],
  ["compare.html", "Compare models"],
  ["settings.html", "Settings"],
];
// THE THEME HOLDS ACROSS PAGES AND LOADS: the toggle saves it, and every page applies the saved one as kit.js loads.
// A page with nothing saved keeps the night its HTML opens in.
const THEME_KEY = "splice-console-theme";
try { const saved = localStorage.getItem(THEME_KEY); if (saved === "day" || saved === "night") document.documentElement.dataset.theme = saved; } catch { /* storage off: the page keeps its own theme */ }
// A PRESS THAT CUTS OR MOVES SOMEONE ASKS ONCE (Marlin, Oct 13, from Settings' Restart splice, Oct 12): the first press
// reads which sessions have a request in flight on the commands it touches, off each command's live turns the way
// Sessions reads them. None, and the press acts at once; some, and it arms with their names, each a chip that opens its
// session, and the second press acts. [heads] null is every command splice runs.
async function midRequest(heads = null) {
  const keys = heads ?? ((await API.get("/api/heads")).body?.heads || []).map((h) => h.key);
  const [lives, sessions] = await Promise.all([
    Promise.all(keys.map((h) => API.get(`/api/heads/${encodeURIComponent(h)}/turns/live`))),
    API.get("/api/sessions"),
  ]);
  const names = new Map((sessions.body?.sessions || []).map((x) => [x.session_id, x.name || x.title]));
  const ids = [...new Set(lives.flatMap((r) => (r.body?.turns || []).filter((t) => t.session && !t.stopped).map((t) => t.session)))];
  return ids.map((id) => ({ id, name: names.get(id) }));
}
/** The armed question: what the act does to them, their chips, then the act and the way out (both already drawn). */
const armedAsk = (said, cut, act, keep, tail = "") => `<span class="armed cutask"><span class="said">${esc(said)}</span>`
  + cut.map((x) => `<a class="chip" href="sessions.html#${encodeURIComponent(x.id)}"><i aria-hidden="true"></i>${esc(x.name || x.id.slice(0, 8))}</a>`).join("")
  + `${tail}${act}${keep}</span>`;
function toggleTheme() {
  const h = document.documentElement;
  h.dataset.theme = h.dataset.theme === "day" ? "night" : "day";
  try { localStorage.setItem(THEME_KEY, h.dataset.theme); } catch { /* storage off: this page still switches */ }
}
// WITH ONE PAGE THE NAV DRAWS NOTHING (hitstop, Oct 10): a lone link repeats the page's own title, and a single
// selected box reads as a strip with its other tabs torn off. The column keeps its width, its wordmark and its
// theme toggle either way, so nothing moves when the links arrive.
function drawNav() {
  const side = document.querySelector(".side");
  if (!side || PAGES.length < 2 || side.querySelector(".nav")) return;
  // the page names itself by its title, so any address it was opened at (a query, a bare "/", no ".html") marks its own
  // item, and a page no item names marks none rather than Accounts (splice-lead, Oct 12, from builder2's retake)
  const own = document.title.split(" · ")[0], file = location.pathname.split("/").pop();
  const here = PAGES.find(([f, name]) => name === own || f === file)?.[0];
  const links = PAGES.map(([file, name]) =>
    `<a href="${file}"${file === here ? ' aria-current="page"' : ""}>${esc(name)}</a>`).join("");
  const nav = document.createElement("nav");
  nav.className = "nav";
  nav.setAttribute("aria-label", "Pages");
  nav.innerHTML = links;
  side.insertBefore(nav, side.querySelector(".foot"));
}
drawNav();
document.querySelector('.side [data-act="theme"]')?.addEventListener("click", toggleTheme);

// THE STALE MARK (web-console.md:385, :397; gap 460): splice.toml on disk no longer matches what the daemon booted with,
// so a change written there, by this console or by hand, waits on a restart. /health says so as topologyStale; the
// mark sits in the side column's foot on every page and is absent while the two match. Read once the page's scripts have
// loaded (API comes after this file), then every 15 s; an unanswered read leaves the mark as it was.
const STALE_READ_MS = 15_000;
async function markStale() {
  const foot = document.querySelector(".side .foot");
  const res = foot ? await API.get("/health") : null;
  if (!res?.ok) return;
  const mark = foot.querySelector(".stale");
  if (res.body?.topologyStale !== true) { mark?.remove(); return; }
  if (mark) return;
  const add = document.createElement("span");
  add.className = "stale";
  add.textContent = "splice.toml changed · restart to apply";
  foot.prepend(add);
}
addEventListener("DOMContentLoaded", () => { markStale(); setInterval(markStale, STALE_READ_MS); });

// ---------- the project pick: ONE PICK across Sessions, Teams and Requests (Marlin, Oct 11) ----------
// Choosing a project on any of them changes it on all of them, so it lives in this browser and in the address: the
// address wins when he arrives by a link ("Open on Requests"), and what he picked last carries to the next page he
// opens. The key is the project's id as the daemon writes it (ProjectWire), the one thing every page's rows share.
// The doors are what keep one shared pick safe: while a project is picked, every other project with someone waiting
// on him stands beside the picker as its name and its Needs-you count, one press from being the pick.
const PICK_STORE = "splice.project";
const PICK = {
  get() {
    const at = new URLSearchParams(location.search).get("project");
    return at !== null ? at || null : localStorage.getItem(PICK_STORE) || null;
  },
  set(key) {
    if (key) localStorage.setItem(PICK_STORE, key); else localStorage.removeItem(PICK_STORE);
    const q = new URLSearchParams(location.search);
    if (key) q.set("project", key); else q.delete("project");
    const s = q.toString();
    history.replaceState(history.state, "", `${location.pathname}${s ? `?${s}` : ""}${location.hash}`);
  },
};
// a door's count is the button's size: its glyph is Needs you's, small, in Needs you's ink
const NEEDS_LAMP = (n) => `<span class="pc needs"><span class="plamp" aria-hidden="true">${ICON.input}</span><b>${n}</b></span>`;
/** The picker and the doors. [projects] is [[key, label]] in the pages' group order; [doors] is [[key, label, waiting]]
 *  for the projects with someone waiting, drawn only while one project is picked; [open] whether the menu is down. */
// A PROJECT'S FIGURE AT EVERY WIDTH (Marlin's walk, Oct 12): below the tiles' width the picker and its menu carry the
// same figure each tile does on that page (sessions, teams, or requests in the window), so "how many did temple send"
// is one glance and the menu says where the work is before he picks. [projects] are [key, label, total]; [all] is
// Every project's total. A total not read yet is null and draws nothing. [show] writes a figure: digits grouped unless
// the page counts something that runs to billions (Usage writes tokens short, "16 bn", Marlin).
const showCount = (n) => n.toLocaleString("en-US");
function pickerHtml(projects, pick, open, doors, all = null, show = showCount) {
  const label = (k) => projects.find(([key]) => key === k)?.[1] ?? doors.find(([key]) => key === k)?.[1] ?? "Every project";
  const totalOf = (k) => (k === "" ? all : projects.find(([key]) => key === k)?.[2] ?? null);
  const fig = (n) => (n == null ? "" : `<span class="pfig">${show(n)}</span>`);
  const item = (k, text) => `<button role="menuitemradio" aria-checked="${(pick ?? "") === k}" data-act="pickproj" data-p="${esc(k)}"><span class="pname">${esc(text)}</span>${fig(totalOf(k))}</button>`;
  const menu = open ? `<div class="menu pmenu" role="menu">${item("", "Every project")}${projects.map(([k, l]) => item(k, l)).join("")}</div>` : "";
  const ways = pick == null ? "" : doors.filter(([k, , n]) => k !== pick && n)
    .map(([k, l, n]) => `<button class="pdoor" data-act="pickproj" data-p="${esc(k)}" aria-label="${esc(`${l}, ${n} needs you`)}"><span class="pname">${esc(l)}</span>${NEEDS_LAMP(n)}</button>`).join("");
  return `<span class="pwrap"><button class="ppick${pick != null ? " on" : ""}" data-act="projmenu" aria-haspopup="menu" aria-expanded="${Boolean(open)}">`
    + `<span class="pname">${esc(pick == null ? "Every project" : label(pick))}</span>${fig(totalOf(pick ?? ""))}${ICON.caret}</button>${menu}</span>${ways}`;
}

// ---------- the project tiles: at a 150rem page and wider (2560 and up), every project at once (Marlin, fin) ----------
// A row of tiles across the page replaces the picker and the doors on Sessions, Teams and Requests alike: a tile is its
// project's header in another place, the name, the muted total beside it, and a card-size lamp and count per live
// state. "Every project" comes first and sums them. Pressing a tile is the pick, and the picked tile is raised like a
// Teams tab, with no word. Measured on the page's own content box, so every page agrees on when it is wide.
const TILES_AT_REM = 150;
function tilesWide() {
  const p = document.querySelector(".page");
  if (!p) return false;
  const cs = getComputedStyle(p), rem = parseFloat(getComputedStyle(document.documentElement).fontSize);
  return (p.clientWidth - parseFloat(cs.paddingLeft) - parseFloat(cs.paddingRight)) / rem >= TILES_AT_REM;
}
const TLAMP = { needs: ICON.input, stalled: ICON.flat, working: ICON.trace };
const tlampHtml = ([k, n]) => `<span class="pc big ${k}"><span class="tlamp ${k}" aria-hidden="true">${TLAMP[k]}</span><b>${n}</b></span>`;
// the brief's own words for a header read aloud: "temple, 1 needs you, 3 working, 7 sessions" (Marlin)
const STATE_WORDS = { needs: "needs you", stalled: "stalled", working: "working" };
/** "1 needs you, 3 working, 7 sessions": what a tile says to a screen reader, in the lamps' order. */
const countWords = (counts, total, unit, show = showCount) => [...counts.filter(([, n]) => n).map(([k, n]) => `${n} ${STATE_WORDS[k]}`),
  `${show(total)}${total === 1 ? unit : `${unit}s`}`].join(", ");
const sumCounts = (lists) => ["needs", "stalled", "working"].map((k) => [k, lists.flat().filter(([x]) => x === k).reduce((a, [, n]) => a + n, 0)]);
/** [items] is [[key, label, counts, total, words]] with Every project's key "". Each tile's preferred width is its name
 *  in its own font plus the total, so the row's room goes where the names are, and in a crowded row a long name
 *  truncates before its total does (the total sits beside the name, never at the lamps' end, where "4" and "1,300"
 *  would read as one number). */
function tilesHtml(items, pick, show = showCount) {
  return `<nav class="ptiles" aria-label="Projects">${items.map(([k, label, counts, total, words]) => {
    const on = (pick ?? "") === k, figure = total == null ? "" : show(total);
    // each tile is its own content's width, its whole name included (console.css .ptile)
    return `<button class="ptile${on ? " on" : ""}" data-act="pickproj" data-p="${esc(k)}" aria-pressed="${on}" aria-label="${esc(`${label}, ${words}`)}">`
      + `<span class="ptop"><span class="tname">${esc(label)}</span><span class="ptotal">${figure}</span></span>`
      + `<span class="plamps">${counts.filter(([, n]) => n).map(tlampHtml).join("")}</span></button>`;
  }).join("")}</nav>`;
}

// ---------- the projects as Teams and Requests see them: off /api/sessions rows, in Sessions' group order ----------
// Sessions groups its own cards (sessions.js groupsOf); the other pages read the same rows raw. The order is the same
// rule and the same reading (rowLook, rankOf, from the same live turns): the group with the most urgent live session
// first, then its latest activity, then its name, No project last. ONE ORDER (Marlin): a project that ranked one way
// here and another on Sessions would read as two facts.
const NO_PROJECT_KEY = "-";
// Compare models sends belong to no project and are not "No project" either: they are a group of their own, keyed so no
// project id (an absolute folder or git root) can equal it, and shown only where a page has rows of it.
const COMPARE_KEY = "compare";
const COMPARE_LABEL = "Compare models";
const rowProjKey = (r) => (r?.compare ? COMPARE_KEY : r?.project?.id ?? NO_PROJECT_KEY);
const rowProjName = (r) => (r?.compare ? COMPARE_LABEL : r?.project?.label || r?.project?.name || "No project");
/** [{key, label, counts, total}] for every project with a live session, in Sessions' group order. [turns] is the live
 *  turns by session id, [heads] the commands with their reanchorMs, as each page reads them for its own cards. */
function projectGroups(rows, turns = {}, heads = []) {
  const live = (r) => r.availability !== "gone";
  const ranks = new Map(rows.map((r) => {
    const turn = turns[r.session_id] ?? null;
    return [r, rankOf(rowLook(r, turn, heads.find((h) => h.key === turn?.head)))];
  }));
  const m = new Map();
  for (const r of rows) {
    const k = rowProjKey(r);
    if (!m.has(k)) m.set(k, { key: k, label: rowProjName(r), rows: [] });
    m.get(k).rows.push(r);
  }
  const groups = [...m.values()].filter((g) => g.rows.some(live)).map((g) => {
    const on = g.rows.filter(live);
    return {
      key: g.key, label: g.label, total: g.rows.length,
      counts: ["needs", "stalled", "working"].map((k, i) => [k, on.filter((r) => ranks.get(r) === i).length]),
      rank: Math.min(...on.map((r) => ranks.get(r))), at: Math.max(...on.map(rowAt)),
    };
  });
  return groups.sort((a, b) => (a.key === NO_PROJECT_KEY) - (b.key === NO_PROJECT_KEY)
    || a.rank - b.rank || b.at - a.at || a.label.localeCompare(b.label));
}
/** A pick that names a project by its name (an older door: Usage's ?project=splice) is that project's id. */
function reconcilePick(pick, groups) {
  if (pick == null || groups.some((g) => g.key === pick)) return pick;
  const g = groups.find((x) => x.label === pick || x.key.split("/").pop() === pick);
  if (g) PICK.set(g.key);
  return g ? g.key : pick;
}
/** The pick line Teams and Requests draw: the tiles at a wide page, else the picker and its doors, else, with one
 *  project, its name; nothing with no live session. The pick only exists where there are two projects to pick between.
 *  EACH PAGE COUNTS ITS OWN THING (Marlin): [count] is the tiles' totals, { of(key), all, unit }, teams on Teams and
 *  requests on Requests; a figure the page has not read is left off. The lamps stay the sessions', so someone waiting
 *  shows from every page. [count.show] writes a figure (Usage: tokens short), digits grouped when a page gives none. */
function pickLineHtml(groups, pick, open, count) {
  if (!groups.length) return "";
  if (groups.length < 2) return groups[0].key === NO_PROJECT_KEY ? "" : `<div class="lhead"><span class="pname solo">${esc(groups[0].label)}</span></div>`;
  const show = count.show ?? showCount;
  if (tilesWide()) {
    const words = (c, n) => (n == null ? countWords(c, 0, count.unit).replace(/(, )?0 \w+$/, "") : countWords(c, n, count.unit, show));
    const items = groups.map((g) => { const n = count.of(g.key); return [g.key, g.label, g.counts, n, words(g.counts, n)]; });
    const every = sumCounts(items.map((x) => x[2]));
    return tilesHtml([["", "Every project", every, count.all, words(every, count.all)], ...items], pick, show);
  }
  return `<div class="lhead">${pickerHtml(groups.map((g) => [g.key, g.label, count.of(g.key)]), pick, open, groups.map((g) => [g.key, g.label, g.counts[0][1]]), count.all, show)}</div>`;
}
