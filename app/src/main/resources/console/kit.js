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
// load except the one nav draw, which needs the top bar (aside.side) the page already wrote.

// ---------- the present ----------
// why read it again: a page open across a window's reset drew the reset as still ahead of a NOW from before it.
let NOW = new Date();
const tick = () => (NOW = new Date());

// ---------- the providers, as the stylesheet names their colours ----------
const COLORS = { claude: "--claude", gpt: "--gpt", grok: "--grok", kimi: "--kimi", muse: "--muse", router: "--router", deepseek: "--deepseek", local: "--local", vast: "--local" };
const PROVIDER_NAME = { claude: "Claude", gpt: "ChatGPT", grok: "Grok", kimi: "Kimi", muse: "Muse", router: "OpenRouter", deepseek: "DeepSeek", local: "This computer", vast: "vast.ai" };
// /api/models names a head's provider in the provider module's own words, which are not always the colour's. A family
// with no colour of its own draws in none, rather than borrowing another's.
const PROVIDER_FAMILY = { anthropic: "claude", openai: "gpt", codex: "gpt", xai: "grok", grok: "grok", moonshot: "kimi",
  kimi: "kimi", meta: "muse", muse: "muse", openrouter: "router", router: "router", deepseek: "deepseek",
  local: "local", vast: "vast" };
// A provider splice has no colour for draws in the muted text colour rather than in another provider's.
const colorOf = (provider) => `var(${COLORS[provider] || "--text-mute"})`;
/** The colour family of a provider /api/models names. The table above knows the words a provider module uses; a provider
 *  it does not know, one he named in splice.toml, is served from THIS COMPUTER when it answers on this machine's own
 *  loopback (his own model servers, the tunnel to a rented GPU), whatever it is called: the address is the fact, the name
 *  is not. [bases] is /api/topology's providers by name, where a page has read it; without it a name the table does not
 *  know stays itself, and colorOf draws it muted. */
const LOOPBACK_BASE = /^https?:\/\/(127\.0\.0\.1|\[?::1\]?|localhost)\b/;
const providerFamilyOf = (provider, bases = {}) =>
  PROVIDER_FAMILY[provider] ?? (COLORS[provider] ? provider : LOOPBACK_BASE.test(bases[provider]?.base_url || "") ? "local" : provider);
/** A plan login's card on Accounts has one address, spelled here for the page that draws the card and for every page that
 *  links to it (accounts.html?card=). loginCardId is the login's id on its provider's board: the commands it serves joined
 *  with "+", then its selector (its selector key, else its label, else "primary"). acctCardKey puts the provider's colour
 *  family before it, which is the card's key. [row] is a login as /api/accounts sends it. */
