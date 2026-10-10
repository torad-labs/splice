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
// A provider splice has no colour for draws in the muted text colour rather than in another provider's.
const colorOf = (provider) => `var(${COLORS[provider] || "--text-mute"})`;

// ---------- fin's words for how a request ended (OutcomeTag.kt's tags) ----------
// A clean request and a spent window have no word: the window shows its own lock and reset time. A stop is not
// a failure (isStopped, OutcomeTag.kt:75-76).
const OUTCOME_WORD = { client_abort: "Stopped", "error:stopped": "Stopped", "error:cancelled": "Cancelled", "error:restarted": "splice restarted",
  "error:plan-limit": "", "error:rate-limited": "Rate limited", "error:all-accounts-exhausted": "No quota", "error:budget-blocked": "Over budget",
  "error:auth-missing": "Signed out", "error:upstream-failed": "Provider error", "failure:overloaded_error": "Overloaded",
  "error:conn-reset": "Network error", "error:upstream-frame-too-large": "Stream error", empty_model: "Empty answer", "error:unexpected": "Internal error" };
// A turn splice gave up on after no progress from the model for the limit reads "Given up". splice writes no
// such tag yet: it lands as an Overloaded error with "splice progress timeout expired" (CancellationSeal.kt:149).
OUTCOME_WORD["error:turn-cap"] = "Given up";
// A COUNT of turns an outcome ended reads with "when", so "60 when splice restarted" is sixty turns cut, not
// sixty restarts (fin, after p85 and p78); a single request keeps the outcome's own word.
const COUNT_WORD = { "error:restarted": "when splice restarted" };
const countWord = (o) => COUNT_WORD[o] ?? OUTCOME_WORD[o];
const STOPPED = new Set(["client_abort", "error:stopped"]);
const isClean = (o) => o === "ok" || o === "empty_message";
const isFailed = (o) => !isClean(o) && !STOPPED.has(o);
// What happened, as a door on Settings names it and Requests filters it (?why=gaveup). "Overloaded" is every
// request that came back overloaded, from the provider or from splice (OutcomeTag.kt:68-69). "Waited in line"
// waited for a free slot at its command's limit (admit_wait_ms). "Waited out" is a silence the watchdog asked
// about and held (Watchdog.kt:303-362). "Resumed" and "Started over" are a request's re-anchor tick: splice
// re-sent a broken stream from its partial answer, or ran the round over from scratch (his own two phrases).
// "Resumed after a silence" is the stall tier's resume (Knob.kt:270-283), the one Silent models counts.
const WHY = {
  gaveup: { word: "Given up", has: (r) => r.outcome === "error:turn-cap" },
  overloaded: { word: "Overloaded", has: (r) => r.outcome === "failure:overloaded_error" },
  queued: { word: "Waited in line", has: (r) => !!r.queued },
  waited: { word: "Waited out", has: (r) => !!r.silent },
  resumed: { word: "Resumed", has: (r) => !!r.resumed && !r.resumedFresh },
  startedover: { word: "Started over", has: (r) => !!r.resumedFresh },
  silentover: { word: "Started over after a silence", has: (r) => !!r.resumedFresh && !!r.stallMs },
  silentresume: { word: "Resumed after a silence", has: (r) => !!r.resumed && !!r.stallMs },
  restarted: { word: "splice restarted", has: (r) => r.outcome === "error:restarted" },
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
  lock: G('<rect x="4.5" y="10.5" width="15" height="10" rx="2"/><path d="M8 10.5V7.5a4 4 0 0 1 8 0v3"/>', 'aria-hidden="true"'),
};
const esc = (s) => String(s).replace(/[&<>"]/g, (c) => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;" })[c]);
const clock = (d) => {
  const h = d.getHours() % 12 || 12, m = d.getMinutes(), ap = d.getHours() < 12 ? "AM" : "PM";
  return `${h}:${String(m).padStart(2, "0")} ${ap}`;
};
// A key is an account: its variable and the day splice first saw it, "OPENROUTER_API_KEY · Sep 14" (fin).
const dayWord = (d) => d.toLocaleDateString("en-US", { month: "short", day: "numeric" });
// When a window comes back, as Accounts says it: a time today, else the day and the time.
const backWord = (d) => (d.toDateString() === NOW.toDateString() ? clock(d) : `${d.toLocaleDateString("en-US", { weekday: "short" })} ${clock(d)}`);
const counter = (ms) => { const s = Math.floor(ms / 1000); return `${Math.floor(s / 60)}:${String(s % 60).padStart(2, "0")}`; };
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
const stalled = (s) => s.state === "working" && s.stall;
function look(s) {
  if (s.state === "needs") return { cls: "needs", lamp: ICON[s.ask.kind], word: "Needs you" };
  if (stalled(s)) {
    const k = s.stall.kind, lamp = k === "silent" ? ICON.flat : ICON[k];
    const detail = k === "silent" ? `<span class="detail" data-silent="${s.id}">${ICON.watch(Math.floor(s.stall.ms / 1000))}<span>${counter(s.stall.ms)}</span></span>`
      : k === "retries" ? `<span class="detail">${s.stall.n} ${s.stall.n === 1 ? "retry" : "retries"}</span>`
      : k === "limit" ? `${s.stall.acct ? acctPlanHtml(s.stall.acct, s.stall.plan) : ""}<span class="detail">Resets ${backWord(s.stall.until)}</span>` : `<span class="detail">Signed out</span>`;
    // a plan's limit ended the turn; nothing stalled, so the word is Accounts' own (fin): lock, At limit, the plan, its reset
    if (k === "limit") return { cls: "stalled", lamp, word: `${ICON.lock}At limit`, detail };
    return { cls: "stalled", lamp, word: "Stalled", detail };
  }
  if (s.state === "working") return { cls: "working", lamp: ICON.trace, word: "Working" };
  if (s.state === "stopped") return { cls: "stopped", lamp: "<i></i>", word: "Stopped" };
  if (s.state === "seen") return { cls: "seen", lamp: "<i></i>", word: `Seen ${clock(s.at)}` };
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
  ["teams.html", "Teams"],
];
// WITH ONE PAGE THE NAV DRAWS NOTHING (hitstop, Oct 10): a lone link repeats the page's own title, and a single
// selected box reads as a strip with its other tabs torn off. The column keeps its width, its wordmark and its
// theme toggle either way, so nothing moves when the links arrive.
function drawNav() {
  const side = document.querySelector(".side");
  if (!side || PAGES.length < 2 || side.querySelector(".nav")) return;
  const here = location.pathname.split("/").pop() || PAGES[0][0];
  const links = PAGES.map(([file, name]) =>
    `<a href="${file}"${file === here ? ' aria-current="page"' : ""}>${esc(name)}</a>`).join("");
  const nav = document.createElement("nav");
  nav.className = "nav";
  nav.setAttribute("aria-label", "Pages");
  nav.innerHTML = links;
  side.insertBefore(nav, side.querySelector(".foot"));
}
drawNav();