const loginCardId = (row) => `${(row.heads || []).join("+")}/${row.selector_key || row.label || "primary"}`;
const acctCardKey = (row) => `${providerFamilyOf(row.provider)}/${loginCardId(row)}`;

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
// Both give-up forms read "Given up after no progress" on a row (fin, Marlin, Oct 12): error:turn-cap here, and an overload
// stalled past its re-anchors through Requests' outcomeOf, which reads this entry. A silence is what the two share, and
// it is the console's word for the event: resumed, started over or given up. Not "No output": a turn can stream for
// minutes, then go quiet. No figure: the limit ignores pings (Watchdog.kt:148-150) and up_gap_max_ms counts them.
// WHY.gaveup.word stays "Given up" for the filter and Settings' tile.
OUTCOME_WORD["error:turn-cap"] = "Given up after no progress";
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
  silentover: { word: "Started over after no progress", has: (r) => !!r.resumedFresh && !!r.stallMs },
  silentresume: { word: "Resumed after no progress", has: (r) => !!r.resumed && !!r.stallMs },
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
// EVERY GLYPH THE KIT DRAWS HAS A SIZE OF ITS OWN (Oct 26): an svg with only a viewBox takes its box, so a kit icon
// dropped into a line the stylesheet has no rule for drew 300 px tall — ICON.door as an arrow across a card, DRAW.lock
// filling a login row. 1em is the size of the words beside it; any rule that wants another size still wins.
const G = (d, extra = "") => `<svg viewBox="0 0 24 24" width="1em" height="1em" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" ${extra}>${d}</svg>`;
const ICON = {
  trace: '<svg class="wave" viewBox="0 0 40 40" width="1em" height="1em" aria-hidden="true"><polyline class="base" points="5,20 13,20 16,12 20,28 24,15 27,20 35,20"/><polyline class="beat" points="5,20 13,20 16,12 20,28 24,15 27,20 35,20"/></svg>',
  flat: '<svg class="wave" viewBox="0 0 40 40" width="1em" height="1em" aria-hidden="true"><polyline points="5,20 35,20"/></svg>',
  dialog: '<svg viewBox="0 0 24 24" width="1em" height="1em" aria-hidden="true"><path d="M12 3l7 3v5.5c0 4.4-3 8-7 9.5-4-1.5-7-5.1-7-9.5V6z"/></svg>',
  input: '<svg viewBox="0 0 24 24" width="1em" height="1em" aria-hidden="true"><path d="M4 5h16v11h-9l-4.5 3.5V16H4z"/><path d="M12 13.2h0"/><path d="M10 9.2a2 2 0 1 1 2.6 1.9"/></svg>',
  retries: '<svg viewBox="0 0 24 24" width="1em" height="1em" aria-hidden="true"><path d="M4.5 11a7.5 7.5 0 0 1 13.2-4.6M19 4v4.5h-4.5M19.5 13a7.5 7.5 0 0 1-13.2 4.6M5 20v-4.5h4.5"/></svg>',
  limit: '<svg viewBox="0 0 24 24" width="1em" height="1em" aria-hidden="true"><rect x="4.5" y="10.5" width="15" height="10" rx="2"/><path d="M8 10.5V7.5a4 4 0 0 1 8 0v3"/></svg>',
  signout: '<svg viewBox="0 0 24 24" width="1em" height="1em" aria-hidden="true"><path d="M14 4h5v16h-5M10 8l-4 4 4 4M6 12h9"/></svg>',
  branch: G('<circle cx="6" cy="5" r="2.2"/><circle cx="6" cy="19" r="2.2"/><circle cx="18" cy="7" r="2.2"/><path d="M6 7.2v9.6M18 9.2c0 4.5-6 3.6-11.3 7.4"/>', 'aria-hidden="true"'),
  team: G('<circle cx="8.5" cy="9" r="3"/><circle cx="16.5" cy="9.5" r="2.5"/><path d="M3 19c.8-3.3 2.9-5 5.5-5s4.7 1.7 5.5 5M15 14.4c2.5-.4 5 .9 6 4.6"/>', 'aria-hidden="true"'),
  stop: G('<rect x="6" y="6" width="12" height="12" rx="1.5"/>', 'aria-hidden="true"'),
  send: G('<path d="M4 12l16-8-6 16-2.6-6.4z"/><path d="M11.4 13.6L20 4"/>', 'aria-hidden="true"'),
  close: G('<path d="M6 6l12 12M18 6L6 18"/>', 'aria-hidden="true"'),
  watch: (sec) => G(`<circle cx="12" cy="13.5" r="7.5"/><path d="M10 3h4M12 3v3"/><path class="hand" d="M12 13.5V9" style="transform: rotate(${sec * 6}deg)"/>`, 'class="watch" aria-hidden="true"'),
  caret: G('<path d="M9 5l7 7-7 7"/>', 'class="caret" aria-hidden="true"'),
  door: G('<path d="M7 17L17 7M9 7h8v8"/>', 'aria-hidden="true"'),
  wait: '<svg class="wait" viewBox="0 0 40 40" width="1em" height="1em" aria-hidden="true"><circle cx="20" cy="20" r="15"/></svg>',
  lock: G('<rect x="4.5" y="10.5" width="15" height="10" rx="2"/><path d="M8 10.5V7.5a4 4 0 0 1 8 0v3"/>', 'aria-hidden="true"'),
  // the two themes, as the top bar's switch shows them: the one in use is lit and the other waits (drawTheme)
  sun: G('<circle cx="12" cy="12" r="4.2"/><path d="M12 2.6v2.2M12 19.2v2.2M2.6 12h2.2M19.2 12h2.2M5.4 5.4l1.6 1.6M17 17l1.6 1.6M18.6 5.4L17 7M7 17l-1.6 1.6"/>', 'class="sun" aria-hidden="true"'),
  moon: G('<path d="M21 12.8A9 9 0 1 1 11.2 3a7 7 0 0 0 9.8 9.8z"/>', 'class="moon" aria-hidden="true"'),
  // something went wrong where a person was going: the route error's mark (console.js)
  bang: G('<circle cx="12" cy="12" r="9"/><path d="M12 7.6v5.2M12 16.2h0"/>', 'aria-hidden="true"'),
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
// A GHOST IS THE SIZE AND THE SHAPE OF WHAT WILL LAND, so nothing moves when the data arrives, and an item keeps its own
// state visible while the page reads. The stripes move at .9 s a cycle, which is the one loop that means "still working".
const GHOST = {
  bar: (rem, cls = "") => `<i class="ghost${cls ? ` ${cls}` : ""}" style="width:${rem}rem" aria-hidden="true"></i>`,
  lines: (widths) => `<div class="ghost-lines" aria-hidden="true">${widths.map((w) => GHOST.bar(w)).join("")}</div>`,
  // [i] fades the card down the page: the first is whole, each later one lighter
  card: (widths, i = 0, cls = "") => `<article class="card ghosted gcard${cls ? ` ${cls}` : ""}" style="--i:${i}" aria-hidden="true">${GHOST.bar(widths[0], "big")}${GHOST.lines(widths.slice(1))}</article>`,
  chart: (heights) => `<div class="card ghost-chart" aria-hidden="true">${heights.map((h) => `<i class="ghost" style="--h:${h}%"></i>`).join("")}</div>`,
  // AN ITEM'S OWN STATE LINE, which the kit had no shape for, so nine pages printed the word "Reading" where a state
  // belongs (the old page's rail said it on 7 of 11 cards). A card draws this where its state word will land and keeps
  // everything else it already knows.
  state: () => `<i class="ghost state" aria-hidden="true"></i>`,
  fig: (rem = 3.6) => `<i class="ghost fig" style="width:${rem}rem" aria-hidden="true"></i>`,
  // a share not read yet: the meter's own empty track, striped. Never a 0% fill, which reads as "nothing used".
  track: () => `<i class="ghost track" aria-hidden="true"></i>`,
  stack: () => `<i class="ghost stack" aria-hidden="true"></i>`,
  ring: (cls = "") => `<i class="ghost ring${cls ? ` ${cls}` : ""}" aria-hidden="true"></i>`,
  tile: () => `<i class="ghost tile" aria-hidden="true"></i>`,
  // a row of shapes the size of the row that will land: give it the pieces in order
  row: (parts) => `<div class="ghost-row" aria-hidden="true">${parts.join("")}</div>`,
  // THE REST OF A SECTION'S SHAPES (Oct 27), so a page draws its first frame from the kit and not by hand (three pages
  // drew their own, and jumped by up to 1,093 px when the data landed):
  /** the hero figure and its caption on one baseline (DRAW.hero) */
  hero: () => `<div class="pd-top" aria-hidden="true"><i class="ghost hero"></i><i class="ghost cap"></i></div>`,
  /** a focal figure and its words (DRAW.focal) */
  focal: (rem = 7) => `<div class="pd-sub" aria-hidden="true"><i class="ghost focalfig"></i>${GHOST.bar(rem)}</div>`,
  /** a chip, its own size (DRAW.chip) */
  chip: (rem = 6.2) => GHOST.bar(rem, "chip"),
  /** a bar cell: the column's track and its figure on the column's right edge (DRAW.barCell) */
  cell: () => `<span class="barcell" aria-hidden="true"><i class="ghost vtrack"></i><i class="ghost fig v"></i></span>`,
  /** a range on the shared log axis (DRAW.range) */
  range: () => `<i class="ghost range" aria-hidden="true"></i>`,
  /** THE PROJECT TILES WHILE THE PROJECTS ARE READ (Oct 27): the tiles land only once the transcripts are listed, the
   *  last read on a page, so a line that drew nothing jumped the page down by the tiles' height when they came. [n]
   *  tiles on the tiles' own equal columns at a wide page (tilesWide), else the picker's chip; a page with no count
   *  to go by passes 0 and draws nothing. Requests and Usage both draw it. */
  tiles: (n) => (!(n > 0) ? ""
    : tilesWide() ? `<nav class="ptiles" aria-hidden="true">${Array.from({ length: n }, () => `<i class="ghost ptg"></i>`).join("")}</nav>`
      : `<div class="lhead" aria-hidden="true">${GHOST.chip(13)}</div>`),
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
 *  zeros, and "<$0.000001" only below a millionth. A cent and up is cents. Nothing charged is exact, "$0".
 *  WHOLE DOLLARS FROM $100 UP (Oct 27): a week's spend runs to five digits, and two digits of cents on a figure nobody
 *  reconciles to the cent widened every money column on Usage; "$20,390", while a figure under $100 keeps its cents. */
const DOLLAR_FORM = (v) => (v < 0.0095 ? { minimumSignificantDigits: 2, maximumSignificantDigits: 2 }
  : v >= 99.995 ? { maximumFractionDigits: 0 } : { minimumFractionDigits: 2, maximumFractionDigits: 2 });
const dollars = (v) => (v === 0 ? "$0" : v < 0.000001 ? "<$0.000001" : `≈$${v.toLocaleString("en-US", DOLLAR_FORM(v))}`);
const kTok = (n) => (n >= 999500 ? `${+(n / 1e6).toFixed(n >= 1e7 ? 0 : 1)}M` : n >= 1e4 ? `${Math.round(n / 1000)}K` : n >= 1000 ? `${+(n / 1000).toFixed(1)}K` : `${n}`);
// AN AGENT'S *EMPHASIS* IS EMPHASIS (Oct 27): only **bold** was read, so a line an agent wrote with single asterisks
// round a word printed the asterisks themselves, wherever agent text is shown (the manager's sheet, a session's
// messages). Single asterisks round a run that starts and ends on a letter are <em>, which composer.css already draws
// in italic; a lone asterisk (5 * 3, a glob, a bullet already read off its line) is left as written, and code keeps
// its own text.
const EMPH = /(^|[^*\w])\*(?![\s*])([^*\n]*?[^\s*])\*(?![*\w])/g;
// A LINK AN AGENT WROTE IS A LINK (Oct 27). Claude Code writes links in its answers constantly, and this read left
// "[the guide](https://…)" on the screen as written, brackets, address and all, on every page that shows an agent's
// words. An address on the web opens in a new tab under its own words; any other address (a file path, an anchor) names
// the file and keeps the whole path as its title, since the console cannot open his disk; a script, data or mail
// address keeps only its words. A bare web address is a link to itself. Code keeps its own text: a link inside
// backticks, or in a fenced block, which md never reads inline, stays as written.
const MD_LINK = /\[([^\]\n]+)\]\(([^)\s]+)(?:\s+"[^"\n]*")?\)/g;
const BARE_URL = /\bhttps?:\/\/[^\s\u0000<>()"'`\]]+[^\s\u0000<>()"'`.,;:!?\]]/g;
const linkName = (label, href) => {
  const name = /[\\/]/.test(label) ? label.split(/[\\/]/).filter(Boolean).pop() : label;
  return name || String(href).split(/[\\/]/).filter(Boolean).pop() || label;
};
function linkHtml(label, href) {
  if (/^https?:\/\//i.test(href)) return `<a class="mdlink" href="${esc(href)}" target="_blank" rel="noopener noreferrer">${esc(label)}</a>`;
  if (/^(?:mailto|javascript|data|vbscript):/i.test(href)) return esc(label);
  return `<span class="flink" title="${esc(href)}">${esc(linkName(label, href))}</span>`;
}
// EACH CODE SPAN AND EACH LINK IS TAKEN OUT BEFORE THE TEXT IS ESCAPED AND READ FOR EMPHASIS, and put back drawn, so bold or
// italic that runs across a code span (**the record `state` field**) is read as one run instead of leaving its asterisks on
// the screen, and what is in code or in a link's address is never read for emphasis.
const inline = (s) => {
  const held = [];
  const hold = (html) => `\u0000${held.push(html) - 1}\u0000`;
  const text = String(s ?? "").replace(/\u0000/g, "").replace(/`([^`]+)`/g, (all, code) => hold(`<code>${esc(code)}</code>`))
    .replace(MD_LINK, (all, label, href) => hold(linkHtml(label, href))).replace(BARE_URL, (url) => hold(linkHtml(url, url)));
  let html = esc(text).replace(/\*\*([^*\n]+)\*\*/g, "<b>$1</b>").replace(EMPH, "$1<em>$2</em>");
  // a link's words may hold a code span, so each put-back can bring another placeholder with it
  for (let i = 0; i <= held.length && /\u0000\d+\u0000/.test(html); i++) html = html.replace(/\u0000(\d+)\u0000/g, (all, n) => held[+n] ?? "");
  return html;
};
// THE BLOCK SHAPES AN AGENT ACTUALLY WRITES (Oct 26). This read dash lists and backticks only, so a Claude Code turn's
// table came out as a run of pipes, a heading as hash marks, a numbered list as paragraphs and a fenced block as one
// long line — the walls of explanation Marcos stopped the console for. Sessions carried its own copy of this; every
// page that shows what an agent wrote (its conversation, Teams' messages, Requests' bodies) now draws the same shapes.
const mdCells = (line) => line.trim().replace(/^\|/, "").replace(/\|$/, "").split("|").map((c) => c.trim());
const MD_RULE = /^\s*\|?[\s:|-]*-[\s:|-]*\|?\s*$/;
function mdTable(rows) {
  if (!rows.length) return "";
  const head = rows.length > 1 && MD_RULE.test(rows[1]) ? mdCells(rows[0]) : null;
  const body = (head ? rows.slice(2) : rows).map(mdCells).filter((r) => r.length);
  return `<table class="mdt">${head ? `<thead><tr>${head.map((c) => `<th>${inline(c)}</th>`).join("")}</tr></thead>` : ""}`
    + `<tbody>${body.map((r) => `<tr>${r.map((c) => `<td>${inline(c)}</td>`).join("")}</tr>`).join("")}</tbody></table>`;
}
function md(text) { // the agent's own text, as it wrote it: headings, lists, tables, fenced code, paragraphs
  const out = [], lines = String(text ?? "").split("\n");
  let list = null, ord = null, table = null, fence = null, lang = "";
  const closeAll = () => {
    if (list) { out.push(`<ul>${list.join("")}</ul>`); list = null; }
    if (ord) { out.push(`<ol>${ord.join("")}</ol>`); ord = null; }
    if (table) { out.push(mdTable(table)); table = null; }
  };
  for (const line of lines) {
    if (fence != null) {
      if (/^\s*```/.test(line)) { out.push(`<pre class="code"${lang ? ` data-lang="${esc(lang)}"` : ""}>${esc(fence.join("\n"))}</pre>`); fence = null; lang = ""; }
      else fence.push(line);
      continue;
    }
    if (/^\s*```/.test(line)) { closeAll(); fence = []; lang = line.replace(/^\s*```/, "").trim(); continue; }
    if (line.trim().startsWith("|")) { if (list || ord) closeAll(); (table ??= []).push(line); continue; }
    if (table) closeAll();
    const bullet = line.match(/^\s*[-*]\s+(.*)$/), number = line.match(/^\s*(\d+)[.)]\s+(.*)$/);
    if (bullet) { if (ord) closeAll(); (list ??= []).push(`<li>${inline(bullet[1])}</li>`); continue; }
    if (number) { if (list) closeAll(); (ord ??= []).push(`<li>${inline(number[2])}</li>`); continue; }
    closeAll();
    const head = line.match(/^(#{1,6})\s+(.*)$/);
    if (head) { out.push(`<p class="mh h${head[1].length}">${inline(head[2])}</p>`); continue; }
    if (line.trim()) out.push(`<p>${inline(line)}</p>`);
  }
  if (fence) out.push(`<pre class="code">${esc(fence.join("\n"))}</pre>`);
  closeAll();
  return out.join("");
}
const acctPlanHtml = (acct, plan) => `<span class="acct">${esc(acct)}${plan ? `<span class="pv">${esc(plan)}</span>` : ""}</span>`;

// ================================================================================================================
// DRAW: THE DRAWINGS EVERY PAGE SHARES (Oct 26)
//
// Marcos's rule for the console is "show don't tell". Until now the drawings that make the Usage drill-down and Accounts
// lived inside the two files that drew them: the hero figure, the stacked outcome bar, the ring and glyph tiles and
// the log axis were private to the Usage drill-down (perf-heads.js), and the meter, the reset ring and the card's
// acts were private to Accounts. Everywhere else a share, a load, a window, a time or an amount was printed as a
// word. This is the same drawings, lifted here unchanged, so any page can draw instead of tell. console.css holds
// their rules under "THE DRAWINGS EVERY PAGE SHARES"; the sizes are tokens there (--meter-h, --stack-h, --ring-sz,
// --ring-focal, --fig-hero, --fig-focal, --kicker, --u), measured on his own screen at 3822.
//
// THE SHAPE FITS THE QUANTITY: a share of a whole is a meter or a ring; a magnitude is a bar on its column's shared
// scale; a split of a whole is one stacked bar with the labels inside the segments; a time is a mark on one log
// axis; the one answer of a section is the hero figure with its denominator on the same baseline. A figure splice
// has no reading for is DRAW.dash, never a word in a figure's slot and never a zero.
// ================================================================================================================
const GO = G('<path d="M7 17L17 7M9 7h8v8"/>', 'class="go" aria-hidden="true"');
const CHEV = `<svg class="chev" viewBox="0 0 10 10" aria-hidden="true"><path d="M3 1.5 7 5 3 8.5"/></svg>`;
// the glyphs a count can carry, so a number has a shape the eye finds again (the Usage drill-down's CACHE AND ACTIVITY strip)
const GLYPH = {
  retry: `<svg viewBox="0 0 16 16" width="1em" height="1em" aria-hidden="true"><path d="M13.2 6.2A5.3 5.3 0 1 0 13.6 10"/><path d="M13.4 2.8v3.4H10"/></svg>`,
  cold: `<svg viewBox="0 0 16 16" width="1em" height="1em" aria-hidden="true"><path d="M8 1.5v13M2.3 4.75l11.4 6.5M13.7 4.75l-11.4 6.5"/></svg>`,
  key: `<svg viewBox="0 0 16 16" width="1em" height="1em" aria-hidden="true"><circle cx="5.2" cy="10.8" r="2.6"/><path d="M7.2 8.8l6.3-6.3M11.2 4.8l1.6 1.6M13.5 2.5l1.2 1.2"/></svg>`,
  peak: `<svg viewBox="0 0 16 16" width="1em" height="1em" aria-hidden="true"><path d="M3.5 14.5V1.5M3.5 2.3h8.5l-2 2.6 2 2.6H3.5"/></svg>`,
  gap: `<svg viewBox="0 0 16 16" width="1em" height="1em" aria-hidden="true"><path d="M1.5 8h4.5M10 8h4.5M7 8h2" stroke-dasharray=".5 1.5"/></svg>`,
  label: `<svg viewBox="0 0 16 16" width="1em" height="1em" aria-hidden="true"><path d="M2 3.5h8.4L14 8l-3.6 4.5H2z"/></svg>`,
};
const RING_R = 16, RING_C = 2 * Math.PI * RING_R, RESET_C = 2 * Math.PI * 15;
const BW = 40;                                        // rem, about how wide a panel bar is drawn
const labelRem = (text) => text.length * 0.42 + 0.8;  // rem a label takes at the panel's type size (an estimate)
const STEP = ["f0", "f1", "f2"];                      // failure blocks by rank: the largest is the full limit red
// the times on a log axis, from a round figure under the smallest typical to one over the largest slowest 5%
const NICE = [1, 3, 10, 30, 100, 300, 1e3, 3e3, 1e4, 3e4, 6e4, 18e4, 6e5];
// an axis label: a round figure, never a table's two-part reading (60 000 ms is "1 min", not "1 min 0 s"). Past ten minutes
// the axis ends on the longest time itself (axis below), which is not a round figure: whole minutes.
const tickWord = (ms) => (ms < 1000 ? `${ms} ms` : ms < 60000 ? `${ms / 1000} s` : `${Math.round(ms / 60000)} min`);

const DRAW = {
  // ---------- how a figure is written: one spelling per kind, everywhere ----------
  n: (v) => Math.round(v).toLocaleString("en-US"),
  /** A share as a percent. Under half a percent reads "<1%", so a rounded 0% never stands for something that happened. */
  pct: (x) => (x == null ? null : x > 0 && x < 0.005 ? "<1%" : `${Math.round(x * 100)}%`),
  /** A duration: milliseconds, then seconds, then minutes and seconds. */
  ms: (v) => (v < 1000 ? `${Math.round(v)} ms` : v < 60000 ? `${+(v / 1000).toFixed(v < 10000 ? 1 : 0)} s` : `${Math.floor(v / 60000)} min ${Math.round((v % 60000) / 1000)} s`),
  /** Tokens and other counts that run to billions, in ONE spelling: "53.9B", "77M", "920K" (kit kTok). The billions
   *  step was "15 bn", a space and two letters where the scale beside it has neither, so a page that says a token
   *  count five times (Usage) had to carry its own speller. */
  big: (v) => (v >= 999.5e6 ? `${+(v / 1e9).toFixed(v >= 9.995e9 ? 0 : 1)}B` : kTok(v)),
  /** A figure splice has no reading for. NEVER a word in a figure's slot, and never a zero. */
  dash: `<i class="nr" title="Not reported">—</i>`,
  /** What an agent wrote, as it wrote it: headings, lists, tables, fenced code and paragraphs (md above). */
  md,
  /** A TOOL'S NAME IN WORDS (Oct 26): tool names reach the screen as a client names them, so `mcp__ast-grep__find_code`
   *  stood on a session's row and "mcp__" was on the page. An MCP tool reads as its server, a middle dot and its own
   *  words; a joined name splits where a lowercase letter or digit meets a capital, and where a run of capitals meets
   *  a capital and a lowercase ("SendMessage" is "Send message", "WebFetch" is "Web fetch"); a one-word name is left
   *  as written (Bash, Read, Edit). It returns TEXT: the caller escapes it and keeps the client's name as the title. */
  toolWord(name) {
    const raw = String(name ?? "").trim();
    if (!raw) return "";
    const mcp = raw.match(/^mcp__(.+?)__(.+)$/);
    if (mcp) return `${mcp[1]} · ${mcp[2].replace(/_/g, " ")}`;
    const words = raw.replace(/_/g, " ").replace(/([a-z0-9])([A-Z])/g, "$1 $2").replace(/([A-Z]+)([A-Z][a-z])/g, "$1 $2").split(/\s+/).filter(Boolean);
    if (words.length < 2) return raw;
    return words.map((w, i) => (i ? w.toLowerCase() : w)).join(" ");
  },
  /** An object with nothing to report keeps its own shape and says so once, on itself, in one muted line. */
  nothing: (word) => `<span class="nothing">${esc(word)}</span>`,
  /** A dollar figure in a table's cell: the kit's money spelling (dollars) without its "≈". A column of estimates says
   *  so ONCE, in its header ("Cost, estimated"), never on every row; a figure splice has no price for is the dash.
   *  dollars() keeps the mark for a figure that stands alone, away from any header. */
  usd: (v) => (v == null || !Number.isFinite(+v) ? DRAW.dash : dollars(+v).replace(/^≈/, "")),

  // ---------- rhythm: a plate, its sections, its kicker ----------
  /** A section's ground: 4u of padding and a wash, no ink border and no cast shadow. A card is for OBJECTS only. */
  plate: (body, cls = "", id = "") => `<div class="plate${cls ? ` ${cls}` : ""}"${id ? ` id="${esc(id)}"` : ""}>${body}</div>`,
  /** One section of a plate: a two-word uppercase kicker 2u above its content, and 4u + a hairline + 4u from the one
   *  before it. [aside] rides the kicker's baseline, which is where a legend, a count or a window door belongs. */
  section: (kicker, body, { aside = "", cls = "", head = "" } = {}) =>
    `<section class="psec${cls ? ` ${cls}` : ""}"><div class="sechead${head ? ` ${head}` : ""}">${kicker ? `<h3 class="kicker">${esc(kicker)}</h3>` : ""}${aside}</div>${body}</section>`,
  /** A kicker over a row of objects that is not inside a plate: the unit, or what the row counts, said once. */
  kicker: (words) => `<p class="kicker on-wall">${esc(words)}</p>`,

  // ---------- the figures a section leads with ----------
  /** The one figure a section answers with, 2.9rem in the data face, coloured by level, with its denominator on the
   *  SAME baseline and the whole caption a door to exactly those items: "57%  1,635 failed of 2,887 requests ↗".
   *  [level] is "ok" (text ink), "watch" (the key light) or "bad" (the limit red).
   *
   *  [under] is the object the figure belongs to, on its own line under the figure — the login, the command, the
   *  window — and [tail] the state that goes with it, such as a DRAW.lock with its reset. A page that led with a
   *  figure and then had to say which thing it was about wrote that line itself (settings.css's .herocap). */
  hero({ figure, level = "ok", count = null, word = "", of = "", href = null, under = "", tail = "" }) {
    const inner = count == null ? `<span>${esc(of)}</span>` : `<b>${DRAW.n(count)}</b> ${esc(word)}<span>${esc(of)}</span>`;
    const cap = !of && count == null ? ""
      : href ? `<a class="pd-failed" href="${href}">${inner}${GO}</a>` : `<span class="pd-failed">${inner}</span>`;
    const line = under || tail ? `<p class="pd-under">${esc(under)}${tail}</p>` : "";
    return `<div class="pd-top"><b class="pd-rate lv-${level}">${esc(figure)}</b>${cap}</div>${line}`;
  },
  /** A figure that leads a part of a section, 1.7rem, with its words on its baseline: "587 ended, last 7 days". */
  focal: (figure, words) => `<div class="pd-sub"><b>${esc(figure)}</b>${esc(words)}</div>`,
  /** A count in a kicker line, as a door to exactly those items: "9 in 24 hours ↗". */
  countDoor: (count, words, href) => `<a class="pd-count" href="${href}"><b>${DRAW.n(count)}</b> ${esc(words)}${GO}</a>`,

  // ---------- a share of a limit or of a whole ----------
  /** THE METER (every window row on Accounts): 16.7 px tall at 3822, a sunk track with a rim, the fill in the provider's
   *  colour with an ink right edge, and at 100% the whole bar in the limit red with no edge. The figure beside it says
   *  "NN% used", so 6% never reads as 6% left. [cls]: "sub" a slice inside one, "full" at the limit, "stale" read
   *  before its window moved, "gone" a window whose reset has passed, "reading" still being read.
   *  Call DRAW.paint once after the markup lands to set the widths and grow them.
   *  [used] null is a window splice has not read: the empty track, rimmed in a dashed line and holding nothing (a 0%
   *  fill would read as nothing used), with DRAW.dash where its figure goes. */
  meter: (used, cls = "") => (used == null ? `<div class="bar unread${cls ? ` ${cls}` : ""}"></div>`
    : `<div class="bar ${cls}"><b data-v="${Math.max(0, Math.min(100, used))}"></b></div>`),
  /** Every meter under [root] takes its width. [grow] is "all" or a Set of ids: those grow from nothing over 240 ms,
   *  and the rest are simply drawn. [reading] stripes the tracks while splice is asking the provider again, so the
   *  last figure stays on screen. A meter's id is the closest [data-a], [data-meter] or [data-grow]. */
  paint(root, { grow = null, reading = false } = {}) {
    const move = !window.matchMedia("(prefers-reduced-motion: reduce)").matches;
    for (const b of root.querySelectorAll(".bar > b[data-v]")) {
      const host = b.closest("[data-a], [data-meter], [data-grow]");
      const id = host && (host.dataset.a || host.dataset.meter || host.dataset.grow);
      const growing = move && grow && id && (grow === "all" || (typeof grow.has === "function" && grow.has(id)));
      b.style.width = `${b.dataset.v}%`;
      if (reading) b.parentElement.classList.add("reading");
      else if (growing) {
        b.style.transform = "scaleX(0)";
        b.parentElement.classList.add("grow");
        requestAnimationFrame(() => requestAnimationFrame(() => (b.style.transform = "scaleX(1)")));
      }
    }
  },
  /** THE RING: a share drawn as an arc from 12 o'clock. "reset" is Accounts' 24.3 px ring beside a meter (how soon the
   *  window refills, in the provider's colour); "focal" is the Usage drill-down's 50 px ring, the figure a section leads with. */
  ring(frac, { size = "reset", label = null } = {}) {
    const f = Math.max(0, Math.min(1, frac || 0));
    if (size === "focal") {
      return `<svg class="pd-ring" viewBox="0 0 44 44" ${label ? `role="img" aria-label="${esc(label)}"` : `aria-hidden="true"`}><circle class="rt" cx="22" cy="22" r="${RING_R}"/>`
        + `<circle class="rv" cx="22" cy="22" r="${RING_R}" transform="rotate(-90 22 22)" stroke-dasharray="${(RING_C * f).toFixed(2)} ${RING_C.toFixed(2)}"/></svg>`;
    }
    return `<svg class="ring" viewBox="0 0 40 40" aria-hidden="true"><circle class="trk" cx="20" cy="20" r="15"/><circle class="arc" cx="20" cy="20" r="15" stroke-dasharray="${(RESET_C * f).toFixed(1)} ${RESET_C.toFixed(1)}"/></svg>`;
  },

  // ---------- one whole, split: the stacked bar ----------
  /** THE STACKED BAR (the Usage drill-down's OUTCOMES): one proportional bar over [total] from [{n, word, cls, href}], in order. A
   *  block is as wide as its count says (a tiny one keeps a hairline) and is a link when it has an href. A block wide
   *  enough CARRIES ITS OWN WORD AND COUNT INSIDE IT, so there is no key to look up; the rest are named in one tidy
   *  row under the bar, the largest first. Blocks marked "nt" (neither one outcome nor the other) always go to that
   *  row; two or more of them are led by their own total, so the parts add up to the bar.
   *  [cls] per segment: "ok" the clean part, "nt" the quiet part, f0/f1/f2 failure kinds in the limit red by rank,
   *  "rest" the rest of a whole that is not an outcome (the servers not shared), quiet but named by its own word.
   *
   *  A WHOLE THAT IS NOT A SET OF OUTCOMES (Oct 26). Three pages carried a copy of this bar because the kit wrote
   *  every count with DRAW.n, coloured only by outcome, and gathered every quiet part under one sentence:
   *   - [text] per segment is the figure as the caller words it ("24.7 GB", "15"); without it, DRAW.n(s.n).
   *   - [ink] per segment is the colour the caller already knows (a member's command, a store's hue), written as --c.
   *   - ONE quiet block is a plain legend item with the neutral swatch ("Stopped 6") after the failure kinds, where
   *     it read "Neither answered nor failed 6: Stopped 6" — the same count twice behind a sentence about a class.
   *   - { restWord } is the caller's own word for the lead over two or more quiet blocks.
   *  { small } draws the shorter 1.4rem bar, for a second whole under a hero bar. { grow } grows it in over 240 ms.
   *  { label } reads the whole bar aloud; { tail } rides the end of the legend row.
   *
   *  WHICH LABELS FIT INSIDE IS MEASURED ON THE BAR AS DRAWN (Oct 27, fitStack below). The words and counts are first
   *  placed by a guess from a bar { bw } rem wide (40 by default), and once the bar is laid out each part keeps its label
   *  only where the label fits, and a part the guess left outside takes its label in where it fits. The guess assumed
   *  a 606 px bar everywhere, so on a wider bar a 273 px part lost its label to the row below. */
  stack(segs, total, { small = false, grow = false, restWord = "Neither answered nor failed", label = null, tail = "", bw = BW } = {}) {
    const blocks = [], legend = [];
    // every part that can carry its label is paired with its item in the row by its rank, largest first, which is the
    // row's own order, so fitStack can move a label between the two and keep the row in order
    const ranked = segs.filter((s) => s.n > 0 && total > 0 && s.cls !== "nt").sort((a, b) => b.n - a.n);
    const pair = (s) => (ranked.includes(s) ? ` data-seg="${ranked.indexOf(s)}"` : "");
    const figure = (s) => (s.text == null ? DRAW.n(s.n) : esc(s.text));
    const classOf = (s) => `pd-seg${s.cls ? ` ${s.cls}` : ""}${s.ink ? " ink" : ""}`;
    for (const s of segs) {
      if (!(s.n > 0) || !(total > 0)) continue;
      const w = (s.n / total) * 100, text = `${s.word} ${s.text == null ? DRAW.n(s.n) : s.text}`;
      const quiet = s.cls === "nt";
      const inside = !quiet && w >= (labelRem(text) / (0.9 * bw)) * 100;
      const tag = s.href ? "a" : "span", href = s.href ? ` href="${s.href}"` : "";
      const style = `${s.ink ? `--c:${s.ink};` : ""}width:max(${w.toFixed(3)}%, .16rem)`;
      blocks.push(`<${tag} class="${classOf(s)}"${href}${pair(s)} style="${style}" title="${esc(text)}">`
        + `${inside ? `<span>${esc(s.word)} <b>${figure(s)}</b></span>` : ""}</${tag}>`);
      if (!inside) legend.push(s);
    }
    const item = (s, bare) => {
      const sw = `<i class="pd-sw${s.cls ? ` ${s.cls}` : ""}${s.ink ? " ink" : ""}"${s.ink ? ` style="--c:${s.ink}"` : ""}></i>`;
      const inner = `${bare ? "" : sw}${esc(s.word)} <b>${figure(s)}</b>`;
      return s.href ? `<a class="pd-lb"${pair(s)} href="${s.href}">${inner}</a>` : `<span class="pd-lb"${pair(s)}>${inner}</span>`;
    };
    const quiet = legend.filter((s) => s.cls === "nt"), rest = legend.filter((s) => s.cls !== "nt").sort((a, b) => b.n - a.n);
    // one quiet block says what it is, as every other item does; two or more are led by what they have in common
    const neither = quiet.length === 1 ? item(quiet[0])
      : quiet.length ? `<div class="pd-lgrp"><span class="pd-neither"><i class="pd-sw nt"></i>${esc(restWord)} <b>${DRAW.n(quiet.reduce((a, s) => a + s.n, 0))}</b>:</span> ${quiet.map((s) => item(s, true)).join(`<span class="pd-comma">, </span>`)}</div>` : "";
    const bar = `<div class="pd-bar${grow ? " grow" : ""}"${label ? ` role="img" aria-label="${esc(label)}"` : ""}>${blocks.join("")}</div>`;
    return `<div class="pd-pop${small ? " sm" : ""}">${bar}`
      + `${legend.length || tail ? `<div class="pd-legend">${rest.map((s) => item(s)).join("")}${neither}${tail}</div>` : ""}</div>`;
  },

  // ---------- a magnitude in a table column ----------
  /** A bar on its column's shared scale with its figure after it, so every row's figure ends on ONE right edge and the
   *  biggest and the worst are found before a digit is read. [w] is this row's percent of the column's largest.
   *  [label] is the column's own word, drawn only where the table is too narrow for its header. */
  vbar: (w) => `<span class="vbar" aria-hidden="true"><b style="width:${Math.round(Math.max(0, Math.min(100, w)) * 10) / 10}%"></b></span>`,
  /** [role] "cell" when the bar cell is a cell of a role="table" (the Usage Results rows). */
  barCell: ({ w, text, label = "", cls = "", level = "", role = "" }) =>
    `<span class="barcell${cls ? ` ${cls}` : ""}${level ? ` lv-${level}` : ""}"${role ? ` role="${role}"` : ""}>${label ? `<em>${esc(label)}</em>` : ""}${DRAW.vbar(w)}<b class="v">${text}</b></span>`,

  // ---------- times: range bars on ONE log axis ----------
  /** The axis every range bar on a page shares, from a round figure under the smallest typical to one over the largest
   *  slowest 5%. Returns { lo, hi, ticks, at(ms) }. A log axis lets 272 ms and 75 s read as lengths on one line. */
  axis(typicals, slowests) {
    const low = Math.min(...typicals.map((x) => Math.max(1, x))), high = Math.max(...slowests);
    const lo = [...NICE].reverse().find((v) => v <= low) ?? 1, hi = NICE.find((v) => v >= high) ?? high;
    const span = Math.log(hi) - Math.log(lo) || 1;
    const at = (x) => Math.max(0, Math.min(100, ((Math.log(Math.max(x, lo)) - Math.log(lo)) / span) * 100));
    // no more than six labels along the axis, always the last one, and none within 7% of another, so no two touch
    const all = NICE.filter((v) => v >= lo && v <= hi);
    // A LONGEST TIME PAST THE LAST ROUND FIGURE IS ITS OWN END: the axis runs to it, so it closes the list and its label
    // stands at the track's end. Left out, the last label was "10 min" standing half its width left of a tick at 77% of a
    // track that ran to 35 min. Skipped when it reads the same as the figure before it.
    if (all.at(-1) !== hi && tickWord(hi) !== tickWord(all.at(-1) ?? 0)) all.push(hi);
    const step = Math.ceil(all.length / 6);
    const picked = all.filter((v, i) => (all.length - 1 - i) % step === 0);
    const wide = (v) => tickWord(v).length * 2.4, GAP = 7;
    const kept = [];
    picked.forEach((v, i) => {
      const first = i === 0, last = i === picked.length - 1 && picked.length > 1;
      if (first) { kept.push({ v, l: 0, r: wide(v) }); return; }
      if (last) { const l = 100 - wide(v); while (kept.length > 1 && l - kept.at(-1).r < GAP) kept.pop(); kept.push({ v, l, r: 100 }); return; }
      const l = at(v) - wide(v) / 2, r = at(v) + wide(v) / 2;
      if (l - kept.at(-1).r >= GAP) kept.push({ v, l, r });
    });
    return { lo, hi, ticks: kept.map((k) => k.v), at };
  },
  /** One range on the shared axis: a solid band to the typical time, a thin line on to the slowest 5%, ending in a tick. */
  range: (ax, typical, slowest) => `<span class="pd-trk"><i class="hi" style="width:${ax.at(slowest).toFixed(2)}%"></i><i class="mid" style="width:${ax.at(typical).toFixed(2)}%"></i></span>`,
  /** The two marks named once, on the section's kicker line, so no row repeats them. */
  rangeLegend: `<span class="pd-leg"><i class="pd-lt"></i>Typical<i class="pd-ls"></i>Slowest 5%</span>`,
  /** The axis's own line, its ticks and what it measures, said once under the rows. */
  axisRow: (ax, word = "Time, log scale", tail = "") =>
    `<div class="pd-axis pd-grid"><span class="pd-sl"><small>${esc(word)}</small></span><span class="pd-ax">`
    + `${ax.ticks.map((v, i) => `<span class="pd-tk${i === 0 ? " pd-t-first" : i === ax.ticks.length - 1 ? " pd-t-last" : ""}" style="left:${ax.at(v).toFixed(2)}%">${tickWord(v)}</span>`).join("")}`
    + `</span>${tail}</div>`,
  tickWord,

  // ---------- stat and glyph tiles: ONLY above zero ----------
  /** One tile: a ring or a glyph, its figure and its name on one baseline. A tile is drawn only when its count is above
   *  zero — a strip of tiles lists what happened, never a row of noughts. */
  statTile: (icon, number, label, { title = null, focal = false } = {}) =>
    `<span class="pd-stat${focal ? " focal" : ""}"${title ? ` title="${esc(title)}"` : ""}>${icon}<span><b>${number}</b><small>${esc(label)}</small></span></span>`,
  /** The strip: [{icon, n, label, title, focal}], the ones above zero, in the order the section reads them. */
  tiles(items) {
    // a string is a tile its caller has already judged; an object with a numeric count is drawn only above zero
    const drawn = items.filter((t) => t && (typeof t === "string" || typeof t.n !== "number" || t.n > 0));
    return drawn.length ? `<div class="pd-stats">${drawn.map((t) => (typeof t === "string" ? t : DRAW.statTile(t.icon, t.n, t.label, t))).join("")}</div>` : "";
  },
  glyph: GLYPH,

  // ---------- small multiples: one card per thing, every card on the same scales ----------
  /** A card that holds one thing's figures on the scales its siblings use, so the cards compare by eye. Only the worst
   *  is loud, and only when it is worth a look: one inset ring in the limit red and its rate at 1.7rem. */
  multiCard: ({ name, level = "ok", loud = false, body }) =>
    `<div class="pd-card lv-${level}${loud ? " loud" : ""}"><span class="pd-cn">${esc(name)}</span>${body}</div>`,
  /** A labelled figure with its own mini bar inside a small-multiple card. [mark] is a percent: the line the bar is
   *  read against (a warn line), as DRAW.meter carries a limit. [cls] "row" lays the three out as a row for a narrow
   *  card (console.css .pd-q.row); a "bad" level reddens the fill as well as the figure. */
  miniPair: ({ label, figure, w, cls = "", level = "", mark = null, markTitle = "" }) => {
    const parts = String(cls).split(" ").filter(Boolean);
    const row = parts.includes("row") ? " row" : "", mini = parts.filter((c) => c !== "row").join(" ");
    const pin = mark == null ? "" : `<i class="pd-mk" style="left:${Math.max(0, Math.min(100, mark)).toFixed(2)}%"></i>`;
    return `<div class="pd-q${row}"><span class="k">${esc(label)}</span><b${level ? ` class="lv-${level}"` : ""}>${figure}</b>`
      + `<span class="pd-mini${mini ? ` ${mini}` : ""}${level ? ` lv-${level}` : ""}"${markTitle ? ` title="${esc(markTitle)}"` : ""}>`
      + `<i style="width:${Math.max(0, Math.min(100, w)).toFixed(2)}%"></i>${pin}</span></div>`;
  },

  // ---------- the objects ----------
  /** A command as an object: the mono face says "a command", the edge colour says which provider, and the 10.6 px dot
   *  says it is live now. Three facts, no words. Set --c on the chip or on a parent. */
  chip: ({ text, live = false, href = null, cls = "", title = null, data = "" }) => {
    const inner = `${live ? `<i aria-hidden="true"></i>` : ""}${esc(text)}`;
    const attrs = `class="chip${cls ? ` ${cls}` : ""}"${title ? ` title="${esc(title)}"` : ""}${data}`;
    return href ? `<a ${attrs} href="${href}">${inner}</a>` : `<span ${attrs}>${inner}</span>`;
  },
  /** A segmented switch: every view visible as one set with ONE lit, in neutral ink (the selected look, console.css
   *  :root --sel-*). Never the key light. [items] is [[value, word]]; { data } rides every button (Accounts writes the
   *  provider and command the switch orders), and { disabled } holds the whole switch while what it sets is saved. */
  switcher: (items, now, act, label, { data = "", disabled = false } = {}) =>
    `<div class="switch" role="group" aria-label="${esc(label)}">`
    + items.map(([v, word]) => `<button type="button" data-act="${esc(act)}"${data} data-v="${esc(v)}" aria-pressed="${now === v}"${disabled ? " disabled" : ""}>${esc(word)}</button>`).join("")
    + `</div>`,
  /** The dashed tile where the new item will land, so the act explains itself with no "Click to add" sentence. On a
   *  rail it carries the empty socket, so it stands in the order as the next number. While it works it turns solid and
   *  holds its own form; a refused add shakes once. */
  addTile: ({ word, act, data = "", socket = false, cls = "", pressed = null, body = null }) =>
    `<button class="add${cls ? ` ${cls}` : ""}" type="button" data-act="${esc(act)}"${data}${pressed == null ? "" : ` aria-pressed="${pressed}"`}>`
    + `${socket ? `<span class="socket" aria-hidden="true"></span>` : ""}<span class="plus" aria-hidden="true">+</span>${body ?? `<span>${esc(word)}</span>`}</button>`,
  /** The add tile turned into its form, in the same place and at the same size: the rim goes solid in the group's
   *  colour and [body], the fields and the acts, stands where the word was. { failed } rims it in the limit red and
   *  shakes it once. A page adds "enter" to { cls } on the draw that opened it, so it rises in once (Accounts' signing
   *  and naming tiles). */
  addForm: ({ body, data = "", socket = false, failed = false, cls = "" }) =>
    `<div class="add signing${cls ? ` ${cls}` : ""}${failed ? " failed" : ""}"${data}>${socket ? `<span class="socket" aria-hidden="true"></span>` : ""}${body}</div>`,

  // ---------- the wall: titled groups, packed into columns ----------
  /** A TITLED GROUP (Accounts' provider, for every page's board of groups): its dot in [c], its name in the serif at
   *  1.4rem, [end] at the header's end (the first thing after the name takes the end and the rest follow it: a switch,
   *  a count, an act on the whole group), and [body], its objects, 1.1rem under the header. { cls } "provider" is
   *  Accounts' own; every other page keeps "titled". { key } is the data-key a page keeps focus and motion by. */
  group: ({ name, body, c = null, end = "", key = "", cls = "titled", style = "" }) => {
    const st = [c ? `--c:${c}` : "", style].filter(Boolean).join(";");
    return `<section class="${cls}"${st ? ` style="${st}"` : ""}${key ? ` data-key="${esc(key)}"` : ""}><header><h2><span class="blot"></span>${esc(name)}</h2>${end}</header>${body}</section>`;
  },
  /** Objects down one column, 15 px apart (.stack-cards), and titled groups down one column, 36 px apart (.groups). */
  cards: (items) => `<div class="stack-cards">${items.join("")}</div>`,
  groups: (items) => `<div class="groups">${items.join("")}</div>`,
  /** THE COLUMN SYSTEM: [items] in as many equal columns as the width holds, each at least [min] rem, so the row is
   *  always full (.cols); an item with the class "wide2", "wide3" or "full" takes more of the row. */
  cols: (items, min = 26, cls = "") => `<div class="cols${cls ? ` ${cls}` : ""}" style="--col-min:${min}rem">${items.join("")}</div>`,
  /** Groups of different heights flowed down columns at least [min] rem wide, the order running down each (.flow-cols). */
  flow: (items, min = 26) => `<div class="flow-cols" style="--col-min:${min}rem">${items.join("")}</div>`,
  /** How many columns [rem] wide [el]'s width holds, with its own column gap between them: a board's rule at every
   *  width (Accounts: 40rem a column, five at 3822 and two at 1440). */
  colsFit(el, rem) {
    const gap = parseFloat(getComputedStyle(el).columnGap) || 0, px = parseFloat(getComputedStyle(document.documentElement).fontSize);
    return Math.max(1, Math.floor((el.clientWidth + gap) / (rem * px + gap)));
  },
  /** [items] packed down [n] columns, each into the shortest column so far by [weight], so no column stands as a tall
   *  wall beside empty ones. Returns [{ w, items }] in column order (Accounts' providers). */
  packCols(items, n, weight) {
    const cols = Array.from({ length: n }, () => ({ w: 0, items: [] }));
    for (const it of items) { const c = cols.reduce((m, x) => (x.w < m.w ? x : m)); c.items.push(it); c.w += weight(it); }
    return cols;
  },
  /** A board's columns as drawn (console.css .board): [{ body, span }] in order; a column of span k is as wide as k
   *  columns and the k - 1 gaps between them, so its own columns line up with the board's. */
  boardCols: (cols) => cols.map(({ body, span = 1 }) => `<div class="col${span > 1 ? " wide" : ""}"${span > 1 ? ` style="--span:${span}"` : ""}>${body}</div>`).join(""),
  /** A list's tail: the top few at full size, the rest behind one pill. Never a column that runs past the fold.
   *  { one } is the word for a count of one ("1 more line"), so no caller has to spell the singular itself; without
   *  it the one word serves every count. */
  more: (n, { word = "more", one = null, act = "", data = "", pill = false, open = false, less = "Fewer" } = {}) =>
    `<button class="${pill ? "morepill pill" : "morepill"}" type="button" data-act="${esc(act)}"${data}>${open ? esc(less) : `${pill ? `<b>+${DRAW.n(n)}</b>` : `<b>${DRAW.n(n)}</b> ${esc(n === 1 && one != null ? one : word)}`}`}</button>`,
  /** A row that opens its own detail under itself: the chevron turns over 240 ms and the detail lands in place, so the
   *  row keeps its context and the panel needs no title. */
  disc: ({ open, controls, inner, cls = "", act = "", data = "" }) =>
    `<button class="disc${cls ? ` ${cls}` : ""}" type="button" aria-expanded="${Boolean(open)}"${controls ? ` aria-controls="${esc(controls)}"` : ""}${act ? ` data-act="${esc(act)}"` : ""}${data}>${CHEV}${inner}</button>`,
  chev: CHEV,
  /** The lock that takes the place of the act that no longer works, and pops in over 260 ms when it arrives. */
  lock: (word = "Limit reached", { pop = false } = {}) =>
    `<span class="state limit"><span class="lockico${pop ? " lock-pop" : ""}" aria-hidden="true">${ICON.lock}</span>${esc(word)}</span>`,
  /** An act, on the object it changes. While it works it spins where it stands; its result lands beside it in plain
   *  words, and a refusal shakes it once (add .shook for one turn). */
  act: ({ word, act, cls = "", busy = false, data = "", disabled = false }) =>
    `<button class="act${cls ? ` ${cls}` : ""}" type="button" data-act="${esc(act)}"${data}${busy ? ` aria-busy="true"` : ""}${disabled ? " disabled" : ""}>${esc(word)}</button>`,
  /** What an act did, in place and in plain words: nothing to hunt for in a console or a banner. */
  result: (word, tone = "") => `<span class="result${tone ? ` ${tone}` : ""}" role="status">${esc(word)}</span>`,
  /** A lamp: a dark socket with an ink ring, lit in the state's own colour. "working" draws the live trace, "needs"
   *  the Needs-you ring, "stalled" the flat line in the limit red, and anything else an unlit dot. */
  lamp: (kind) => `<span class="tlamp${kind ? ` ${kind}` : ""}" aria-hidden="true">${TLAMP[kind] ?? "<i></i>"}</span>`,
  /** A press that cuts or moves someone asks ONCE, in place: what it does to them, each one a chip that opens its
   *  session, then the act and the way out. The second press acts. (kit armedAsk) */
  armed: (said, cut, act, keep, tail = "") => armedAsk(said, cut, act, keep, tail),
  go: GO,
};

// ---------- the stacked bar's labels, fitted to the bar as it is drawn (Oct 27) ----------
// DRAW.stack places each part's word and count from a guess at the bar's width. Once the bar is laid out, a part keeps
// its label inside only where the label fits, a part named in the row under the bar takes its label in where it fits,
// and the row keeps its order, largest first, ahead of the quiet parts and the caller's tail. Fitted when a bar lands,
// again when the window's width changes, and once more when the fonts arrive, since a label's width is its font's.
const FITTED = new WeakMap();
let fitRound = 0;
function fitStack(pop) {
  const bar = pop.querySelector(":scope > .pd-bar"), width = bar?.clientWidth;
  if (!width || FITTED.get(bar) === `${fitRound}:${width}`) return;
  FITTED.set(bar, `${fitRound}:${width}`);
  let legend = pop.querySelector(":scope > .pd-legend");
  const itemOf = (seg) => legend?.querySelector(`:scope > .pd-lb[data-seg="${seg.dataset.seg}"]`) ?? null;
  const parts = [...bar.querySelectorAll(":scope > .pd-seg[data-seg]")], tried = new Set();
  for (const seg of parts) { // a part named in the row tries its label inside
    const it = seg.firstElementChild ? null : itemOf(seg);
    if (!it) continue;
    const span = document.createElement("span");
    span.append(...[...it.childNodes].filter((n) => !n.classList?.contains("pd-sw")).map((n) => n.cloneNode(true)));
    seg.append(span);
    tried.add(seg);
  }
  const over = new Set(parts.filter((seg) => seg.firstElementChild && seg.firstElementChild.offsetWidth > seg.clientWidth));
  for (const seg of parts) {
    const span = seg.firstElementChild;
    if (!span) continue;
    if (!over.has(seg)) { if (tried.has(seg)) itemOf(seg)?.remove(); continue; }
    if (!tried.has(seg)) { // a label that does not fit goes to the row, behind its part's swatch
      const it = document.createElement(seg.tagName === "A" ? "a" : "span");
      it.className = "pd-lb";
      it.dataset.seg = seg.dataset.seg;
      if (seg.tagName === "A") it.setAttribute("href", seg.getAttribute("href"));
      const sw = document.createElement("i");
      sw.className = [...seg.classList].map((c) => (c === "pd-seg" ? "pd-sw" : c)).join(" ");
      if (seg.style.getPropertyValue("--c")) sw.style.setProperty("--c", seg.style.getPropertyValue("--c"));
      it.append(sw, ...span.childNodes);
      if (!legend) { legend = document.createElement("div"); legend.className = "pd-legend"; pop.append(legend); }
      legend.append(it);
    }
    span.remove();
  }
  if (!legend) return;
  const named = [...legend.querySelectorAll(":scope > .pd-lb[data-seg]")].sort((a, b) => a.dataset.seg - b.dataset.seg);
  const after = [...legend.childNodes].find((n) => !named.includes(n)) ?? null;
  for (const it of named) legend.insertBefore(it, after);
  if (!legend.childNodes.length) legend.remove();
}
const fitStacks = (root = document) => { for (const pop of root.querySelectorAll(".pd-pop")) fitStack(pop); };
new MutationObserver((records) => {
  for (const r of records) for (const n of r.addedNodes) {
    if (n.nodeType !== 1) continue;
    if (n.matches(".pd-pop")) fitStack(n); else if (n.firstElementChild) fitStacks(n);
  }
}).observe(document.documentElement, { childList: true, subtree: true });
addEventListener("resize", () => fitStacks());
document.fonts?.ready.then(() => { fitRound++; fitStacks(); });

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
  // THE CONNECT PHASE IS HEALTHY, SO IT IS NOT RED (the colour pass, Oct 26). "No output yet" is the ordinary moment
  // between a request going out and the first byte coming back; drawn in the limit red it read as a failure on 3 of 15
  // working sessions. The limit red marks a limit or a failure and nothing else, so this line is muted ink (.soft).
  return { cls: "working", lamp: ICON.trace, word, detail: t.phase === "connect" ? `<span class="detail soft">No output yet</span>` : "" };
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

// list and open item sit side by side exactly where the page's .page container query puts them: the page's content
// box, padding out, against the width its stylesheet writes beside that query as .page { --side-by-side: <rem> }, 104
// where it writes none. So a page never opens an item the CSS can only show in place of its list, and each page keeps
// its one width in its own stylesheet (the scale pass of Oct 15 moved Requests to 120 and Models to 90).
const sideBySide = () => {
  const p = document.querySelector(".page"), cs = getComputedStyle(p);
  const at = parseFloat(cs.getPropertyValue("--side-by-side")) || 104;
  return (p.clientWidth - parseFloat(cs.paddingLeft) - parseFloat(cs.paddingRight)) / parseFloat(getComputedStyle(document.documentElement).fontSize) >= at;
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
// THE FAVICON FOLLOWS THE THEME: the kit’s tile in the night inks, or the same tile in the day inks (brand/favicon-day.svg).
function setFavicon() {
  const link = document.querySelector('link[rel="icon"][type="image/svg+xml"]');
  if (link) link.href = document.documentElement.dataset.theme === "day" ? "brand/favicon-day.svg" : "brand/favicon.svg";
}
setFavicon();
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
/** THE TOGGLE SHOWS BOTH THEMES WITH THE ONE IN USE LIT (Oct 26), as every other two-state control on the console does:
 *  the sun and the moon side by side, the current one in full ink and the other muted, and the label says what the
 *  press gives ("Switch to day"). It drew one moon in both themes with the label "Day or night", so it said neither
 *  which theme was on nor what a press would do. The page's own markup holds the button; the kit draws what is in it,
 *  so every page's toggle reads the same and the press keeps its one binding. */
function drawTheme() {
  const button = document.querySelector('.side [data-act="theme"]');
  if (!button) return;
  const day = document.documentElement.dataset.theme === "day";
  button.classList.add("thm");
  button.setAttribute("aria-label", day ? "Switch to night" : "Switch to day");
  button.innerHTML = `${ICON.sun}${ICON.moon}`;
}
function toggleTheme() {
  const h = document.documentElement;
  h.dataset.theme = h.dataset.theme === "day" ? "night" : "day";
  try { localStorage.setItem(THEME_KEY, h.dataset.theme); } catch { /* storage off: this page still switches */ }
  setFavicon();
  drawTheme();
}
// WITH ONE PAGE THE NAV DRAWS NOTHING (hitstop, Oct 10): a lone link repeats the page's own title, and a single
// selected box reads as a strip with its other tabs torn off. The top bar keeps its height, its wordmark and its
// theme toggle either way, so nothing moves when the links arrive.
function drawNav() {
  const side = document.querySelector(".side");
  if (!side || PAGES.length < 2 || side.querySelector(".nav")) return;
  // the page names itself by its title, so any address it was opened at (a query, a bare "/", no ".html") marks its own
  // item, and a page no item names marks none rather than Accounts (splice-lead, Oct 12, from builder2's retake)
  const own = document.title.split(" · ")[0], file = location.pathname.split("/").pop();
  const here = PAGES.find(([f, name]) => name === own || f === file)?.[0];
  const links = PAGES.map(([file, name]) =>
    `<a href="${file}"${file === here ? ' aria-current="page"' : ""}><span class="nname">${esc(name)}</span></a>`).join("");
  const nav = document.createElement("nav");
  nav.className = "nav";
  nav.setAttribute("aria-label", "Pages");
  // ONE LINE UNDER THE PAGE HE IS ON, AND IT TRAVELS (Oct 27): each link drew its own line and faded it in place, so a
  // page change blinked one line out and another in. The line is one object now, moved to the new link's place.
  nav.innerHTML = `${links}<span class="nav-line" aria-hidden="true"></span>`;
  side.insertBefore(nav, side.querySelector(".foot"));
  navLine(false);
  new ResizeObserver(() => navLine(false)).observe(nav);
}
/** The current-page line under the link marked current: moved there over --t-move, or put there at once. */
function navLine(travel = true) {
  const nav = document.querySelector(".side .nav"), line = nav?.querySelector(".nav-line");
  if (!line) return;
  const on = nav.querySelector('a[aria-current="page"]');
  line.classList.toggle("off", !on);
  if (!on) return;
  line.classList.toggle("still", !travel);
  line.style.width = `${on.offsetWidth}px`;
  line.style.transform = `translateX(${on.offsetLeft}px)`;
  if (!travel) requestAnimationFrame(() => line.classList.remove("still"));
}
/** Marks [file]'s link as the page he is on, and moves the line to it (the router, console.js select). */
function navCurrent(file) {
  for (const link of document.querySelectorAll(".side .nav a")) {
    if (link.getAttribute("href") === file) link.setAttribute("aria-current", "page");
    else link.removeAttribute("aria-current");
  }
  navLine(true);
}
drawNav();

// ---------- what waits behind a link ----------
// THE LINKS ARE THE MAP, SO A LINK CARRIES WHAT WAITS BEHIND IT (Oct 27). The bar named eight pages and nothing on
// them, so a session waiting on him was one page away and invisible from the other seven. Sessions carries the
// project tiles' own lamps with their counts: who needs him, and what stalled. It is the same reading every page's
// tiles take (projectGroups over the sessions and the commands' live turns), so the bar and the tiles never disagree.
// Nothing waiting draws nothing, since a bare link is the steady state; a read that fails leaves the last marks.
// The read is the page's own when it just made one (API.recent, splice:heard), else the bar's, once a minute while the
// tab is in view. Working sessions are not marked: working is the normal state, not something waiting.
const NAV_READ_MS = 60_000;
const NAV_MARKS = ["needs", "stalled"];
let navReading = false, navAgain = false;
async function markNav() {
  if (navReading) { navAgain = true; return; }
  if (document.hidden || document.body.classList.contains("locked") || !document.querySelector(".side .nav")) return;
  navReading = true;
  try {
    const [sessions, list] = await Promise.all([API.recent("/api/sessions", NAV_READ_MS / 2), API.recent("/api/heads", NAV_READ_MS / 2)]);
    if (!sessions.ok || !list.ok) return;
    const heads = (list.body?.heads || []).filter((h) => h.key).map((h) => ({ key: h.key, reanchorMs: h.gate?.stall_reanchor_ms ?? null,
      idleMs: h.gate?.stream_idle_ms ?? null, firstByteMs: h.gate?.first_byte_timeout_ms ?? null, retired: h.retired }));
    const running = heads.filter((h) => !h.retired);
    const lives = await Promise.all(running.map((h) => API.recent(`/api/heads/${encodeURIComponent(h.key)}/turns/live`, NAV_READ_MS / 2)));
    const turns = {};
    running.forEach((h, i) => { for (const t of lives[i].body?.turns || []) if (t.session && !t.stopped) turns[t.session] = { head: h.key, ...t }; });
    const every = sumCounts(projectGroups(sessions.body?.sessions || [], turns, heads).map((g) => g.counts));
    drawNavMarks("sessions.html", "Sessions", every.filter(([k, n]) => NAV_MARKS.includes(k) && n > 0));
  } finally {
    navReading = false;
    if (navAgain) { navAgain = false; markNav(); }
  }
}
function drawNavMarks(file, name, counts) {
  const link = document.querySelector(`.side .nav a[href="${file}"]`);
  if (!link) return;
  let marks = link.querySelector(".nmarks");
  const html = counts.map((c) => tlampHtml(c)).join("");
  if ((marks?.innerHTML ?? "") === html) return;
  if (!html) { marks?.remove(); link.removeAttribute("aria-label"); navLine(false); return; }
  const fresh = !marks;
  if (!marks) { marks = document.createElement("span"); marks.className = "nmarks"; link.append(marks); }
  marks.innerHTML = html;
  if (fresh) marks.classList.add("pop");
  // read aloud as the tiles are: "Sessions, 2 needs you, 1 stalled"
  link.setAttribute("aria-label", [name, ...counts.map(([k, n]) => `${n} ${STATE_WORDS[k]}`)].join(", "));
  navLine(false);
}
// A PAGE'S LINK CARRIES THE STATE THAT PAGE HOLDS (Oct 27). Accounts is the page that knows which logins are at their
// limit, and nothing anywhere else said so: a provider ran out and a person on Sessions or Usage could not see it until
// they opened Accounts. Accounts draws its own count on its link from the reading its board is drawn from, so the two
// never disagree, and keeps it in this browser with the moment each login comes back; every other page draws that
// reading, and a login drops out of the count at its own reset, the instant Accounts showed. A login held with no reset
// reported stays counted until Accounts reads it again. Drawn only above zero, as the lamp the tiles draw for it.
const NAV_MARK_KEY = "splice-nav-marks";
const NAV_MARK = { limit: { lamp: ICON.lock, words: (n) => `${n} at ${n === 1 ? "its" : "their"} limit` } };
const navMarks = () => { try { return JSON.parse(localStorage.getItem(NAV_MARK_KEY) || "{}") || {}; } catch { return {}; } };
/** [file]'s link carries [n] of [tone] ("limit"), the count its page holds; [until] is when each one comes back, null
 *  where no reset is known. A page calls this each time it draws the count. */
function navMark(file, n, tone, until = null) {
  const marks = navMarks();
  if (n > 0) marks[file] = { tone, until: until ?? Array.from({ length: n }, () => null) }; else delete marks[file];
  try { localStorage.setItem(NAV_MARK_KEY, JSON.stringify(marks)); } catch { /* a blocked store keeps the mark on this page only */ }
  drawNavMark(file, n, tone);
}
/** Every page: the marks its pages left, each counted at this moment against the resets they carry. */
function drawStoredMarks() {
  const now = Date.now();
  for (const [file, m] of Object.entries(navMarks())) if (NAV_MARK[m?.tone]) drawNavMark(file, (m.until || []).filter((t) => t == null || t > now).length, m.tone);
}
function drawNavMark(file, n, tone) {
  const link = document.querySelector(`.side .nav a[href="${file}"]`), kind = NAV_MARK[tone];
  if (!link || !kind) return;
  let mark = link.querySelector(".nmark");
  const name = link.querySelector(".nname")?.textContent ?? "";
  if (!(n > 0)) { if (mark) { mark.remove(); link.removeAttribute("aria-label"); link.removeAttribute("title"); navLine(false); } return; }
  const html = `<span class="pc big ${tone}"><span class="tlamp ${tone}" aria-hidden="true">${kind.lamp}</span><b>${DRAW.n(n)}</b></span>`;
  if (mark?.innerHTML === html) return;
  const fresh = !mark;
  if (!mark) { mark = document.createElement("span"); mark.className = "nmarks nmark"; link.append(mark); }
  mark.innerHTML = html;
  if (fresh) mark.classList.add("pop");
  link.setAttribute("aria-label", `${name}, ${kind.words(n)}`);
  link.title = kind.words(n);
  navLine(false);
}
let navTimer = 0;
// a page's own read of the sessions landing redraws the marks off it, a moment after, once the live turns are in too
addEventListener("splice:heard", (e) => {
  if (e.detail !== "/api/sessions") return;
  clearTimeout(navTimer);
  navTimer = setTimeout(markNav, 400);
});
addEventListener("DOMContentLoaded", () => {
  markNav();
  drawStoredMarks();
  setInterval(() => { markNav(); drawStoredMarks(); }, NAV_READ_MS);
  document.addEventListener("visibilitychange", () => { if (!document.hidden) markNav(); });
});
drawTheme();
document.querySelector('.side [data-act="theme"]')?.addEventListener("click", toggleTheme);

// THE STALE MARK (web-console.md:385, :397; gap 460): splice.toml on disk no longer matches what the daemon booted with,
// so a change written there, by this console or by hand, waits on a restart. /health says so as topologyStale; the
// mark sits at the top bar's right end, beside the theme toggle, on every page and is absent while the two match. Read once the page's scripts have
// loaded (API comes after this file), then every 15 s; an unanswered read leaves the mark as it was.
//
// AND THE ACT IS ON THE MARK (Oct 26). The words were the user's already, but the only way to act on them was to go to
// Settings and find Restart splice, so the mark told him to do something and offered nothing. The restart lives here
// now, as an act on the thing it changes: it arms once with the sessions it would cut (midRequest, as Settings and
// Models arm theirs), spins where it stands, and says in place what happened. The daemon's side is unchanged:
// POST /api/daemon/restart, then GET /api/daemon/restart answers again once the successor is up.
const STALE_READ_MS = 15_000, RESTART_POLL_MS = 2_000, RESTART_TRIES = 60, SAID_MS = 4_000;
// what the restart press is doing right now: armed with the sessions it would cut, out, or done with a word to say
let restarting = null;
async function markStale() {
  const foot = document.querySelector(".side .foot");
  const res = foot ? await API.get("/health") : null;
  if (!res?.ok) return;
  // the mark stays while a restart of its own is in flight or has a word to say, so the act never vanishes mid-press;
  // a way out he was handed (the command) has done its work once splice runs the saved settings
  if (res.body?.topologyStale !== true && restarting?.manual) restarting = null;
  if (res.body?.topologyStale !== true && !restarting) { foot.querySelector(".stale")?.remove(); return; }
  drawStale();
}
function drawStale() {
  const foot = document.querySelector(".side .foot");
  if (!foot) return;
  let mark = foot.querySelector(".stale");
  if (!mark) {
    mark = document.createElement("span");
    mark.className = "stale";
    foot.prepend(mark);
    mark.addEventListener("click", async (e) => {
      const act = e.target.closest("[data-act]")?.dataset.act;
      if (act === "restart") pressRestart();
      if (act === "restart-now") restartSplice();
      if (act === "keep") { restarting = null; drawStale(); }
      if (act === "copy-restart") {
        let ok = true;
        try { await navigator.clipboard.writeText(RESTART_LINE); } catch { ok = false; }
        restarting = { ...restarting, copied: ok ? "Copied" : "Could not copy" };
        drawStale();
        setTimeout(() => { if (restarting?.copied) { restarting = { ...restarting, copied: null }; drawStale(); } }, SAID_MS);
      }
    });
  }
  // THE USER'S WORDS, NOT THE FILE'S (Oct 26), AND THE DOOR TO THEM (Oct 27). It read "splice.toml changed · restart to
  // apply", then "Settings changed since splice started" at 12.9 px beside a 26 px act. The fact is two words in the
  // links' own type, and a door to the page that holds those settings; the act that applies them stands beside it.
  const words = `<a class="stale-door" href="settings.html">${ICON.retries}<span>Settings changed</span></a>`;
  const act = restarting?.manual
    // SPLICE CANNOT BRING ITSELF BACK HERE, SO THE WAY OUT IS THE COMMAND THAT CAN (Oct 27): a refusal that said
    // "Nothing is set to start it again" left nothing to press. `splice restart` stops it and starts it from a terminal.
    ? `<span class="stale-way"><span class="said">${esc(restarting.manual)}</span><code>${RESTART_LINE}</code>`
      + (restarting.copied ? DRAW.result(restarting.copied, restarting.copied === "Copied" ? "good" : "bad")
        : `<button class="act quiet small" type="button" data-act="copy-restart">Copy</button>`) + `</span>`
    : restarting?.said
      ? DRAW.result(restarting.said, restarting.tone)
      : restarting?.armed
        ? armedAsk("Mid-request", restarting.armed, `<button class="act danger small" type="button" data-act="restart-now">Restart splice</button>`,
          `<button class="act quiet small" type="button" data-act="keep">Keep running</button>`)
        : DRAW.act({ word: restarting?.out ? "Restarting" : "Restart splice", act: "restart", cls: "quiet small",
          busy: Boolean(restarting?.out || restarting?.reading) });
  mark.innerHTML = words + act;
  // a refusal shakes what the press was on; the press is gone by then (its word stands in its place), so the mark shakes
  if (restarting?.shook) { (mark.querySelector(".act") || mark).classList.add("shook"); restarting.shook = false; }
}
const RESTART_LINE = "splice restart";
/** The first press reads which sessions have a request in flight; none, and it restarts at once. */
async function pressRestart() {
  if (restarting?.out || restarting?.reading) return;
  restarting = { reading: true };
  drawStale();
  const cut = await midRequest();
  if (!cut.length) { restartSplice(); return; }
  restarting = { armed: cut };
  drawStale();
}
async function restartSplice() {
  restarting = { out: true };
  drawStale();
  const res = await API.post("/api/daemon/restart", {});
  if (res.body?.error) console.warn(`restart: ${res.body.error}`);
  // one already under way is the same wait as his own: it says Restarted when splice is back
  if (res.status === 409) { pollRestart(0); return; }
  if (!res.ok) {
    // 503 is the daemon saying nothing would bring it back, unwired or unsupervised: both mean the same to him here,
    // and the way out is the command; any other refusal leaves the act to press again
    restarting = res.status === 503 ? { manual: "Can't restart itself", shook: true } : { said: "Not restarted", tone: "bad", shook: true };
    drawStale();
    if (restarting.said) setTimeout(() => { if (restarting?.said) { restarting = null; markStale(); } }, SAID_MS);
    return;
  }
  pollRestart(0);
}
/** Until the daemon answers again. Every call while it is down fails, which is the wait, not an error. Past two
 *  minutes the wait is said, with the command that brings it back, and the reading goes on at the mark's own pace. */
function pollRestart(tries) {
  setTimeout(async () => {
    const res = await API.get("/api/daemon/restart");
    if (res.ok && res.body?.status === "idle") {
      restarting = { said: "Restarted", tone: "good" };
      drawStale();
      setTimeout(() => { restarting = null; markStale(); }, SAID_MS);
      return;
    }
    if (tries === RESTART_TRIES) { restarting = { manual: "Not back yet", shook: true }; drawStale(); }
    if (tries >= RESTART_TRIES) { setTimeout(() => pollRestart(tries + 1), STALE_READ_MS - RESTART_POLL_MS); return; }
    pollRestart(tries + 1);
  }, RESTART_POLL_MS);
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
// a count whose read answered with a failure: the dash a figure splice has no reading for wears, said "Not read" on hover
const NOT_READ = `<i class="nr" title="Not read">—</i>`;
function pickerHtml(projects, pick, open, doors, all = null, show = showCount) {
  const label = (k) => projects.find(([key]) => key === k)?.[1] ?? doors.find(([key]) => key === k)?.[1] ?? "Every project";
  const totalOf = (k) => (k === "" ? all : projects.find(([key]) => key === k)?.[2] ?? null);
  const fig = (n) => (n == null ? "" : `<span class="pfig">${n === false ? NOT_READ : show(n)}</span>`);
  const item = (k, text) => `<button role="menuitemradio" aria-checked="${(pick ?? "") === k}" data-act="pickproj" data-p="${esc(k)}"><span class="pname">${esc(text)}</span>${fig(totalOf(k))}</button>`;
  const menu = open ? `<div class="menu pmenu" role="menu">${item("", "Every project")}${projects.map(([k, l]) => item(k, l)).join("")}</div>` : "";
  const ways = pick == null ? "" : doors.filter(([k, , n]) => k !== pick && n)
    .map(([k, l, n]) => `<button class="pdoor" data-act="pickproj" data-p="${esc(k)}" aria-label="${esc(`${l}, ${n} needs you`)}"><span class="pname">${esc(l)}</span>${NEEDS_LAMP(n)}</button>`).join("");
  return `<span class="pwrap"><button class="ppick${pick != null ? " on" : ""}" data-act="projmenu" aria-haspopup="menu" aria-expanded="${Boolean(open)}">`
    + `<span class="pname">${esc(pick == null ? "Every project" : label(pick))}</span>${fig(totalOf(pick ?? ""))}${ICON.caret}</button>${menu}</span>${ways}`;
}

// ---------- the project tiles: at a 150rem page and wider (about 1970 px and up since the nav moved to the top bar and
// the page took the rail's width), every project at once (Marlin, fin) ----------
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
// a lamp and its count; on a tile, with the words the lamp stands for beside the count ("1 needs you", "3 working")
const tlampHtml = ([k, n], said = false) => `<span class="pc big ${k}"><span class="tlamp ${k}" aria-hidden="true">${TLAMP[k]}</span><b>${n}</b>${said ? `<span class="pw">${STATE_WORDS[k]}</span>` : ""}</span>`;
// the brief's own words for a header read aloud: "temple, 1 needs you, 3 working, 7 sessions" (Marlin)
const STATE_WORDS = { needs: "needs you", stalled: "stalled", working: "working" };
/** "1 needs you, 3 working, 7 sessions": what a tile says to a screen reader, in the lamps' order. */
const countWords = (counts, total, unit, show = showCount) => [...counts.filter(([, n]) => n).map(([k, n]) => `${n} ${STATE_WORDS[k]}`),
  `${show(total)} ${total === 1 ? unit : `${unit}s`}`].join(", ");
const sumCounts = (lists) => ["needs", "stalled", "working"].map((k) => [k, lists.flat().filter(([x]) => x === k).reduce((a, [, n]) => a + n, 0)]);
/** [items] is [[key, label, counts, total, words]] with Every project's key "". Each tile's preferred width is its name
 *  in its own font plus the total, so the row's room goes where the names are, and in a crowded row a long name
 *  truncates before its total does (the total sits beside the name, never at the lamps' end, where "4" and "1,300"
 *  would read as one number).
 *
 *  EACH TILE SAYS WHAT IT COUNTS AND DRAWS ITS SHARE (Oct 27). The figure carries its unit on its own baseline,
 *  "2,127 requests", once per tile: a bare "2,127" changed meaning with the page, and the unit said once over the row
 *  read as the page's title again ("TEAMS" under "Teams"). Every tile but Every project, which is the whole, draws its
 *  total on one track as a share of Every project's, and the tiles stand in equal columns (console.css .ptiles), so
 *  the shares compare by eye. A project with nothing in the window keeps its tile, since the tile is how it is
 *  picked, drawn quiet: a dashed rim, its 0 muted and an empty track. */
function tilesHtml(items, pick, show = showCount, unit = "") {
  const all = items.find(([k]) => k === "")?.[3];
  const tiles = items.map(([k, label, counts, total, words]) => {
    const on = (pick ?? "") === k, every = k === "", zero = total === 0 && !every;
    // A TILE BEING READ IS THE SIZE IT WILL BE (Oct 26): the names are known before the figures, so a tile whose
    // total has not landed draws the figure and the share as the shapes that will land there, and the page under
    // the row stays where it is. It used to lose both, which left a 10 px stripe and moved the page by ~100 px.
    // A COUNT THAT COULD NOT BE READ IS NOT A COUNT ON ITS WAY: null is being read (stripes, which move), false is a read
    // that answered with a failure (a muted dash and an empty track, which stand still), so a tile whose figure will
    // never come is told from one whose figure is coming.
    const figure = total == null ? GHOST.bar(2.6) : total === false ? NOT_READ : show(total);
    const what = unit ? `<span class="punit">${esc(total === 1 ? unit : `${unit}s`)}</span>` : "";
    const share = every ? "" : total == null ? `<span class="pshare ghost" aria-hidden="true"></span>`
      : `<span class="pshare" aria-hidden="true"><i style="width:${total !== false && all > 0 ? Math.min(100, (total / all) * 100).toFixed(1) : 0}%"></i></span>`;
    return `<button class="ptile${on ? " on" : ""}${zero ? " zero" : ""}" data-act="pickproj" data-p="${esc(k)}" aria-pressed="${on}" aria-label="${esc(`${label}, ${words}`)}" title="${esc(label)}">`
      // the figure and its unit are one run of words with a real space between them, so the tile reads "2,262 sessions"
      // as text and aloud, never "2,262sessions"; the run keeps together on the name's baseline
      + `<span class="ptop"><span class="tname">${esc(label)}</span><span class="pfigs"><span class="ptotal">${figure}</span>${what ? ` ${what}` : ""}</span></span>${share}`
      + `<span class="plamps">${counts.filter(([, n]) => n).map((c) => tlampHtml(c, true)).join("")}</span></button>`;
  }).join("");
  return `<nav class="ptiles" aria-label="Projects">${tiles}</nav>`;
}

// ---------- the projects as Teams and Requests see them: off /api/sessions rows, in Sessions' group order ----------
// Sessions groups its own cards (sessions.js groupsOf); the other pages read the same rows raw. The order is the same
// rule and the same reading (rowLook, rankOf, from the same live turns): a project where someone waits on him first,
// then one with a stalled session, then every other project by its name, No project last. ONE ORDER (Marlin): a
// project that ranked one way here and another on Sessions would read as two facts.
// AN ORDER HE CAN LEARN (Oct 27): working and idle no longer rank, and neither does the latest activity, since both
// change by the minute: the tiles reshuffled between two visits minutes apart, so a project found once had moved by
// the next. Only a project that needs him moves, and its lamp says why.
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
    || Math.min(a.rank, 2) - Math.min(b.rank, 2) || a.label.localeCompare(b.label));
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
    const words = (c, n) => (n == null || n === false ? countWords(c, 0, count.unit).replace(/(, )?0 \w+$/, "") : countWords(c, n, count.unit, show));
    const items = groups.map((g) => { const n = count.of(g.key); return [g.key, g.label, g.counts, n, words(g.counts, n)]; });
    const every = sumCounts(items.map((x) => x[2]));
    return tilesHtml([["", "Every project", every, count.all, words(every, count.all)], ...items], pick, show, count.unit);
  }
  return `<div class="lhead">${pickerHtml(groups.map((g) => [g.key, g.label, count.of(g.key)]), pick, open, groups.map((g) => [g.key, g.label, g.counts[0][1]]), count.all, show)}</div>`;
}
