// Accounts: every account on every provider, how much of each window is used, when it comes back, which one
// serves next, and what each command may spend in a day. The drawing (Splice-Animator design/console, d3338fa) on
// splice's own answers: every name, number and state below is read from the control API, and every act calls it.
CONSOLE.page("accounts.html", (scope) => {
  "use strict";
  const { document, window, API, setTimeout, clearTimeout, setInterval, clearInterval, requestAnimationFrame, cancelAnimationFrame, addEventListener, removeEventListener } = scope;

const LEN = { "5 hours": 300, Week: 10080, Month: 43200, Day: 1440 };

// splice's budget day runs midnight to midnight on the DAEMON's clock (BudgetEnforcement.kt:10), and the daemon says
// when it next rolls over (day_resets_at_epoch_ms). A browser in another zone computing its own midnight disagreed
// with the admission that actually blocks (re-review, Oct 10). Only a daemon that answers nothing falls back here.
let DAY_RESETS_AT = null;
let DAY_STARTED_AT = null; // the instant the budget day now running began, from the daemon's own calendar day
let DAY_FROM = null; // the first whole hour the day's figures count from, where each Day's door to its requests starts
let HOUR_MAX = 1; // the busiest hour of any command today: every strip's hours are drawn on this one scale
// NO DAY ON THE BOARD WAS READ (the day's read failed, or splice could read no command's history): the page head says so
// once with the reason, and each strip keeps a dash and its hatched hours. Null while any day reads. { why }
let DAY_UNREAD = null;
// whether each command was all on a plan the last time splice said (all_accounts_on_plan), kept across visits, so a day
// that is not read still knows whether its strip offers a budget
const SPLIT_KEY = "splice-accounts-split";
const SPLIT_SEEN = new Map((() => { try { return Object.entries(JSON.parse(localStorage.getItem(SPLIT_KEY) || "{}")); } catch { return []; } })());
function dayReset() {
  const next = DAY_RESETS_AT ?? new Date(NOW.getFullYear(), NOW.getMonth(), NOW.getDate() + 1).getTime();
  return { left: Math.round((next - NOW.getTime()) / 60000), resetMs: next };
}

// The provider a command belongs to, by the family splice gives its head (/api/status registry, ProviderFamilyRule.kt).
const FAMILY = {
  anthropic: { id: "claude", name: "Claude" }, openai: { id: "gpt", name: "ChatGPT" }, xai: { id: "grok", name: "Grok" },
  moonshot: { id: "kimi", name: "Kimi" }, meta: { id: "muse", name: "Muse" }, openrouter: { id: "router", name: "OpenRouter" },
  deepseek: { id: "deepseek", name: "DeepSeek" }, local: { id: "local", name: "This computer" },
  vast: { id: "vast", name: "vast.ai" }, // a rented GPU behind a tunnel: the provider says family = "vast" (fin's name)
};
// What Add provider offers: a provider splice can add, picked by the family it would run under.
const CATALOG = [
  { id: "claude", name: "Claude", kind: "plan" }, { id: "gpt", name: "ChatGPT", kind: "plan" },
  { id: "grok", name: "Grok", kind: "plan" }, { id: "kimi", name: "Kimi", kind: "plan" },
  { id: "muse", name: "Muse", kind: "plan" }, { id: "router", name: "OpenRouter", kind: "key" },
  { id: "deepseek", name: "DeepSeek", kind: "key" },
];

// A window as the page draws it, from splice's flat fields: its name from its slot, how much is used, and the minutes
// until it resets (null once the reading's reset has passed: the window has refilled since, and the page shows no time).
// A weekly window the provider gives no length is measured from the time left to its reset
// (QuotaSlots.weeklyWindowSeconds), so the long slot reads Week unless it is a month long.
//
// EACH WINDOW CARRIES ITS OWN STALENESS, from splice's own `*_current` field for that window
// (QuotaFreshness): one current window never vouches for another. A 5-hour reading minutes old sits
// beside a week whose reset has passed, and the week's figure is then the usage of a week that
// ended; drawing it unmarked reports spent capacity on a week that starts at zero. [current]
// undefined is a caller with no freshness to give, which is drawn as it always was.
function windowOf(pct, resetSec, lenSec, slot, current) {
  if (pct == null) return null;
  const month = slot === "long" && lenSec >= 28 * 86400;
  const label = slot === "short" ? "5 hours" : month ? "Month" : "Week";
  const left = resetSec ? Math.round((resetSec * 1000 - NOW.getTime()) / 60000) : null;
  const due = left != null && left >= 0;
  return { label, used: Math.round(pct), len: LEN[label], left: due ? left : null, resetMs: due ? resetSec * 1000 : null,
    // The reset splice last heard about, once it has gone by: the figure splice holds is the ENDED window's, so the
    // row drops it and says only this. Null when no reset was ever reported, the one case the page knows nothing of.
    rolledMs: resetSec && !due ? resetSec * 1000 : null,
    stale: current === false };
}
// A model week's own freshness, under the account week it is drawn beneath: that week's reading is the one
// observation both came from, and the model's own reset decides whether its figure is still this week's.
// splice drops a rolled row (QuotaSnapshot.modelsRunningAt); a daemon older than that still sends one, and the
// page must not draw it as today's. Undefined week freshness (a daemon older than the field) leaves the row
// drawn as it always was.
function modelCurrent(weekCurrent, m) {
  if (weekCurrent === false) return false;
  return m.resets_at && m.resets_at * 1000 <= NOW.getTime() ? false : weekCurrent;
}
const plainPlan = (s) => (s ? s.charAt(0).toUpperCase() + s.slice(1) : undefined);
// An account's name on one command, as splice knows it there: two commands can each have an account called "primary"
// (ChatGPT's and Grok's), so the page keys an account by its commands and that name, and sends splice the name only.
const labelOf = (row) => row.selector_key || row.label || "primary";
// a login's id on its provider's board is the kit's (kit.js loginCardId), so the pages that link to a card spell it the
// way the board keys it
const acctId = loginCardId;
const labelOn = (a, head) => a.row?.account_labels?.[head] || labelOf(a.row);

// One read of everything the page shows. Each provider: its commands (with their order, pin and day) and its accounts.
// The day's counts come from /api/economics, which on a daemon that has just started can take half a minute. The page
// never waits on it: it is asked beside the other reads, the cards are drawn at once with the Day row's ghost where its
// count goes, and when it answers the page is read again with that answer in hand.
let economics = null; // the last /api/economics answer, kept so a later read draws the day it knew while a new one is asked
let askingEconomics = false;
const ECONOMICS_REASK_MS = 2000;
function askEconomics() {
  if (askingEconomics) return;
  askingEconomics = true;
  API.getReady("/api/economics").then(async (answer) => {
    economics = answer;
    askingEconomics = false;
    await load({ asks: false });
    render();
    // a command the daemon is still reading said so, and its ghost stands only until it has: ask again until none is
    if (answer.ok && (answer.body?.heads || []).some((h) => h.read_pending)) setTimeout(askEconomics, ECONOMICS_REASK_MS);
  });
}
// The sessions on each login come from /api/sessions, which took 4 s and more on his machine while every other read here
// answers in under one, and the page showed only Add provider until it did (walk on d0ec523b5). So it is asked beside
// the other reads and never waited on: the cards draw at once with ghost doors under each login, as many as it had on the
// last visit, and when it answers each card takes its sessions and the page draws again. Null is not read yet; a later
// read keeps the last answer meanwhile.
let liveSessions = null;
let askingSessions = false;
function askSessions() {
  if (askingSessions) return;
  askingSessions = true;
  API.get("/api/sessions").then((ss) => {
    askingSessions = false;
    // the sessions running now, each on the login splice proved it uses (/api/sessions account, SessionAccountOf.kt): a
    // login lists them under its card (v0.4.0 section 9: show which account every session is currently on)
    liveSessions = ss.ok ? (ss.body?.sessions || []).filter((x) => x.availability === "live" && x.account && x.head) : liveSessions ?? [];
    for (const p of data) for (const a of p.accounts) if (a.sessionsOn) a.sessions = a.sessionsOn();
    render();
  });
}
// A read that a newer one overtook keeps nothing it read, and it ends only once the read that overtook it has ended, so
// whoever waits on a read (the refresh after an act, Add naming the login it signed in) sees a board at least as new as
// the one it asked for. Ending at once left Add a board from before its sign-in, because the day's figures that refresh
// asked for answered first and started a newer read, and the name typed on Add was never given (account-labels).
let loadVersion = 0, accountRevision = 0, newestRead = null;
function load(options) { return (newestRead = readAll(options)); }
async function readAll({ asks = true } = {}) {
  const version = ++loadVersion, revision = accountRevision;
  tick();
  if (asks) askEconomics();
  askSessions();
  const [st, ac, ks, bg, md, hd, au] = await Promise.all(["/api/status", "/api/accounts", "/api/keys", "/api/budgets", "/api/models", "/api/heads", "/api/auth"].map((p) => API.get(p)));
  // each login's name by every label its pool knows it by on a head (its pool label and its selector, "native:claude"
  // for Claude Code's own sign-in), so a session's last switch reads in Accounts' names and never as an id; a name a person
  // gave is kept here, and every login with a card takes the card's own title below (the console's name rule)
  for (const r of ac.body?.accounts || []) for (const h of r.heads || []) {
    const own = DRAW.ownName(r.display_name);
    for (const l of [r.account_labels?.[h], r.label, r.selector_key]) if (l && own) LOGIN_NAME.set(`${h}|${l}`, own);
  }
  if (version !== loadVersion) return newestRead;
  if (revision !== accountRevision || ui.orderSaving || ui.removing) return;
  // A READ SPLICE DID NOT ANSWER KEEPS THE BOARD IT HAD (Oct 26). It emptied the list, so the board drew only Add provider:
  // a splice with no providers, inviting him to add what was already there. The last board stays with its figures, the
  // page head says splice is not answering and since when, and the page asks again on its own until it answers.
  if (!st.ok || !ac.ok) {
    // a 401 is the console's lock (api.js), which only `splice console` opens again: there is nothing to ask again
    if (st.status === 401 || ac.status === 401) return;
    unheard = unheard ?? { at: Date.now() };
    askAgainSoon();
    return;
  }
  unheard = null;
  const ec = economics || { ok: true, body: { heads: (st.body?.registry || []).map((h) => ({ key: h.key, read_pending: true })) } };
  const budgets = new Map((bg.body?.budgets || []).map((b) => [b.head, b]));
  DAY_RESETS_AT = bg.body?.day_resets_at_epoch_ms ?? null;
  DAY_STARTED_AT = bg.body?.day_started_at_epoch_ms ?? null;
  // The day splice is counting begins where the daemon says it began: its own calendar day's midnight, never
  // tomorrow's minus 24 hours, which is wrong on the two days a year the clock changes. Only a daemon that answers
  // nothing falls back to this browser's midnight.
  const dayStart = DAY_STARTED_AT ?? new Date(NOW.getFullYear(), NOW.getMonth(), NOW.getDate()).getTime();
  // Buckets are whole UTC hours, so where the day starts inside one (a zone offset by half an hour) that hour holds
  // turns of both days and its buckets cannot say which is which; an exact figure there needs the per-turn rows. The
  // figure counts the whole hours from the first boundary after the day began, and the opening hour is its own
  // number beside it, never guessed into either day.
  const HOUR = 3600000;
  const wholeFrom = Math.ceil(dayStart / HOUR) * HOUR;
  DAY_FROM = wholeFrom;
  // THE DAY HOUR BY HOUR, midnight to the reset, drawn on each command's Day row: each whole hour's requests split into
  // the ones that went through (on a plan or priced), the ones with no price and the failed ones; an hour the daemon could
  // not read is marked so; the hours still to come are empty. 23 or 25 of them on the days the clock changes.
  const dayEnds = DAY_RESETS_AT ?? wholeFrom + 24 * HOUR;
  const slots = Math.max(1, Math.min(26, Math.round((dayEnds - wholeFrom) / HOUR)));
  const hoursOf = (h, onPlanHead) => {
    const by = new Map();
    for (const b of h.buckets || []) {
      if (b.hour < wholeFrom || b.hour >= dayEnds) continue;
      const t = by.get(b.hour) || { all: 0, fail: 0, none: 0 };
      // a split hour's unpriced turns are the key's alone; before the split a plan command's unpriced turns are its plan's
      const none = b.plan_turns != null || !onPlanHead ? b.unpriced_turns || 0 : 0;
      by.set(b.hour, { all: t.all + (b.turns || 0), fail: t.fail + (b.failed_turns || 0), none: t.none + none });
    }
    const missing = new Set((h.not_read_hours || []).filter((x) => x >= wholeFrom && x < dayEnds));
    return Array.from({ length: slots }, (_, i) => {
      const at = wholeFrom + i * HOUR, t = by.get(at) || { all: 0, fail: 0, none: 0 };
      return { at, ...t, ok: Math.max(0, t.all - t.fail - t.none), unread: missing.has(at) };
    });
  };
  // economics counts only requests that moved tokens, so turns less the unpriced ones are the priced share (fin, Oct 10)
  // A daemon that splits plan from key (all_accounts_on_plan on the head) says per hour how many requests were on a plan
  // and their value at API prices; cost_usd and unpriced_turns are then the key's alone. An hour recorded before the split
  // has its plan fields null, and its cost and count still mix the two, so it is summed apart (old) and never read as key.
  const sumOf = (rows) => rows.reduce((t, b) => {
    const old = b.plan_turns == null;
    // failed_turns: failed requests that reported no usage, which are failed and never "no price" (Marlin's Accounts walk,
    // ruled Oct 12); an hour written before the field has none, and its failures stay in unpriced_turns as they were counted
    return { usd: t.usd + (b.cost_usd || 0), unpriced: t.unpriced + (b.unpriced_turns || 0), failed: t.failed + (b.failed_turns || 0), turns: t.turns + (b.turns || 0),
      plan: t.plan + (old ? 0 : b.plan_turns), planUsd: t.planUsd + (old ? 0 : b.plan_value_usd || 0),
      old: t.old + (old ? b.turns || 0 : 0), oldUsd: t.oldUsd + (old ? b.cost_usd || 0 : 0), oldUnpriced: t.oldUnpriced + (old ? b.unpriced_turns || 0 : 0) };
  }, { usd: 0, unpriced: 0, failed: 0, turns: 0, plan: 0, planUsd: 0, old: 0, oldUsd: 0, oldUnpriced: 0 });
  const splitOf = new Map((ec.body?.heads || []).filter((h) => typeof h.all_accounts_on_plan === "boolean").map((h) => [h.key, h.all_accounts_on_plan]));
  const spendToday = new Map((ec.body?.heads || []).map((h) => {
    const rows = h.buckets || [];
    const opening = wholeFrom > dayStart ? sumOf(rows.filter((b) => b.hour < wholeFrom && b.hour + HOUR > dayStart)) : null;
    return [h.key, { ...sumOf(rows.filter((b) => b.hour >= wholeFrom)), opening: opening && (opening.usd || opening.unpriced) ? opening : null }];
  }));
  // a command whose hours splice has not read has no count, and its row says why, never zero, because "No requests" over
  // a day that had them is a false statement (Marlin, Oct 11): still being read just after a restart draws the row's
  // ghost; a history it could not read, or no answer at all, reads Not read, as Settings does (fin)
  // a day that holds an hour the daemon could not read has no total, never the sum of the hours it could (not_read_hours)
  const dayUnread = (h) => (h.not_read_hours || []).some((hour) => hour + HOUR > dayStart);
  const unread = new Map(ec.ok ? (ec.body?.heads || []).filter((h) => h.read_pending || h.unavailable || dayUnread(h))
    .map((h) => [h.key, !h.read_pending && (h.unavailable || dayUnread(h)) ? DAY_NOT_READ : DAY_READING])
    : (st.body?.registry || []).map((h) => [h.key, DAY_NOT_READ]));
  // why a day reads Not read, behind the word as its disclosure (splice-usage's walk, Oct 13: ChatGPT's Day said Not read
  // and nothing more): the daemon's own fixed sentence for a history it could not read (EconomicsRead.Unavailable), or
  // that splice did not answer. An hour it could not read needs no sentence: the day draws that hour hatched.
  const unreadWhy = new Map(ec.ok ? (ec.body?.heads || []).filter((h) => !h.read_pending && h.unavailable).map((h) => [h.key, h.unavailable])
    : (st.body?.registry || []).map((h) => [h.key, "splice did not answer with today's requests"]));
  const ecHead = new Map(ec.ok ? (ec.body?.heads || []).map((h) => [h.key, h]) : []);
  // why a command's turns with no price have none, decided by the daemon once for this row and for Requests
  const priceWhy = new Map((ec.body?.heads || []).map((h) => [h.key, h.unpriced_reason]));
  // a local runtime splice could not reach on its port (/api/heads runtimeNotAnswering): its card says so, as a signed-out one does
  const headRows = (Array.isArray(hd.body) ? hd.body : hd.body?.heads) || [];
  const down = new Set(headRows.filter((h) => h.runtimeNotAnswering).map((h) => h.key));
  // a local runtime the watch has no reading for yet, or any more: Checking, never the answering dot
  const unreadRt = new Set(headRows.filter((h) => h.runtimeUnread).map((h) => h.key));
  MODEL_IDS = new Map((md.body?.heads || []).map((h) => [h.head, (h.models || []).filter((m) => m.id).map((m) => ({ id: m.id, label: m.label }))]));
  const pinnedModel = new Map((md.body?.heads || []).map((h) => [h.head, (h.models || []).find((m) => m.pinned)?.label || h.pinned_model]));
  const keyRows = ks.body?.keys || [];
  const provs = new Map();
  for (const h of st.body.registry || []) {
    const fam = FAMILY[h.family] || { id: h.family || h.key, name: h.family || h.key };
    const kind = h.family === "local" || h.family === "vast" ? "local" : h.authKind === "api-key" ? "key" : "plan";
    if (!provs.has(fam.id)) provs.set(fam.id, { id: fam.id, name: fam.name, kind, cmds: [], accounts: [] });
    const p = provs.get(fam.id), b = budgets.get(h.key), today = spendToday.get(h.key) || { usd: 0, unpriced: 0, failed: 0, turns: 0, plan: 0, planUsd: 0, old: 0, oldUsd: 0, oldUnpriced: 0 };
    const allPlan = splitOf.get(h.key);
    // with the split: an all-plan command counts every request as on the plan, its old hours too, and names their value
    // at API prices; a mixed one shows the key's money, the plan's count, and an old hour's requests as not split (fin,
    // Marlin 5:52 PM, splice-lead on null hours, Oct 11)
    const split = allPlan === undefined || kind === "local" ? null : allPlan
      ? { allPlan: true, plan: today.plan + today.old, planUsd: today.planUsd + today.oldUsd }
      : { allPlan: false, keyUsd: today.usd - today.oldUsd, keyUnpriced: today.unpriced - today.oldUnpriced, plan: today.plan, old: today.old,
        keyPriced: Math.max(0, today.turns - today.old - today.plan - (today.unpriced - today.oldUnpriced) - today.failed) };
    const counted = !unread.has(h.key);
    const onPlan = ((b && b.unpriced_reason) || priceWhy.get(h.key) || (kind === "plan" ? "plan" : "undeclared")) === "plan";
    const eh = ecHead.get(h.key);
    p.cmds.push({ cmd: h.label || h.key, head: h.key, kind, authKind: h.authKind, spent: b ? b.used_usd : counted ? today.usd : null, unpriced: b ? b.unpriced_turns : today.unpriced,
      // the day hour by hour, null while the daemon still reads it; a history it could not read at all, or no answer,
      // leaves every hour gone by unknown
      hours: kind === "local" || eh?.read_pending ? null : hoursOf(eh || {}, onPlan), allUnread: !ec.ok || (!!eh && !!eh.unavailable),
      unread: b ? null : unread.get(h.key) ?? null,
      unreadWhy: b ? null : unreadWhy.get(h.key) ?? null,
      failed: b ? b.failed_turns ?? today.failed : today.failed,
      priced: Math.max(0, today.turns - today.unpriced - today.failed),
      turns: counted ? today.turns : null, // every request the day's figures count, which its door opens on Requests
      opening: b ? null : today.opening, // only the bucket-built figure has a half-hour it cannot place; the budget's own is exact
      local: kind === "local", // nothing bills it per token, so no figure and no line
      split,
      onPlan,
      budget: b && b.daily_usd != null ? { cap: b.daily_usd, block: b.action === "block" } : null, mode: "soonest", pin: null, order: [],
      moved: au.body?.[h.key]?.account_pool?.last_switch ?? null, // the command's last move between logins, and why (spec 11h)
      lastLeft: lastLeftOf(ac.body?.head_pools?.[h.key]) });
  }
  for (const p of provs.values()) {
    const heads = new Set(p.cmds.map((c) => c.head));
    if (p.kind === "plan") {
      for (const row of ac.body.accounts || []) {
        if (!(row.heads || []).some((x) => heads.has(x))) continue;
        const login = planAccount(row);
        if (login) p.accounts.push(login);
      }
    } else if (p.kind === "key") {
      for (const k of keyRows) {
        const uses = (k.heads || []).filter((x) => heads.has(x.head));
        if (!uses.length) continue;
        const from = uses[0].source;
        const day = (s) => (s ? new Date(s * 1000) : null); // the day splice first saw the key (KeyLedger.kt)
        // the key in use shows its masked tail, "…9c21", from /api/auth's api_key_masked on a command it serves, so a
        // replaced key can be told from the one now in use (Marlin's Accounts walk, Oct 12); a short key is masked "set"
        const masked = uses.map((u) => au.body?.[u.head]).find((x) => x?.env_var === k.name && x.api_key_masked)?.api_key_masked;
        const tail = masked && masked.includes("…") ? `…${masked.split("…").pop()}` : null;
        // the day only tells keys apart, so it shows only when the variable has held more than one (fin, Oct 12)
        const twins = (k.replaced || []).length > 0;
        // heads: the commands that send with THIS key, so a second command with a key of its own rides its own card, never
        // the provider's first one (marlin's desk A look, Oct 12). The keys it replaced fold into one line on its card
        // (marlin, Oct 12): their spend stays on Usage and their requests on Requests, and they have nothing to act on.
        p.accounts.push({ id: k.name, env: k.name, tail, twins, has: from !== "missing" && from !== "unknown", from: from === "missing" || from === "unknown" ? null : from, seen: day(k.first_seen_epoch_seconds),
          heads: uses.map((u) => u.head), replaced: (k.replaced || []).map((x) => day(x.first_seen_epoch_seconds)) });
      }
    } else {
      for (const c of p.cmds) p.accounts.push({ id: c.head, name: c.cmd, model: pinnedModel.get(c.head) || "", down: down.has(c.head), unread: unreadRt.has(c.head) });
    }
  }
  // each plan command's own order and pin (AccountOrderStore.kt:1, :24): no order is the soonest-reset rule
  await Promise.all([...provs.values()].filter((p) => p.kind === "plan").flatMap((p) => p.cmds.map(async (c) => {
    const mine = p.accounts.filter((a) => (a.row.heads || []).includes(c.head));
    // a command with one login has no pool to order, and its order route refuses with a 409 on every load (walk, Oct 13)
    const o = mine.length > 1 ? await API.get(`/api/auth/${encodeURIComponent(c.head)}/order`) : { body: null };
    const byLabel = (label) => mine.find((a) => labelOn(a, c.head) === label)?.id;
    const ids = [...new Set((o.body?.effective_order || []).map(byLabel).filter(Boolean))];
    c.order = [...ids, ...mine.map((a) => a.id).filter((id) => !ids.includes(id))];
    c.mode = o.body?.order?.length ? "mine" : "soonest";
    c.orderable = new Set(o.body?.effective_order || []); // the labels splice will take in an order
    c.pin = mine.find((a) => (a.row.pinned_heads || []).includes(c.head))?.id || null; // pinned on THIS head, not on whichever head a shared row came from
    // the pool's own picks, named by label on this command
    c.serving = byLabel(o.body?.next_target) ?? null; c.following = byLabel(o.body?.following_target) ?? null;
  })));
  if (version !== loadVersion) return newestRead;
  // An earlier reading must not undo a removal or order that is still being saved.
  if (revision !== accountRevision || ui.orderSaving || ui.removing) return;
  data = [...provs.values()];
  // read means the board's data is held: a draw that the day's figures or the sessions start while the orders are still
  // being read keeps the ghosts, never an empty board offering Add provider
  readOnce = true;
  // a move names a login as its card is titled, never by its address
  for (const p of data) if (p.kind === "plan") for (const a of p.accounts) {
    const named = shownName(p, a);
    for (const h of a.row?.heads || []) for (const l of a.namesOn(h)) LOGIN_NAME.set(`${h}|${l}`, named);
  }
  // the board's one scale for the hours: the busiest hour any command had today, its opening hour included
  const days = data.flatMap((p) => p.cmds).filter((c) => !c.local);
  HOUR_MAX = Math.max(1, ...days.flatMap((c) => (c.allUnread ? [] : [...(c.hours || []).filter((h) => !h.unread).map((h) => h.all), c.opening?.turns || 0])));
  // the requests and the dollars each fill their bar for the busiest day on the board, so a quiet command reads as quiet beside a busy one
  const drawn = days.map((c) => dayBars(c, c.split && !c.unread ? c.split : null));
  REQ_MAX = Math.max(1, ...drawn.map((d) => d.req?.total || 0));
  USD_MAX = Math.max(0.01, ...drawn.map((d) => d.usd?.v || 0));
  // no day read on the whole board is one thing gone wrong, said once (a budgeted day is read from its budget, so it
  // still has its figure)
  const free = days.filter((c) => !c.budget), whys = [...new Set(free.map((c) => c.unreadWhy).filter(Boolean))];
  DAY_UNREAD = free.length && free.every((c) => c.unread === DAY_NOT_READ) ? { why: !ec.ok ? "splice did not answer" : whys.length === 1 ? whys[0] : "" } : null;
  if (splitOf.size) {
    for (const [head, allPlan] of splitOf) SPLIT_SEEN.set(head, allPlan);
    try { localStorage.setItem(SPLIT_KEY, JSON.stringify(Object.fromEntries(SPLIT_SEEN))); } catch { /* a full store keeps the last split */ }
  }
}

// A plan login as the page holds it, from one row of /api/accounts: the board reads it, and a first visit's ghosts size and
// order their cards from the same function, so both cut a wide rail at the same card. Null for no login at all.
function planAccount(row) {
  const windows = [windowOf(row.five_hour_used_percent, row.five_hour_reset_epoch_seconds, row.five_hour_window_seconds, "short", row.five_hour_current),
    windowOf(row.seven_day_used_percent, row.seven_day_reset_epoch_seconds, row.seven_day_window_seconds, "long", row.seven_day_current)].filter(Boolean);
  // a login held at its limit carries the limit's own sentence as its refusal, and it is held, not signed out
  const out = !row.token_renews && (!row.credential_present || row.auth_exclusion_reason === "credential_missing" || (!!row.refusal && !windows.some(atLimit)));
  // Claude Code's own login place that holds no account is no account (Marlin, Oct 10): signed out, no proved
  // account, no reading. Its Sign in only adds an account, as Add account does, and the place still read Signed out
  // after it. One that was signed in and expired keeps its account, and shows.
  if (row.login_place && out && !row.identity_verified && !row.observed_at_epoch_seconds) return null;
  const models = (row.seven_day_models || []).map((m) => ({ name: m.model,
    ...windowOf(m.used_percent, m.resets_at, LEN.Week * 60, "long", modelCurrent(row.seven_day_current, m)) }));
  // a session names its login by the label the pool gave it on its head; a login answers to that, its selector or its
  // own label, and a single login with none is the primary
  const namesOn = (head) => { const n = [row.account_labels?.[head], row.selector_key, row.label].filter(Boolean); return n.length ? n : ["primary"]; };
  const sessionsOn = () => (liveSessions ? liveSessions.filter((x) => (row.heads || []).includes(x.head) && namesOn(x.head).includes(x.account)) : null);
  return { id: acctId(row), row, name: row.display_name, email: row.email || null, plan: plainPlan(row.plan), windows, sessions: sessionsOn(), sessionsOn, namesOn,
    // one observation time behind every bar, so the read line shows as soon as ANY bar drawn here is stale
    staleAt: [...windows, ...models].some((w) => w.stale) && row.observed_at_epoch_seconds ? row.observed_at_epoch_seconds * 1000 : null,
    // an expired token its own command renews on its next run is not a sign-out: the card keeps its readings
    renews: !!row.token_renews,
    out,
    canRename: !!row.can_rename, canRemove: !!row.can_remove, native: !!row.carrying_request,
    // the provider answered with no usage for this account: no bars, never an older reading drawn as today's
    noUsage: !windows.length && !!row.no_usage_at_epoch_seconds,
    // each model's own weekly window, drawn under the week (Claude's Opus and Sonnet)
    models };
}

let data = [];
// SPLICE NOT ANSWERING: since when, while the board it last drew stays (readAll). Null while it answers. readOnce is a
// board read this visit, so a splice that never answered draws the ghost board and never an empty one offering Add.
let unheard = null, readOnce = false, againTimer = null;
// it asks again on its own every 5 s until splice answers, so he never has to keep pressing
function askAgainSoon() {
  clearTimeout(againTimer);
  // the head's ring fills over these 5 s, and starts over with each ask
  const ring = document.querySelector("#unheard .asking");
  if (ring) { ring.classList.remove("go"); void ring.getBoundingClientRect(); ring.classList.add("go"); }
  againTimer = setTimeout(async () => { await load(); render({ flip: true }); }, 5000);
}
const FRESH_UI = () => ({ lane: {}, signin: {}, editor: null, addProv: null, menu: null, armed: null, removeErr: null, rename: null, renameErr: null, renameDraft: null, replace: null, pulse: null, naming: null,
  moving: null, slotErr: null, orderErr: null, keyErr: null, orderSaving: null, removing: null });
// why: the Day rows whose Not read reason is open; reasons: the refusals whose reason splice gave is open under their
// word; doors: the logins whose every session door is out; enter: the one form or menu just opened, which rises into
// place once and never again on a later redraw; shook: the refusal just drawn, which shakes once and then stands still
// moves: the moves whose reason and sessions are open under their line
const ui = { probing: false, checking: null, stillDown: null, grow: null, why: new Set(), reasons: new Set(), doors: new Set(), moves: new Set(), enter: null, shook: null, ...FRESH_UI() };
const board = document.getElementById("board");
let drag = null;
// ---------- time: what a person reads ----------
// A time from the instant splice gave, rounded once to the minute, so every view of one reset prints the same minute (the
// walkers saw 5:38 and 5:39 for one reset when it was rebuilt from minutes left at each load).
function at(ms) {
  const d = new Date(Math.round(ms / 60000) * 60000);
  const days = Math.round((new Date(d).setHours(0, 0, 0, 0) - new Date(NOW).setHours(0, 0, 0, 0)) / 864e5);
  if (days === 0) return clock(d);
  if (Math.abs(days) < 7) return `${d.toLocaleDateString("en-US", { weekday: "short" })} ${clock(d)}`;
  return d.toLocaleDateString("en-US", { month: "short", day: "numeric" });
}

// ---------- the rules the page shows ----------
// A window whose reported reset has gone by. What the page holds for it is the ENDED window's figure, so nothing on
// the screen counts it (Marlin, Oct 10): not the bar, not the number, and not whether the account is at its limit.
// An account held back by a 5-hour window at 100% whose reset passed an hour ago has room, and splice is already
// sending turns to it; the card said At limit until the next reading landed.
const ended = (w) => w.left == null && w.rolledMs != null;
// At its limit NOW: full, and still inside the window that filled. A window at 100% that reported no reset at all
// stays held, because nothing on the page says it rolled — only a reset that has gone by says that.
const atLimit = (w) => w.used >= 100 && !ended(w);
const held = (a) => (a.windows || []).some(atLimit);
const room = (a) => !a.out && !a.renews && !held(a);
const backAt = (a) => Math.max(0, ...a.windows.filter(atLimit).map((w) => w.left ?? 0));
const laneAccts = (p, c) => c.order.map((id) => p.accounts.find((a) => a.id === id)).filter(Boolean);
function ruled(p, c) { // the rule alone: his order, or the soonest reset among the accounts with room
  const list = laneAccts(p, c);
  if (c.mode === "mine") return list;
  const rank = (a) => (room(a) ? 0 : a.out ? 2 : 1);
  // splice's own order (AccountAvailability.resetOrder): the sooner of the two resets, then the week's, then the five
  // hours'; a full tie keeps splice's order, which ends on the primary account and then the name
  const left = (a, label) => a.windows.find((w) => w.label === label)?.left ?? Infinity;
  const keys = (a) => (room(a) ? [Math.min(left(a, "5 hours"), left(a, "Week")), left(a, "Week"), left(a, "5 hours")]
    : [held(a) ? backAt(a) : 0, 0, 0]);
  const byKeys = (a, b) => keys(a).reduce((d, k, i) => d || (k === keys(b)[i] ? 0 : k < keys(b)[i] ? -1 : 1), 0);
  return list.sort((a, b) => rank(a) - rank(b) || byKeys(a, b));
}
// A pin is tried first; a pinned account with no room falls through to the rule and keeps its pin (AccountPool.kt:84-85).
function ordered(p, c) {
  if (p.kind !== "plan") return p.accounts;
  const list = ruled(p, c), pinned = list.find((a) => a.id === c.pin);
  return pinned && room(pinned) ? [pinned, ...list.filter((a) => a !== pinned)] : list;
}
const serving = (p, c) => (p.kind === "plan" ? (c?.serving && p.accounts.find((a) => a.id === c.serving)) || ordered(p, c).find(room) : p.kind === "key" ? p.accounts.find((k) => k.has) : p.accounts[0]);
// Where the command goes when the account in use runs out, as splice picks it (AccountPool.followingLabel): the next
// login with room in the same order, or, when every other one is held, the one whose reset is nearest, served at that
// reset. A command with one account has nowhere to go.
function nextOf(p, c) {
  if (p.kind !== "plan" || !c?.following) return null;
  return p.accounts.find((a) => a.id === c.following) || null;
}

// A failed sign-in says its kind in a word or two where it was pressed, and splice's own sentence opens under the word,
// never on the card (refusalHtml): a bare red "In use" on a Sign in left him nothing to act on (Oct 27). splice names
// each failure's kind (failure_kind, ConsoleAccounts.kt LoginFailure); a kind it doesn't name reads Not completed. A
// file or config failure fails again until it's fixed, so it has no Try again.
const FAILS = {
  not_completed: { word: "Not completed", retry: true }, network: { word: "Network error", retry: true },
  file: { word: "File error" }, config: { word: "Config error" }, expired: { word: "Expired", retry: true },
  stopped: { word: "Stopped", retry: true }, cancelled: { word: "Cancelled", retry: true }, in_progress: { word: "In progress" },
  already_added: { word: "Already added", pulse: true }, in_use: { word: "In use", retry: true },
};
// A name splice takes, and one no other account of the command has (ClaudeAccountFolders.kt:199,
// OAuthAccountFiles.kt:264-266). On Claude: letters, digits, - and _, starting with a letter or digit, up to 64
// (ClaudeAccountFolders.kt:34, :195). Elsewhere: lowercase letters, digits, ., - and _, up to 48
// (AccountLabelPolicy, AccountSelection.kt:17), with primary, auto and a -quota ending reserved.
function nameError(p, a, v) {
  if (a.row?.ref) return refNameError(p, a, v);
  const reserved = p.id !== "claude" && (v === "primary" || v === "auto" || v.endsWith("-quota"));
  const shape = p.id === "claude" ? /^[A-Za-z0-9][A-Za-z0-9_-]{0,63}$/ : /^[a-z0-9][a-z0-9._-]{0,47}$/;
  // the word says it was refused, and what a name here may be opens under it, so the next try can be right
  if (reserved) return { word: "Invalid name", why: "primary, auto and names ending in -quota are splice's own" };
  if (!shape.test(v)) return { word: "Invalid name", why: p.id === "claude" ? "Letters, digits, - and _, starting with a letter or digit, up to 64"
    : "Lowercase letters, digits, ., - and _, starting with a letter or digit, up to 48" };
  if (p.accounts.some((x) => x !== a && x.name === v)) return "Name taken";
  return null;
}
// A login with a ref is named in splice's own names (AccountNameStore.kt): any words up to 48, never an email.
function refNameError(p, a, v) {
  if (v.length > 48) return "Too long";
  if (v.includes("@")) return "Can't hold an email";
  if (p.accounts.some((x) => x !== a && x.name === v)) return "Name taken";
  return null;
}
const namePath = (ref) => `/api/accounts/names/${encodeURIComponent(ref)}`;

// ---------- drawing ----------
const GLYPH = {
  grip: '<svg viewBox="0 0 10 18" fill="currentColor"><circle cx="2.5" cy="3" r="1.6"/><circle cx="7.5" cy="3" r="1.6"/><circle cx="2.5" cy="9" r="1.6"/><circle cx="7.5" cy="9" r="1.6"/><circle cx="2.5" cy="15" r="1.6"/><circle cx="7.5" cy="15" r="1.6"/></svg>',
  lock: '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.4"><rect x="4.5" y="10.5" width="15" height="10" rx="2"/><path d="M8 10.5V7.5a4 4 0 0 1 8 0v3"/></svg>',
  bell: '<svg class="glyph" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M6 16V11a6 6 0 0 1 12 0v5l1.5 2h-15z"/><path d="M10 20.5a2 2 0 0 0 4 0"/></svg>',
  stop: '<svg class="glyph" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M8.2 3h7.6L21 8.2v7.6L15.8 21H8.2L3 15.8V8.2z"/><path d="M8.5 12h7"/></svg>',
  eye: '<svg viewBox="0 0 24 24" width="16" height="16" fill="none" stroke="currentColor" stroke-width="2"><circle cx="12" cy="12" r="8.5"/><path d="M12 7.5V12l3 2"/></svg>',
  pin: '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linejoin="round"><path d="M9 3.5h6l-1.2 6 3.7 3.5h-11l3.7-3.5z"/><path d="M12 13v7.5"/></svg>',
  more: '<svg viewBox="0 0 24 24" fill="currentColor"><circle cx="5" cy="12" r="2"/><circle cx="12" cy="12" r="2"/><circle cx="19" cy="12" r="2"/></svg>',
  wait: '<svg class="wait" viewBox="0 0 40 40" aria-hidden="true"><circle cx="20" cy="20" r="15"/></svg>',
  // an expired token its command renews on its next run, on that command's chip
  renew: '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.2" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><path d="M19.5 12a7.5 7.5 0 1 1-2.2-5.3"/><path d="M19.5 4.5v4h-4"/></svg>',
};
/** A Day row splice is still reading: the count is not known, so it draws the ghost of the row that will land, never a
 *  word (Marcos, Oct 12: "we can do better than that reading indicator"). The value is the row's state, never printed. */
const DAY_READING = "reading";
const DAY_NOT_READ = "Not read";
// why a login's limits could not be read, from /api/accounts' last_read_failure.reason; a reason with no word shows none
const READ_WHY = { not_answering: "Provider not answering", refused: "Provider refused", signed_out: "Signed out", unsupported: "No limits to read" };
const money = dollars; // priced from the rate card, so an estimate; nothing charged reads "$0" (kit.js)
const cap = (v) => `$${Number.isInteger(v) ? v : v.toFixed(2)}`;
const waiting = () => `<span class="waitbox" role="status">${GLYPH.wait}<span class="sr">Signing in</span></span>`;
// the lock says what it means beside it: a bare red lock read as nothing until the footer under the stack (walk, Oct 13)
const locked = (pop = false, word = "Limit reached") => DRAW.lock(word, { pop });
// a held login is held until the last of its windows at their limit resets
const heldUntil = (a) => { const w = a.windows.filter(atLimit).sort((x, y) => y.left - x.left)[0]; return w?.resetMs != null ? `Limit reached until ${at(w.resetMs)}` : "Limit reached"; };

// the ring and the meter are the KIT's now (kit.js DRAW, console.css): this page's own drawings, lifted so that every
// page draws a share the way this one does. The arc is how much of the window is left; the bar is how much is used.
const ring = (w) => DRAW.ring(w.left / w.len);
const bar = (v, cls = "") => DRAW.meter(v, cls);
// A window still running says when it resets, with its ring. A window whose reported reset has already gone by says
// "Ended" and that time (Marlin's Accounts walk, Oct 12: "Reset" over an empty bar misread), with no ring: there is nothing left to fill, and "Week 93% used" beside an empty
// cell read as a live week at 93% when it was the ended week's figure (fin's word, Oct 10). A window that reported no
// reset at all keeps the empty cell, because there the page genuinely knows nothing to say.
// what the ring's arc is, said under the pointer: how long the window has left
const leftWord = (m) => (m < 60 ? `${Math.max(1, m)} min` : m < 2880 ? `${Math.floor(m / 60)} h${m % 60 ? ` ${m % 60} min` : ""}`
  : `${Math.floor(m / 1440)} days${Math.floor((m % 1440) / 60) ? ` ${Math.floor((m % 1440) / 60)} h` : ""}`);
const resets = (w) => (w.left != null ? `<span class="when" data-tip="${leftWord(w.left)} left">${ring(w)}Resets ${at(w.resetMs)}</span>`
  : w.rolledMs != null ? `<span class="when rolled">Ended ${at(w.rolledMs)}</span>` : "<span></span>");
// A window whose reset went by is closed, and Claude opens the next only on the next request, so it can stay closed for
// hours. Its row keeps its shape: the dashed track a window with no reading since its reset draws (console.css .bar.gone,
// never a solid empty track, which would read as nothing used), a dash for its figure, and "Ended" with the time in the
// reset's column, where the open rows say when they reset (Marlin's ruling, fin's words, Oct 12).
const closed = (w, sub = false) => `${bar(null, `gone${sub ? " sub" : ""}`)}<span class="pct">${DRAW.dash}</span>`
  + `<span class="when rolled">Ended${w.rolledMs != null ? ` ${at(w.rolledMs)}` : ""}</span>`;
// The budget day refills at the DAEMON's midnight, so its strip says that one word: a day name and "12:00 AM" read
// as a weekly date to both walkers (fin, Oct 10). The plan windows keep their times, which really move. Read from a
// browser in another zone the daemon's midnight is not midnight there, so the word is only used when it is one.
function refills(day) {
  const at0 = new Date(day.resetMs);
  const midnightHere = at0.getHours() === 0 && at0.getMinutes() === 0;
  return `<span class="when" data-tip="${leftWord(Math.max(1, day.left))} left">${ring(day)}Resets ${midnightHere ? "midnight" : at(day.resetMs)}</span>`;
}

// A WINDOW'S LABEL IS THE DOOR TO WHAT USED IT: the 5 hours, the Week and the Month open Usage on that window, filtered to this
// login on the command the rail shows (usage.html?win=&account=head|label, the address Usage reads), so a week at 92% leads to
// the projects and agents that spent it. A model's slice of the week opens that week filtered to the model as well.
const USAGE_WIN = { "5 hours": "5h", Week: "7d", Month: "30d" };
// The models each command declares, so a slice named by its family ("Fable") opens Usage on the model id that family is on
// this command: Usage's model filter takes an id exactly.
let MODEL_IDS = new Map();
function modelIdOn(head, family) {
  const want = String(family ?? "").toLowerCase(), words = (t) => String(t ?? "").toLowerCase().split(/[^a-z0-9]+/);
  return (MODEL_IDS.get(head) || []).find((m) => words(m.id).includes(want) || words(m.label).includes(want))?.id ?? null;
}
// A login the daemon names carries a ref, which is what Usage matches its requests and its name by (usage.js planRowOf);
// one without a ref goes by its label on that command
const usageDoor = (a, c) => (c ? (label, family = null) => {
  if (!USAGE_WIN[label]) return null;
  const q = new URLSearchParams({ win: USAGE_WIN[label], account: `${c.head}|${a.row?.ref ?? labelOn(a, c.head)}` }), id = family ? modelIdOn(c.head, family) : null;
  if (id) q.set("model", id);
  return `usage.html?${q}`;
} : null);
// the login a line names, as a door to its card on this board with the socket it has on the rail (a move, the Next row)
const loginDoor = (name, id, sock) => (id ? `<button class="mlogin" type="button" data-act="goto" data-a="${esc(id)}" title="Show ${esc(name)}'s card">`
  + `${sock ? `<span class="msock" aria-hidden="true">${sock}</span>` : ""}<b class="nname">${esc(name)}</b></button>` : `<b class="nname">${esc(name)}</b>`);
function windowsHtml(a, next = null, left = null, door = null, nextSock = 0) {
  // a held login says so on its own line under the header, where a narrow card wraps it instead of cutting its name
  let rows = held(a) ? `<span class="heldline">${heldUntil(a)}</span>` : "";
  for (const w of a.windows) {
    const full = atLimit(w), to = door?.(w.label);
    // each bar is marked from ITS OWN reading, never the account's: a current 5-hour window beside a week
    // whose own reset has passed drew that week's spent figure as today's
    rows += (to ? `<a class="label wdoor" href="${to}" aria-label="${w.label}: what used it, on Usage">${w.label}${DRAW.go}</a>` : `<span class="label">${w.label}</span>`)
      + (ended(w) ? closed(w)
        : `${bar(Math.min(w.used, 100), `${full ? "full" : ""} ${w.stale ? "stale" : ""}`)}`
          + `<span class="pct ${full ? "limit" : ""}">${w.used}%<span class="used">used</span></span>` + resets(w));
    if (w.label === "Week" && a.models) {
      // A model's figure is a slice of the account week above it, read in the same observation, so once that week has
      // ended its slices have too — whether or not the model itself reported a reset (hitstop, Oct 10).
      for (const m of a.models) rows += (door?.("Week", m.name) ? `<a class="label sub wdoor" href="${door("Week", m.name)}" aria-label="${esc(m.name)}: what used this week, on Usage">${esc(m.name)}${DRAW.go}</a>` : `<span class="label sub">${esc(m.name)}</span>`)
        + (ended(m) || ended(w) ? closed(ended(m) ? m : w, true)
          : `${bar(m.used, `sub ${m.used >= 100 ? "full" : ""} ${m.stale ? "stale" : ""}`)}`
            + `<span class="pct">${m.used}%<span class="used">used</span></span>` + resets(m));
    }
  }
  if (a.staleAt != null) rows += `<span class="stale-at">${GLYPH.eye}Read ${at(a.staleAt)}</span>`;
  if (next) { // a held next is the one serving at its reset: its lock and that reset, on the windows' own columns, drawn
    // and not said again in a sentence under them (the lock says held, the ring and the time say until when)
    const w = held(next) && next.windows.filter(atLimit).sort((x, y) => y.left - x.left)[0];
    // its name is the door to its card, with the socket it stands at on the rail
    rows += `<span class="next"><span class="label">Next</span><span class="who">${loginDoor(next.name, next.id, nextSock)}</span>${w ? `<span class="nextlock" title="Limit reached until ${at(w.resetMs)}">${locked(false, "")}</span>${resets(w)}` : ""}</span>`;
  } else if (left) {
    // the last login with room in a pool of several: no other takes over at the next plan limit, until the first held one
    // comes back (lastLeftOf), drawn on the Next row where a next would stand (splice-lead, Oct 13): an empty socket where
    // its name would be, the lock, and the time the first held login is back
    const said = `No other login has room${left.untilMs ? ` until ${at(left.untilMs)}` : ""}`;
    rows += `<span class="next none" title="${said}"><span class="label">Next</span><span class="who"><i class="nosock" role="img" aria-label="${said}"></i></span>`
      + `<span class="nextlock">${locked(false, "")}</span>${left.untilMs ? `<span class="when rolled">${at(left.untilMs)}</span>` : "<span></span>"}</span>`;
  }
  return `<div class="windows">${rows}</div>`;
}

// ---------- a command's day ----------
// ONE STRIP PER COMMAND, ONE COLUMN WIDE WHEREVER IT STANDS, ON THE CARDS' OWN LEFT AND RIGHT EDGES. Row one: "Day", the day's
// figure at the hero size right-aligned in one cell, so the words after it start at one x on every strip, the budget's act at the
// row's end, and when the day resets. Under it each quantity is drawn on a scale the whole board shares: the requests as a bar
// (the busiest day fills the track; what had no price and what failed are cut from it in their own colours), the dollars as a bar
// (the dearest day fills the track), then the day hour by hour on one width per hour. The command's tab names the strip, because
// one provider can serve several commands; where a plan provider's commands share one rail, the tab is pressed to show that
// command's order: pick is { p, on } there, and null everywhere else.
// the busiest day's requests and dollars on the board, which fill their tracks; set once the board is read (readAll)
let REQ_MAX = 1, USD_MAX = 1;
// what a strip's two bars hold: the requests the day counted (and the share of them with no price and the share that failed), and
// the dollars on the shared scale with the words that name them. A budget's dollars are its own bar, against the cap.
function dayBars(c, split) {
  if (c.local || c.unread || c.turns == null) return { req: null, usd: null };
  const np = split ? (split.allPlan ? 0 : split.keyUnpriced) : c.onPlan ? 0 : c.unpriced;
  const req = c.turns > 0 ? { total: c.turns, np: Math.min(np, c.turns), fail: Math.min(c.failed, c.turns) } : null;
  let usd = null;
  if (split?.allPlan) usd = split.planUsd > 0 ? { v: split.planUsd, word: "at API prices" } : null;
  else if (!c.budget) { const v = split ? split.keyUsd : c.spent; usd = v > 0 ? { v, word: "at the rate card" } : null; }
  return { req, usd };
}
const isIdle = (c) => !!c.hours && !c.local && c.unread !== DAY_READING && !c.unread && !c.budget && !c.turns && !c.opening;
function meterHtml(c, pick = null) {
  const day = { ...dayReset(), len: LEN.Day };
  const tag = pick ? `<button class="chip tag" data-act="lane" data-p="${pick.p.id}" data-c="${esc(c.cmd)}" aria-pressed="${pick.on}">${esc(c.cmd)}</button>`
    : `<span class="chip tag">${esc(c.cmd)}</span>`;
  const reading = c.unread === DAY_READING, split = c.split && !c.unread ? c.split : null;
  const fill = capFill(c, split), keyed = !!c.hours && !c.local; // the hours are drawn, so the words' swatches name their colours
  // the budget editor opens between the figure and the hours, so the day he caps stays drawn under it
  const editing = ui.editor && ui.editor.c === c.cmd, act = editing ? "" : budgetAct(c, split, reading);
  // the day is a door to exactly its requests on Requests, every project, from the hour its figures count from
  // (requests.js applyAddress reads cmd and from); a day with none, or one not read yet, has nothing to open
  const opens = !c.local && !reading && !c.unread && c.turns > 0 && DAY_FROM != null;
  const label = opens ? `<a class="label door" href="${requestsAt(c, { from: DAY_FROM })}" aria-label="Today's ${esc(c.cmd)} requests">Day${DRAW.go}</a>`
    : `<span class="label">Day</span>`;
  // a day with no request has nothing to draw hour by hour: its strip is one row, its chip, "No requests" and when the day
  // resets, and it grows into the hours the moment a request lands
  const idle = isIdle(c);
  const hero = heroHtml(c, split, reading);
  const bars = reading || idle || c.local ? "" : barsHtml(c, split, fill, keyed, editing);
  const hours = idle || c.local ? "" : hoursHtml(c);
  return `<div class="meter day${editing ? " spend" : ""}${idle || c.local ? " idle" : ""}" data-meter="${esc(c.cmd)}"${reading ? ' aria-busy="true"' : ""}>`
    + `${tag}${label}<div class="dayline">${hero}${act}</div>${refills(day)}${bars}${editing ? editorHtml(c) : ""}${hours}</div>`;
}
// the day's figure at the hero size, and its denominator in muted words on its baseline: "of $10" where a budget caps the day,
// else what the figure counts. A plan day has no cap or total splice can read, so its figure carries only its unit.
function heroHtml(c, split, reading) {
  const fig = (v, cls = "") => `<b class="ads-fig${cls}">${v}</b>`, unit = (t) => `<span class="ads-unit${/^of /.test(t) ? " ads-of" : ""}">${t}</span>`;
  const ghost = (words = "") => `<span class="ads-fig ads-ghost">${GHOST.fig(5.6)}</span>${words ? unit(words) : unit(GHOST.bar(9.5))}`;
  const none = unit("<em>No requests</em>"), over = pastCap(c) ? " over" : "";
  const asked = (n, words) => fig(n0(n)) + unit(`${n === 1 ? "request" : "requests"} ${words}`);
  if (c.local) return "";
  if (reading) return ghost();
  if (c.unread) {
    const n = notRead(c);
    return `<span class="ads-fig dash">${n.fig}</span>${unit(`${DAY_NOT_READ}${n.why}`)}`;
  }
  if (split?.allPlan) return split.plan ? asked(split.plan, "on your plan") : none;
  if (split) {
    const spent = c.budget ? c.spent : split.keyUsd, priced = split.keyPriced;
    if (c.budget && spent == null) return ghost(`of ${cap(c.budget.cap)}`);
    // a day of failed requests spent nothing and says so, "$0 of $5", never only the cap (Oct 12)
    const counted = c.budget ? spent != null && (spent || priced || c.failed) : priced && spent != null;
    if (counted) return fig(money(spent), over) + unit(c.budget ? `of ${cap(c.budget.cap)}` : split.plan || split.keyUnpriced || split.old ? `for ${reqs(priced)}` : "");
    if (split.plan) return asked(split.plan, "on your plan");
    if (split.keyUnpriced) return asked(split.keyUnpriced, "with no price");
    return c.turns > 0 ? asked(c.turns, "counted") : none;
  }
  if (c.spent == null) return ghost(c.budget ? `of ${cap(c.budget.cap)}` : "");
  // a day with no request yet says so in muted ink, never a figure of nothing
  if (!c.priced && !c.unpriced && !c.failed && !c.budget) return none;
  // with every request priced, the figure alone; with some, the figure for the priced ones; with none, only the count, which leads
  const figure = !(c.unpriced && !c.priced);
  if (!figure) return asked(c.unpriced, c.onPlan ? "on your plan" : "with no price");
  const share = (c.unpriced || c.failed) && c.priced ? `for ${reqs(c.priced)}` : "";
  return fig(money(c.spent), over) + unit(c.budget ? `of ${cap(c.budget.cap)}` : share);
}
// The rows under the figure: REQUESTS and DOLLARS, each a bar on its own scale and the words that say what its parts are. The
// requests' parts are told by a swatch that matches the hours drawn below (no price muted, failed in the limit red); a budget's
// dollars are the spend against its cap, with the cap a mark across the track.
function barsHtml(c, split, fill, keyed, editing) {
  const { req, usd } = dayBars(c, split);
  const k = (w) => `<span class="label ads-k">${w}</span>`, pct = (n, of) => (n > 0 ? +((n / of) * 100).toFixed(3) : 0);
  let rows = "";
  if (c.turns != null) {
    const total = pct(req?.total ?? 0, REQ_MAX), np = req ? pct(req.np, req.total) * total / 100 : 0, fl = req ? pct(req.fail, req.total) * total / 100 : 0;
    const parts = req ? [["ok", total - np - fl, 0], ["np", np, total - np - fl], ["fl", fl, total - fl]].filter((x) => x[1] > 0 || (x[0] === "ok" && total === 0))
      .map(([cls, v, at]) => `<b class="${cls}" data-v="${+v.toFixed(3)}" style="left:${+at.toFixed(3)}%"></b>`).join("") : "";
    const words = [];
    if (req) {
      if (split && !split.allPlan) {
        if (split.plan) words.push(`${n0(split.plan)} on your plan`);
        if (split.keyUnpriced) words.push(`${keyed ? sw("np") : ""}${n0(split.keyUnpriced)} with no price`);
        if (split.old) words.push(`${n0(split.old)} earlier, unsorted`);
      } else if (!split && c.unpriced) words.push(`${keyed && !c.onPlan ? sw("np") : ""}${noPrice(c)}`);
      if (c.failed) words.push(`${keyed ? sw("fl") : ""}${failedDoor(c, c.failed)}`);
    }
    rows += `${k("Requests")}<div class="bar ads-req">${parts}</div><span class="ads-words">${words.map((w) => `<span class="part">${w}</span>`).join("")}</span>`;
  }
  if (!editing) {
    if (fill) rows += `${k("Dollars")}${capHtml(c, fill)}`;
    else if (usd) rows += `${k("Dollars")}${bar(Math.max(pct(usd.v, USD_MAX), 0.0001), "ads-usd")}<span class="ads-words"><b class="ads-mo">${money(usd.v)}</b><span class="ads-mute">${usd.word}</span></span>`;
  }
  return rows;
}
// a count that leads its row is the row's figure, in the figure's weight; after a figure it is one of the words
const leadNum = (n, lead) => (lead ? `<b class="lead">${n0(n)}</b>` : n0(n));
// a door to one command's requests on Requests, every project, over a stretch of today (requests.js applyAddress)
const requestsAt = (c, { from, to = null, outcome = null }) => `requests.html?${new URLSearchParams({ project: "", cmd: c.cmd, from: String(from),
  ...(to != null ? { to: String(to) } : {}), ...(outcome ? { outcome } : {}) })}`;
const n0 = (n) => n.toLocaleString("en-US");
const reqs = (n) => `${n0(n)} ${n === 1 ? "request" : "requests"}`;
// A plan's turns read "on your plan": the plan covered them, and "with no price" would read as splice missing data.
// Every other turn with no figure is a model with no rate card (fin's words, Marlin, Oct 10).
const noPrice = (c, lead = false) => `${leadNum(c.unpriced, lead)} ${c.unpriced === 1 ? "request" : "requests"} ${c.onPlan ? "on your plan" : "with no price"}`;
// A failed request is counted here only when it reported no usage, so nothing was charged for it: one that reported
// usage is priced into the figure (EconomicsBuckets: failed_turns counts noPrice && failed). The word is the one
// Requests' Outcome switch uses (fin, Oct 12), in the limit red the hours draw failures in, and the count is the door
// to exactly those requests; that they cost nothing (marlin p229, Oct 12: a count alone left him unsure) is said on
// the door, which is where he asks it.
const failedDoor = (c, n) => (DAY_FROM == null ? `<span class="failed-n">${n0(n)} failed</span>`
  : `<a class="failed-n" href="${requestsAt(c, { from: DAY_FROM, outcome: "fail" })}">${n0(n)} failed</a>`);
// the swatch that ties a phrase to its colour in the hours drawn under it: no price muted, failed in the limit red
const sw = (k) => `<i class="sw ${k}" aria-hidden="true"></i>`;
// Not read, and why when splice kept a sentence for it, behind the word: an hour it could not read is drawn hatched on
// the day itself, so only a reason the drawing cannot show opens here. Where no day on the board was read, the page
// head says so once (dayState) and each strip keeps only the dash where its figure goes. The dash is the figure; the
// sentence, once opened, follows the word under the strip's figure.
function notRead(c) {
  const why = c.unreadWhy || DAY_UNREAD?.why || "";
  if (!why) return { fig: `<span class="nr-day">${DRAW.unread.fig}</span>`, why: "" };
  const open = ui.why.has(c.cmd);
  return { fig: DRAW.disc({ open, act: "why", data: ` data-c="${esc(c.cmd)}" aria-label="Not read, why"`, inner: `<span class="nr-day">${DRAW.unread.fig}</span>` }),
    why: open ? `<span class="state why${ui.enter === "why" ? " unfold" : ""}">${esc(why)}</span>` : "" };
}
// a day at or past its budget: its figure in the limit red, as its bar's overage is
const pastCap = (c) => !!c.budget && c.spent != null && c.spent >= c.budget.cap;

// What the budget's meter fills with: today's spend against the cap, the spend's ghost while splice still reads it, or
// nothing where no request today had a price to count against it. A budget on a command all on a plan caps nothing, so
// its strip draws only the hours, as one with no budget does.
function capFill(c, split) {
  if (!c.budget || c.local || split?.allPlan) return null;
  if (c.spent == null) return { pending: true };
  if (split) return c.spent || split.keyPriced || c.failed ? { usd: c.spent } : null;
  return c.unpriced && !c.priced ? null : { usd: c.spent };
}
// SPEND AGAINST THE CAP, on one track scaled to the larger of the two, so a day past its budget draws how far past: the
// cap is the mark across the track, what stayed under it fills in the provider's colour and what went over in the limit
// red, and the percent is never held at 100 (a Warn budget lets the day run on past it). [capUsd] is the cap drawn: the
// budget's own, or the amount typed in its editor.
// [fixed] is the editor's track: it runs from nothing to the largest of $100, the cap and the spend, so a preset moves the cap's
// line along it, the presets stand on it as ticks, and the line names the amount it marks
const PRESETS = [5, 10, 25, 50, 100];
function capHtml(c, f, capUsd = c.budget.cap, fixed = false) {
  if (f.pending) return `<div class="capbox">${GHOST.track()}</div><span class="pct">${GHOST.fig(4.2)}</span>`;
  const usd = f.usd || 0, scale = fixed ? Math.max(PRESETS.at(-1), capUsd, usd) : Math.max(capUsd, usd), at = (v) => +((v / scale) * 100).toFixed(2);
  const fill = usd > capUsd ? `<b class="past" data-v="${at(usd)}"></b><b data-v="${at(capUsd)}"></b>` : `<b data-v="${at(usd)}"></b>`;
  const marks = fixed ? `${PRESETS.filter((v) => v <= scale).map((v) => `<i class="tick" style="left:${at(v)}%"></i>`).join("")}<span class="capnum" style="left:${at(capUsd)}%">${cap(capUsd)}</span>` : "";
  return `<div class="capbox${fixed ? " scaled" : ""}"><div class="bar spend">${fill}</div>${marks}<i class="capline" style="left:calc(${at(capUsd)}% - .08rem)"></i></div>`
    + `<span class="pct${usd >= capUsd ? " limit" : ""}">${Math.round((usd / capUsd) * 100)}%<span class="used">used</span></span>`;
}
// what a budget on this command would cap today: the key's money, which a plan never adds to; null where nothing is
// capped or the day is not read
const cappable = (c) => (c.local || c.unread || c.split?.allPlan ? null : c.budget ? c.spent : c.split ? c.split.keyUsd : c.spent);
// The act at the right of the first row: Set budget, or the budget he set, its glyph saying Warn or Block. The cap is on
// the act only where the row's own words do not say it already ("$1.50 of $5").
function budgetAct(c, split, reading) {
  const d = `data-act="edit-budget" data-c="${esc(c.cmd)}"`;
  if (c.budget) {
    const said = !c.local && !split?.allPlan && capFill(c, split);
    return `<button class="act quiet bud" ${d} aria-label="Budget ${cap(c.budget.cap)}, ${c.budget.block ? "Block" : "Warn"}">${c.budget.block ? GLYPH.stop : GLYPH.bell}Budget${said ? "" : ` ${cap(c.budget.cap)}`}</button>`;
  }
  // a command whose every account is on a plan has nothing a budget caps, since a budget caps what a key was charged
  // (BudgetEnforcement.kt:34), so its row offers none. A day not read cannot say which it is: the split splice last gave
  // for it decides (SPLIT_SEEN), and a plan command it never gave one for offers none until its day is read.
  const allPlan = c.split ? c.split.allPlan : c.unread === DAY_NOT_READ ? SPLIT_SEEN.get(c.head) ?? c.kind === "plan" : false;
  if (allPlan || c.local) return "";
  if (reading) return c.kind === "key" ? `<span class="act-ghost">${GHOST.bar(6.6)}</span>` : "";
  return `<button class="act quiet" ${d}>Set budget</button>`;
}
// THE DAY HOUR BY HOUR, ON ONE SCALE FOR THE WHOLE BOARD (HOUR_MAX, the busiest hour of any strip), so a quiet command
// reads as quiet beside a busy one. One sunk track runs from the day's start to its reset; each hour gone by is a column
// filled from the foot with what went through in the provider's colour, what had no price muted and what failed in the
// limit red, and is a door to exactly that hour's requests on Requests. An hour splice could not read is hatched, an
// hour with no requests is the flat track, and the hours to come are the track's outline past the now line. The
// strip's busiest hour is a line across the track at its height, its count at the line's end, and 12 AM, 6 AM, 12 PM and 6 PM stand under their hours. In a zone
// whose midnight is not on a UTC hour, the hour that holds the day's start opens the track hatched in the provider's
// colour: its requests belong to two days, so they are kept out of the figure, and what they were is on the hour. While
// the day is read, the row is its ghost.
const HOUR_MS = 3600000;
const hourWord = (ms) => new Date(ms).toLocaleTimeString("en-US", { hour: "numeric" });
const TICKS = new Set([0, 6, 12, 18]);
const openingWord = (o, at) => `${hourWord(at)} · ${o.usd ? money(o.usd) : `${n0(o.unpriced)} ${o.unpriced === 1 ? "request" : "requests"}`} across midnight, not counted`;
function hoursHtml(c) {
  if (c.local) return "";
  if (!c.hours) return `<div class="dayrun">${GHOST.track()}</div>`;
  const now = Date.now(), open = c.opening && !c.allUnread ? c.opening : null, first = open ? 1 : 0;
  const t0 = c.hours[0].at - first * HOUR_MS, n = c.hours.length + first;
  const tall = (k) => `height:max(.14rem, ${((k / HOUR_MAX) * 100).toFixed(2)}%)`;
  const peak = c.allUnread ? null : c.hours.reduce((m, h) => (!h.unread && h.at <= now && h.all > (m?.all ?? 0) ? h : m), null);
  // each hour gone by carries what it says when the pointer or the arrow keys reach it (sayHour)
  const cols = c.hours.map((h) => {
    if (h.at > now) return "<i></i>";
    if (h.unread || c.allUnread) return `<i class="unread" data-said="${hourWord(h.at)} · ${DAY_NOT_READ}"></i>`;
    if (!h.all) return `<i data-said="${hourWord(h.at)} · No requests"></i>`;
    const said = `${hourWord(h.at)} · ${[reqs(h.all), h.none ? `${n0(h.none)} with no price` : "", h.fail ? `${n0(h.fail)} failed` : ""].filter(Boolean).join(", ")}`;
    return `<a class="hr" href="${requestsAt(c, { from: h.at, to: h.at + HOUR_MS })}" tabindex="-1" aria-label="${said}" data-said="${said}">`
      + `${h.ok ? `<b class="ok" style="${tall(h.ok)}"></b>` : ""}${h.none ? `<b class="np" style="${tall(h.none)}"></b>` : ""}`
      + `${h.fail ? `<b class="fl" style="${tall(h.fail)}"></b>` : ""}</a>`;
  });
  if (open) cols.unshift(`<i class="opening" data-said="${openingWord(open, t0)}"><b style="${tall(open.turns)}"></b></i>`);
  const ticks = [];
  for (let i = 0; i < n; i++) {
    const at = t0 + i * HOUR_MS, hour = new Date(at).getHours();
    if (TICKS.has(hour) && (i === 0 || new Date(at - HOUR_MS).getHours() !== hour)) ticks.push(`<span class="tick" style="--i:${i}"><b>${hourWord(at).replace(" ", "\u2009")}</b></span>`);
  }
  const top = peak ? `<span class="peak" style="--y:${Math.min(100, (peak.all / HOUR_MAX) * 100).toFixed(2)}"><i></i><b>${n0(peak.all)}</b></span>` : "";
  const f = Math.min(1, Math.max(0, (now - t0) / (n * HOUR_MS)));
  // the arrow keys walk the hours from the track itself, one stop in the tab order however many hours it holds
  return `<div class="dayrun" role="group" tabindex="0" aria-label="Requests by hour since ${hourWord(t0)}" style="--n:${n};--at:${f.toFixed(4)}">`
    + `<span class="hours">${cols.join("")}</span><i class="now" aria-hidden="true"></i><span class="marks" aria-hidden="true">${top}${ticks.join("")}</span>`
    + `<span class="hourat" role="status"></span></div>`;
}
// The budget editor, between today's figure and its hours: the amount, five presets, Warn or Block, the cap drawn against
// today's spend as he types it, and the acts. A save splice refused says so beside the acts with its reason one press
// away, and the editor shakes once.
// Two fixed rows, so the editor never wraps its parts onto a third line: the amount, its presets and Warn or Block; then
// the cap on today's spend with the acts at the row's end.
function editorHtml(c) {
  const ed = ui.editor, spend = cappable(c);
  const presets = PRESETS.map((v) => `<button data-act="preset" data-v="${v}" aria-pressed="${ed.cap === v}">$${v}</button>`).join("");
  const preview = `<span class="edpreview" aria-hidden="true">${spend == null ? "" : capHtml(c, { usd: spend }, ed.cap, true)}</span>`;
  return `<div class="editor${ed.err && ui.shook === "editor" ? " failed" : ""}${ui.enter === "editor" ? " enter" : ""}"><div class="edrow"><span class="money-in">$<input class="field" id="cap-in" inputmode="decimal" value="${ed.cap}" aria-label="Budget cap"></span>`
    + `<span class="presets">${presets}</span>`
    + `<span class="mode"><button data-act="bmode" data-v="warn" aria-pressed="${!ed.block}">${GLYPH.bell}Warn</button><button data-act="bmode" data-v="block" aria-pressed="${ed.block}">${GLYPH.stop}Block</button></span></div>`
    + `<div class="edrow">${preview}<span class="edacts"><button class="act primary" data-act="save-budget" data-c="${esc(c.cmd)}">Set budget</button>`
    + `${c.budget ? `<button class="act quiet" data-act="remove-budget" data-c="${esc(c.cmd)}">Remove budget</button>` : ""}<button class="act quiet" data-act="cancel-budget">Cancel</button>`
    + `${ed.err ? refusalHtml(ed.err, "editor") : ""}</span></div>${ed.err ? reasonHtml(ed.err, "editor") : ""}</div>`;
}
// the editor's drawing of its cap against today's spend, redrawn in place as he types, so the field keeps its focus
function previewCap(field) {
  const box = field.closest(".editor")?.querySelector(".edpreview"), { c } = findCmd(ui.editor?.c);
  const spend = c && cappable(c);
  if (!box || spend == null || !(ui.editor.cap > 0)) return;
  // the cap's line and its amount travel from where they stood to where the new cap puts them
  const before = ["capline", "capnum"].map((k) => box.querySelector(`.${k}`)?.style.left);
  box.innerHTML = capHtml(c, { usd: spend }, ui.editor.cap, true);
  DRAW.paint(box);
  if (window.matchMedia("(prefers-reduced-motion: reduce)").matches) return;
  ["capline", "capnum"].forEach((k, i) => {
    const el = box.querySelector(`.${k}`), to = el?.style.left;
    if (!el || !before[i] || before[i] === to) return;
    el.style.transition = "none"; el.style.left = before[i]; void el.offsetWidth;
    el.style.transition = "left var(--t-snap) var(--e-out)"; el.style.left = to;
  });
}

// ---------- sign-in: one element wherever an account signs in, with the step its provider really asks for ----------
// The step comes from splice's own answer: a device code to type on the provider's page (Kimi, Muse), a code pasted back
// (Claude Code's own sign-in), or a page that finishes the sign-in by itself (LoginRoutes.kt:56).
const CODE_HINT = `<span class="code-hint">Enter this code on the sign-in page</span>`; // the act on the code, where Open sign-in goes (fin)
function signingHtml(target) {
  const s = ui.signin[target];
  const cancel = `<button class="act quiet" data-act="cancel-signin" data-t="${target}">Cancel</button>`;
  if (s.state === "fail") {
    const f = s.fail, err = { word: f.word, why: s.why }, key = `signin:${target}`;
    const retry = f.retry ? `<button class="act primary" data-act="retry-signin" data-t="${target}">Try again</button>` : "";
    return `${refusalHtml(err, key)}${retry}${cancel}${reasonHtml(err, key)}`;
  }
  const open = s.url ? `<button class="act" data-act="open-signin" data-t="${target}">Open sign-in</button>` : "";
  if (s.by === "code") return `${waiting()}${s.code ? `<button class="usercode${s.swapped ? " swap" : ""}" data-act="copy-code" data-t="${target}">${esc(s.code)}</button>${CODE_HINT}` : ""}${open}${cancel}`;
  if (s.by === "paste") return `${waiting()}${open}<input class="field code-in" data-t="${target}" placeholder="Paste code" aria-label="Paste code" autocomplete="off" spellcheck="false">${cancel}`;
  return `${waiting()}${open}${cancel}`;
}
// how: { start: the POST that begins it, poll: the head its status is read under, native: a Claude Code sign-in }
function startSignin(target, how, spot = null) { // spot: the one card it shows on
  ui.signin[target] = { how, spot, by: how.native ? "paste" : "browser" };
  waitSignin(target);
}
const stopTimers = (s) => clearTimeout(s.timer);
async function waitSignin(target) {
  const s = ui.signin[target];
  stopTimers(s);
  Object.assign(s, { state: "wait", code: null, url: null, id: null, gen: (s.gen || 0) + 1 });
  render();
  const gen = s.gen, res = await API.post(s.how.start, s.how.body || {});
  if (ui.signin[target] !== s || s.gen !== gen) return;
  if (!res.ok || !res.body?.id) { failSignin(target, res.body); return; }
  s.id = res.body.id; seen(target, read(s, res.body));
}
// An add's answer carries its sign-in inside it (AddViews.kt:63); a command's sign-in answers as itself.
const read = (s, body) => (s.how.watch ? body?.sign_in || {} : body);
function seen(target, st) { // one status answer: show its step, finish, fail, or ask again in a second
  const s = ui.signin[target];
  if (!s || s.state !== "wait") return;
  if (st.state === "failed") { failSignin(target, st); return; }
  if (st.state === "signed_in" || st.state === "live_after_restart") { finishSignin(target); return; }
  const url = st.verification_uri || st.browser_url || null, by = st.user_code ? "code" : s.how.native ? "paste" : "browser";
  const changed = url !== s.url || by !== s.by || (st.user_code || null) !== s.code;
  s.swapped = !!(s.code && st.user_code && st.user_code !== s.code); // a device code that expired is replaced (DeviceLoginFlow.kt:88-93)
  Object.assign(s, { url, by, code: st.user_code || null });
  if (changed) { render(); s.swapped = false; }
  const gen = s.gen;
  s.timer = setTimeout(async () => {
    const res = await API.get(s.how.watch || `/api/auth/${encodeURIComponent(s.how.poll)}/login/${encodeURIComponent(s.id)}`);
    if (ui.signin[target] !== s || s.gen !== gen) return;
    if (!res.ok) { failSignin(target); return; }
    seen(target, read(s, res.body));
  }, 1000);
}
function failSignin(target, st = null) {
  const s = ui.signin[target];
  stopTimers(s);
  s.state = "fail"; s.fail = FAILS[st?.failure_kind] || FAILS.not_completed;
  // why, in splice's words: the sign-in's own failure_reason, or the error a start splice refused answered with
  const why = st?.failure_reason ?? st?.error;
  s.why = typeof why === "string" ? why.trim() : "";
  refused(`signin:${target}`);
  if (s.fail.pulse && st?.label) { // Already added: the card already there for that account pulses once
    const there = data.flatMap((p) => p.accounts).find((a) => a.row?.label === st.label && a.row.heads.includes(s.how.poll));
    if (there) ui.pulse = there.id;
  }
  render();
}
async function finishSignin(target) {
  const s = ui.signin[target];
  stopTimers(s);
  if (s.how.done && !(await s.how.done())) return; // an add saves after its sign-in; a refused save says so in place
  delete ui.signin[target];
  if (target === "prov") ui.addProv = null;
  await refresh(s.spot ? s.spot.split("/")[1] : "new");
  if (s.how.name) await nameAdded(s.how);
}
function cancelSignin(target) {
  if (ui.signin[target]) stopTimers(ui.signin[target]);
  delete ui.signin[target];
  if (target === "prov") dropAdd();
  render();
}
function openSignin(target) {
  const s = ui.signin[target];
  if (s.url) window.open(s.url, "_blank", "noopener");
  if (s.by === "paste") board.querySelector(`.code-in[data-t="${target}"]`)?.focus();
}
async function submitCode(field) {
  if (!field.value.trim() || field.readOnly) return;
  field.readOnly = true;
  const s = ui.signin[field.dataset.t];
  if (!s?.id) return;
  const res = await API.post(`/api/auth/${encodeURIComponent(s.how.poll)}/login/${encodeURIComponent(s.id)}/code`, { code: field.value.trim() });
  if (!res.ok) { failSignin(field.dataset.t); return; }
  seen(field.dataset.t, res.body);
}
// How an account signs in again: Claude Code's own sign-in through its place, any other through its command's login
// under its own name (ClaudeLoginRoutes.kt:20, LoginRoutes.kt:24).
function howFor(p, a, c) {
  const head = c ? c.head : a.row.heads[0];
  if (a.row?.login_place) return { start: `/api/claude-logins/${encodeURIComponent(a.row.login_place.id)}/login`, body: { label: null }, poll: head, native: true };
  // its name on that command; the first account of a ChatGPT, Grok, Kimi or Muse command is "primary" there
  const label = a.row?.account_labels?.[head] || a.row?.label;
  // A removed primary has no file to sign in to under its reserved name, so the login refuses before the browser
  // ever opens (OAuthAccountFiles.kt:181). Signing in with NO name is what creates the primary, so a signed-out
  // primary sends none. A named account still sends its name: signing it in nameless would move it into the
  // primary slot (re-review, Oct 10).
  const reserved = label === "primary" && !a.row?.credential_present;
  return { start: `/api/auth/${encodeURIComponent(head)}/login`, body: label && !reserved ? { label } : {}, poll: head };
}
// ---------- an account ----------
// serves: every command this account serves now, each riding it as a chip. Use now pins it on the rail's command.
function slotHtml(p, c, a, serves) {
  // the pin says what pressing it does, at rest and on touch: both walkers clicked a bare pin on a guess (p153, p154)
  const pin = c && c.pin === a.id ? `<button class="act quiet small pin" data-act="unpin" data-p="${p.id}" data-c="${esc(c.cmd)}">${GLYPH.pin}Unpin</button>` : "";
  const chips = serves.map((x) => `<span class="chip" data-key="chip:${esc(x.cmd)}"><i></i>${esc(x.cmd)}</span>`).join("");
  // A SERVER ON THIS COMPUTER WEARS ITS LAMP: unlit when it does not answer, as Sessions draws a session that is not
  // running, and its command's chip with the live dot when it does. "Not answering" is the word (fin, Oct 10): it is
  // what the watch measures, no reply at the address in time (LocalRuntimeReach.kt), and what splice status prints.
  if (a.down) return `${DRAW.lamp("")}<span class="state">Not answering</span>`;
  // a server the watch has no reading for yet: the unlit lamp, and the state line's ghost until the watch reads it
  if (a.unread) return `${DRAW.lamp("")}${GHOST.state()}`;
  // a server that answers wears its lamp lit and says so in its own word, where one that does not wears it unlit
  if (p.kind === "local") return `${DRAW.lamp("lmc-lit")}<span class="state lmc-live">Answering</span>`;
  // a switch or an unpin splice refused, on the card it was pressed on, until the next press
  const err = c && ui.slotErr && ui.slotErr.a === a.id && ui.slotErr.c === c.cmd ? refusalHtml(ui.slotErr, slotKey(a, c)) : "";
  if (serves.length && (!c || serves.includes(c))) return `${err}${pin}${chips}`;
  if (a.out) return `<span class="state">Signed out</span>`;
  // an expired token its command renews on its next run: the chip of that command carries the renewal, and its title
  // says so in words
  if (a.renews) {
    const cmd = p.cmds.find((x) => (a.row?.heads || []).includes(x.head))?.cmd;
    return `<span class="state mute">Token expired</span>${cmd ? `<span class="chip renew" title="Renews when ${esc(cmd)} next runs">${GLYPH.renew}${esc(cmd)}</span>` : ""}`;
  }
  // a key that is missing leaves its commands without one: each is a chip here, with no live dot, so the card says which
  if (p.kind === "key" && !a.has) return `<span class="state">No key</span>${p.cmds.filter((x) => (a.heads || []).includes(x.head)).map((x) => `<span class="chip">${esc(x.cmd)}</span>`).join("")}`;
  if (held(a)) return `${err}${pin}${locked(false, "")}`;
  if (p.kind === "plan" && c) {
    // armed, Use now waits in the row under the card's head, beside the sessions it would cut (armRow)
    const armed = ui.moving && ui.moving.a === a.id && ui.moving.c === c.cmd;
    return `${err}${chips}${armed ? "" : useAct(p, c, a, "quiet")}`;
  }
  return "";
}
const slotKey = (a, c) => `slot:${a.id}|${c.cmd}`;
const useAct = (p, c, a, cls) => `<button class="act ${cls}" data-act="use" data-p="${p.id}" data-c="${esc(c.cmd)}" data-a="${a.id}">Use now</button>`;
// Use now asked once: the sessions with a request in flight on the command, each a chip to its session, then the act
// that switches and the way out, on a row of its own under the card's head. The confirming act fills in the text's own
// ink, never the limit red, which is for limits and failures.
function armRow(p, c, a) {
  const mv = ui.moving;
  if (!c || !mv || mv.a !== a.id || mv.c !== c.cmd) return "";
  return `<div class="armrow${ui.enter === "arm" ? " unfold" : ""}">${armedAsk("Switches at its next request", mv.cut, useAct(p, c, a, "danger"), '<button class="act quiet" data-act="keep-use">Cancel</button>')}</div>`;
}
// A key in the environment or a key file shadows the stored copy (KeyRoutes.kt:7-9): saving one there changes nothing.
const keyActs = (a) => a.from === "store";
function menuItems(p, a) {
  if (p.kind === "local") return [];
  if (p.kind === "key") return a.has && keyActs(a) ? [["replace", "Replace key"], ["remove", "Remove key"]] : [];
  return [...(a.canRename === false ? [] : [["rename", "Rename"]]), ...(a.out ? [] : [["resign", "Sign in again"]]),
    ...(a.canRemove === false ? [] : [["remove", "Remove"]])];
}
// Remove is in the text's ink like every other item, and armed it fills in that ink and names what it cuts: the limit red
// is for limits and failures, never for an act. A refusal says so in plain words under it, its reason one press away,
// and the menu shakes once.
// what pressing Remove again cuts, said under the item it filled: the login and its sign-in, and the sessions on it by name
function armNote(p, a, on) {
  const who = (a.sessions || []).slice(0, 3).map(sessNameOf).join(", ");
  return `<p class="alc-armnote">${p.kind === "key" ? `Removes the stored key for ${esc(a.env)}.` : `Removes ${esc(shownName(p, a))} and its sign-in.`}${on ? ` In use by ${esc(who)}${on > 3 ? ` and ${on - 3} more` : ""}.` : ""} Press Remove again to confirm.</p>`;
}
function menuHtml(p, a, spot) {
  const armed = ui.armed === spot, refused = ui.removeErr && ui.removeErr.spot === spot, key = `remove:${spot}`;
  const on = a.sessions?.length || 0; // sessions on it sign in again or move to another login once it is gone
  return `<div class="menu${refused && ui.shook === key ? " failed" : ""}" role="menu">${menuItems(p, a).map(([act, label]) => {
    const text = act === "remove" && armed ? `Remove ${p.kind === "key" ? "API key" : shownName(p, a)}${on ? ` · ${on === 1 ? "1 session uses it" : `${on} sessions use it`}` : ""}` : label;
    return `<button role="menuitem" class="${act === "remove" ? `danger${armed ? " armed" : ""}` : ""}" data-act="${act}" data-p="${p.id}" data-a="${a.id}" data-s="${spot}"${act === "remove" && ui.removing === spot ? " disabled" : ""}>${act === "remove" && ui.removing === spot ? "Removing…" : esc(text)}</button>${act === "remove" && armed && !ui.removing ? armNote(p, a, on) : ""}`;
  }).join("")}${refused ? `<div class="menu-state">${refusalHtml(ui.removeErr, key)}${reasonHtml(ui.removeErr, key)}</div>` : ""}</div>`;
}
const keyDayWord = (d) => d.toLocaleDateString("en-US", { month: "short", day: "numeric" }); // a key card's day, "Oct 12"
/** What a login is called on its card: the name he gave it, else the console's one rule (kit DRAW.name), "Account", with two
 *  that read alike told apart by a muted number over the provider's whole list. A default name carries an id fragment in the
 *  daemon's spelling, and an id is never a title. */
function namedLogin(p, a) {
  if (p.kind !== "plan") return { text: a.name, own: true };
  const named = DRAW.names("login", p.accounts.map((x) => ({ name: x.name, provider: p.name ?? p.id, plan: x.plan, ids: DRAW.loginIds(x.row) })));
  return named[p.accounts.indexOf(a)] ?? { text: a.name, own: true };
}
const shownName = (p, a) => namedLogin(p, a).text;
function nameHtml(p, a, spot) {
  // a key is named by its variable and, when it replaced another, the day splice first saw it
  if (p.kind === "key") return `<span class="name"><span class="var">${esc(a.env)}</span>${a.tail ? ` <span class="tail">${esc(a.tail)}</span>` : ""}${a.has && a.seen && a.twins ? ` · ${keyDayWord(a.seen)}` : ""}</span>`;
  // a model on this computer is told by its command, as the chip every command is drawn as, lit while the server answers
  if (p.kind === "local") return `<span class="lmc-cmd"><span class="chip">${a.down || a.unread ? "" : "<i></i>"}${esc(a.name)}</span></span>`;
  if (ui.rename !== spot) return `<span class="name">${DRAW.nameHtml(namedLogin(p, a))}</span>`;
  const err = ui.renameErr;
  // one visible way to finish and one to leave, as the key field's Save and Cancel: both walkers had to guess Enter
  // (p153, p154). A refusal reads under it (cardHtml).
  // the field never opens holding an address, which no name may hold: it opens on the name he gave, or empty with the
  // name splice shows now as its placeholder
  return `<input class="field rename${ui.enter === "rename" ? " enter" : ""}" data-a="${a.id}" data-s="${spot}" value="${esc(ui.renameDraft ?? (/\S@\S/.test(a.name ?? "") ? "" : a.name))}" placeholder="${esc(shownName(p, a))}" aria-label="Name" spellcheck="false"${err ? ' aria-invalid="true" aria-describedby="rename-err"' : ""}>`
    + `<button class="act primary small" data-act="finish-rename">Save</button><button class="act quiet small" data-act="cancel-rename">Cancel</button>`;
}
// c is the command whose order the rail shows on a plan provider, and null for a key or this computer. Each account shows
// once, so its spot is the provider and the account.
// the sessions on a login now, each a door to it on Sessions; past a handful, the rest behind one pill that opens them here
const SESS_SHOWN = 4;
const LOGIN_NAME = new Map();
// a login by the name Accounts gives it; one splice never listed keeps a plain word, never its selector or an id
const loginOf = (head, label) => LOGIN_NAME.get(`${head}|${label}`) ?? (String(label ?? "").startsWith("native:") ? "Claude Code's own sign-in" : "Another login");
const at24 = (ms) => backWord(new Date(ms));
// a session's own last switch and splice's reason, and whether it is pinned to this login alone (spec section 11); its
// pane on Sessions carries Use and Unpin for it
function sessWhy(x) {
  const own = x.account_pin && x.account_pin !== x.account_pin_head ? "Pinned to this account" : "";
  const sw = x.account_switch;
  const moved = sw ? `Moved from ${loginOf(x.head, sw.from)} to ${loginOf(x.head, sw.to)}${sw.at_epoch_millis ? ` at ${at24(sw.at_epoch_millis)}` : ""}${sw.reason ? ` · ${movedWhy(sw.reason)}` : ""}` : "";
  return [own, moved].filter(Boolean).join(" · ");
}
// HOW MANY DOORS EACH LOGIN HAD, kept from the last answer and the last visit, so while /api/sessions is read (4 s and
// more on his machine) each card stands at the size it will land at, its doors drawn as their ghosts
const DOORS_SEEN = new Map();
function sessionsHtml(p, a, sock, live) {
  if (a.sessions === null) return ghostDoors(Math.min(SESS_SHOWN, DOORS_SEEN.get(a.id) ?? 0));
  const on = a.sessions || [];
  DOORS_SEEN.set(a.id, on.length);
  const marks = movesHtml(p, a, sock, live);
  if (!on.length) return marks;
  // each name carries the lamp Sessions gives a running session (kit.js look), so it reads as an agent on this login now
  const lamp = (x) => (/^(working|busy)$/.test(x.status) ? `<span class="lamp working" aria-hidden="true">${ICON.trace}</span>` : `<span class="lamp idle" aria-hidden="true"><i></i></span>`);
  // two sessions of one name on a login are told apart by their folder, as Sessions names a repo, and by when each
  // started where the folder is the same too
  const nameOf = sessNameOf;
  const count = (f) => on.reduce((m, x) => m.set(f(x), (m.get(f(x)) || 0) + 1), new Map());
  const names = count(nameOf), places = count((x) => `${nameOf(x)}|${folderOf(x)}`);
  const apart = (x) => (names.get(nameOf(x)) < 2 ? "" : places.get(`${nameOf(x)}|${folderOf(x)}`) < 2 && folderOf(x) ? folderOf(x) : startedAt(x));
  const door = (x) => { const why = sessWhy(x), pinned = x.account_pin && x.account_pin !== x.account_pin_head, also = apart(x);
    return `<a class="sdoor" href="sessions.html#${encodeURIComponent(x.session_id)}"${why ? ` title="${esc(why)}"` : ""}>${lamp(x)}<span>${esc(nameOf(x))}</span>${also ? `<span class="apart">${esc(also)}</span>` : ""}${pinned ? GLYPH.pin : ""}</a>`; };
  // the rest of this login's sessions open in place, where the first ones stand, and fold back with Fewer
  const open = ui.doors.has(a.id), shown = open ? on : on.slice(0, SESS_SHOWN);
  const more = on.length > SESS_SHOWN ? DRAW.more(on.length - SESS_SHOWN, { pill: true, open, act: "doors", data: ` data-a="${esc(a.id)}" aria-expanded="${open}"` }) : "";
  return `<div class="onlogin">${shown.map(door).join("")}${more}</div>${marks}`;
}
const folderOf = (x) => String(x.repo?.root || x.cwd || "").split("/").filter(Boolean).pop() || "";
const sessNameOf = (x) => x.name || x.title || String(x.session_id).slice(0, 8); // a session by the name Sessions gives it
function startedAt(x) {
  const t = typeof x.started_at === "number" ? (x.started_at < 1e12 ? x.started_at * 1000 : x.started_at) : Date.parse(x.started_at ?? "");
  return Number.isFinite(t) ? `since ${clock(new Date(t))}` : "";
}
// A MOVE IS SHOWN ON THE CARDS IT TOUCHED (Oct 26), in one short line under the card's doors: its glyph says onto this
// login or off it, then the other login by its socket and its name, a door to its card on this board, then why and when.
// Where a provider serves two or more commands, each command's move is its own line with that command's chip, so two
// commands moved off one login read as two moves and he can tell which command went where. The command's last move
// (spec 11h, AccountPoolJson last_switch) and each session's own (account_switch) read alike, merged when they say the
// same move; a session move names how many when not every door on the card moved.
function movesHtml(p, a, sock = 0, live = false) {
  if (p.kind !== "plan" || !a.namesOn) return "";
  const many = p.cmds.length > 1, cmdOf = (head) => (many ? p.cmds.find((c) => c.head === head)?.cmd ?? null : null);
  const heads = a.row?.heads || [], mine = (head, label) => heads.includes(head) && a.namesOn(head).includes(label);
  // the login a move names, as this board draws it: its name, and its card and socket when it has one here
  const rail = ordered(p, laneOf(p));
  const login = (head, label) => {
    const x = p.accounts.find((o) => o !== a && (o.row?.heads || []).includes(head) && o.namesOn?.(head).includes(label));
    return { name: loginOf(head, label), id: x?.id ?? null, sock: x ? rail.indexOf(x) + 1 : 0 };
  };
  const into = new Map(), away = new Map();
  // each move keeps the sessions it carried, so its line names them as doors to their panes on Sessions
  const add = (map, key, g, x, ms) => { const m = map.get(key) || { ...g, who: [], at: 0 }; if (x) m.who.push(x); m.at = Math.max(m.at, ms || 0); map.set(key, m); };
  for (const c of p.cmds) {
    const m = c.moved;
    if (!m) continue;
    const cmd = cmdOf(c.head);
    if (mine(c.head, m.to)) add(into, `${cmd}|${loginOf(c.head, m.from)}|${m.reason}`, { cmd, other: login(c.head, m.from), why: movedWhy(m.reason) }, null, m.at_epoch_millis);
    else if (mine(c.head, m.from)) add(away, `${cmd}|${loginOf(c.head, m.to)}`, { cmd, other: login(c.head, m.to), why: movedWhy(m.reason) }, null, m.at_epoch_millis);
  }
  for (const x of a.sessions || []) {
    const sw = x.account_switch, cmd = cmdOf(x.head);
    if (sw?.from && !mine(x.head, sw.from)) add(into, `${cmd}|${loginOf(x.head, sw.from)}|${sw.reason}`, { cmd, other: login(x.head, sw.from), why: movedWhy(sw.reason) }, x, sw.at_epoch_millis);
  }
  for (const x of liveSessions || []) {
    const sw = x.account_switch, cmd = cmdOf(x.head);
    if (sw?.from && mine(x.head, sw.from) && !mine(x.head, x.account)) add(away, `${cmd}|${loginOf(x.head, x.account)}`, { cmd, other: login(x.head, x.account), why: movedWhy(sw.reason) }, x, sw.at_epoch_millis);
  }
  // A MOVE IS DRAWN AS THE SOCKETS IT RAN BETWEEN: the login the traffic left and the one it came to, each as the number it
  // has on the rail, the arrow between them, the command's chip where the provider serves two or more, how many sessions it
  // carried as a lamp and a count, all one path cell as wide as the widest path on the card, and its time right after it, at one
  // x on every line, the chevron that opens why following the time. A login this board holds no card for is an empty
  // socket. The other login is a door to its card. Why it moved, and the sessions by name, open under the line.
  const here = { id: a.id, sock, name: shownName(p, a), on: live };
  const socket = (x, door) => (x.id && door
    ? `<button class="mlogin" type="button" data-act="goto" data-a="${esc(x.id)}" aria-label="Show ${esc(x.name)}, login ${x.sock}"><span class="msock">${x.sock}</span></button>`
    : x.sock ? `<span class="msock${x.on ? " on" : ""}" role="img" aria-label="${esc(x.name)}, login ${x.sock}">${x.sock}</span>`
      : `<span class="msock none" role="img" aria-label="${esc(x.name)}"></span>`);
  // the sessions a move carried, the first two by name as doors to them on Sessions and the rest counted; the command's
  // own move, which carried no one session, names none
  const who = (g) => {
    if (!g.who.length) return "";
    const named = g.who.slice(0, 2).map((x) => `<a class="mdoor" href="sessions.html#${encodeURIComponent(x.session_id)}">${esc(sessNameOf(x))}</a>`).join(", ");
    return `${named}${g.who.length > 2 ? ` +${n0(g.who.length - 2)}` : ""}`;
  };
  const line = (g, dir) => {
    const key = `move:${a.id}|${dir}|${g.cmd ?? ""}|${g.other.id ?? g.other.name}|${g.why ?? ""}`;
    const said = [!g.other.id ? `${dir === "from" ? "From" : "To"} ${esc(g.other.name)}` : "", g.why ? esc(g.why) : "", who(g)].filter(Boolean).join(" · ");
    const open = !!said && ui.moves.has(key), time = g.at ? at24(g.at) : "";
    const from = dir === "from" ? g.other : here, to = dir === "from" ? here : g.other;
    const when = `<span class="mat">${time}</span>`;
    const end = said ? DRAW.disc({ open, act: "move-why", inner: when, data: ` data-k="${esc(key)}" aria-label="Why it moved${time ? `, ${time}` : ""}"` }) : when;
    return `<div class="moveline${movesPrimed && !MOVES_SEEN.has(key) ? " fresh" : ""}" data-key="${esc(key)}"><span class="mpath">${socket(from, from !== here)}${MOVE_ARROW}${socket(to, to !== here)}`
      + `${g.cmd ? `<span class="chip">${esc(g.cmd)}</span>` : ""}${g.who.length ? `<span class="mcarry" title="${n0(g.who.length)} ${g.who.length === 1 ? "session" : "sessions"} moved"><span class="lamp idle" aria-hidden="true"><i></i></span><b>${n0(g.who.length)}</b></span>` : ""}</span>${end}</div>`
      + (open ? `<p class="mwhy${ui.enter === `move:${key}` ? " unfold" : ""}">${said}</p>` : "");
  };
  const lines = [...[...into.values()].map((g) => line(g, "from")), ...[...away.values()].map((g) => line(g, "to"))];
  return lines.length ? `<div class="moves"><span class="mkick">Moved</span>${lines.join("")}</div>` : "";
}
// the arrow a move draws between its two sockets
const MOVE_ARROW = '<svg class="marrow" viewBox="0 0 30 12" aria-hidden="true"><path d="M1 6h26M22 1.6 27.6 6 22 10.4"/></svg>';
// the moves already drawn, so one that arrives while the page is open pops in once and the rest stand still; primed once a
// board with every login's sessions has been drawn, so the moves a visit opens on are never new
const MOVES_SEEN = new Set();
let movesPrimed = false;
// how many rows a card's windows draw, so a login being read for the first time stands at the height it will land at
const WINDOW_GHOST = () => `<span class="label">${GHOST.bar(3.4)}</span>${GHOST.track()}<span class="pct">${GHOST.fig(4.2)}</span><span class="when">${GHOST.ring()}${GHOST.bar(6.2)}</span>`;
function cardHtml(p, c, a, i, serves) {
  const spot = `${p.id}/${a.id}`;
  // a server on this computer that does not answer serves nothing, so it wears no serving tint and no lit socket
  const live = serves.length && !(p.kind === "local" && (a.down || a.unread));
  const cls = ["card", live ? "serving" : "", a.out || (p.kind === "key" && !a.has) ? "out" : "", held(a) ? "held" : ""].join(" ");
  const canMove = c && laneAccts(p, c).length > 1 && c.orderable?.has(labelOn(a, c.head)) && !ui.orderSaving && !ui.removing;
  // a grip that cannot move promises nothing on hover: only one that drags says how
  const grip = p.kind === "plan" ? `<button class="grip" data-grip="${p.id}" data-c="${esc(c.cmd)}" data-a="${a.id}" aria-label="Move ${esc(shownName(p, a))}"${canMove ? ' title="Drag to reorder, or use the up and down arrow keys"' : " disabled"}>${GLYPH.grip}</button>` : "";
  // a default name starts with its plan word ("Max", "Max · 1a2b3c4d", later "Max 2"): the tag beside it shows only
  // when it says more than that word, such as a tier "Max 5x" (fin, Oct 12)
  const said = a.row?.label_default && Boolean(a.plan) && shownName(p, a).toLowerCase().includes(String(a.plan).trim().toLowerCase());
  const sub = a.plan && ui.rename !== spot && !said ? `<span class="plan">${esc(a.plan)}</span>` : "";
  // labels only (Oct 12): a login splice named itself offers its one naming here, beside the name, and never blocks
  const nameIt = a.row?.label_default && a.row?.can_rename && ui.rename !== spot
    ? `<button class="act quiet small name-it" data-act="rename" data-s="${spot}">Name it</button>` : "";
  // the address the provider proved for this login, under its name; a login named by its email already shows it
  const mail = a.email && shownName(p, a) !== a.email
    ? `<span class="email" title="${esc(a.email)}">${esc(a.email)}</span>` : "";
  const more = menuItems(p, a).length
    ? `<button class="icon more" data-act="menu" data-p="${p.id}" data-a="${a.id}" data-s="${spot}" aria-label="More" aria-haspopup="menu" aria-expanded="${ui.menu === spot}">${GLYPH.more}</button>` : "";
  const mine = (t) => ui.signin[t] && (ui.signin[t].spot == null || ui.signin[t].spot === spot);
  const signT = mine(`out:${a.id}`) ? `out:${a.id}` : mine(`re:${a.id}`) ? `re:${a.id}` : null;
  const signRow = signT ? `<div class="outbox${ui.signin[signT].state === "fail" ? " failed" : ""}${ui.enter === "sign" ? " enter" : ""}">${signingHtml(signT)}</div>` : "";
  let body;
  // a key splice refused is said beside its field, its word the door to splice's reason, which opens under the row
  const keyErr = ui.keyErr?.spot === spot ? ui.keyErr : null, keyKey = `key:${spot}`;
  const keyField = (cancel) => `<div class="outbox${cancel && ui.enter === "key" ? " enter" : ""}${keyErr && ui.shook === keyKey ? " failed" : ""}"><input class="field" id="key-in" type="password" data-a="${a.id}" placeholder="Paste key" aria-label="API key" autocomplete="off" spellcheck="false"${keyErr ? ' aria-invalid="true"' : ""}>`
    + `${keyErr ? refusalHtml(keyErr, keyKey) : ""}<button class="act primary" data-act="save-replace" data-p="${p.id}" data-a="${a.id}" disabled>Save</button>${cancel ? `<button class="act quiet" data-act="cancel-replace">Cancel</button>` : ""}${reasonHtml(keyErr, keyKey)}</div>`;
  if (p.kind === "key") {
    // where the value comes from rides the name line as a tag (src, below); the body is the field while one is open
    body = !a.has ? keyField(false) : ui.replace === spot ? keyField(true) : "";
    // a key's provider sends no plan window, so its limits line says that, never an empty bar or a zero (spec section 11)
    if (a.has) body += `<div class="keysrc"><span class="state mute">Limits not reported by provider</span></div>`;
    // the keys this one replaced, as one quiet line with the days splice first saw them
    const n = a.replaced?.length || 0, days = [...new Set((a.replaced || []).filter(Boolean).map(keyDayWord))];
    if (n) body += `<div class="keysrc"><span class="state mute">Replaced ${n === 1 ? "an earlier key" : `${n} earlier keys`}${days.length ? ` · ${esc(days.join(", "))}` : ""}</span></div>`;
  } else if (a.out) {
    body = signRow || `<div class="outbox"><button class="act primary" data-act="signin" data-p="${p.id}" data-a="${a.id}">Sign in</button></div>`;
  } else if (p.kind === "plan" && a.noUsage) {
    // fin's words (Marlin, Oct 10): no time on it, and never "no usage", which reads as "used nothing"
    body = `<div class="keysrc"><span class="state">No limits reported</span></div>${signRow}`;
  } else if (p.kind === "plan" && !a.windows.length) {
    // no reading at all is never "No limits reported", which is the provider answering with none (Oct 12). A login
    // splice has never read stands as the ghost of its two windows while the page's own read is in flight; once it is
    // back with nothing, Not read, with the provider's reason when splice kept one (marlin, Marcos's Oct 4 ruling), over
    // the provider's own windows drawn empty: each label on its dashed track with a dash for its figure, so the card
    // keeps its neighbours' columns and says once what is missing
    const never = !a.row?.observed_at_epoch_seconds && !a.row?.no_usage_at_epoch_seconds;
    const why = a.row?.last_read_failure ? READ_WHY[a.row.last_read_failure.reason] ?? null : null;
    const rows = windowLabels(p).map((l) => `<span class="label">${l}</span>${bar(null)}<span class="pct">${DRAW.dash}</span><span></span>`).join("");
    body = never && ui.probing ? `<div class="windows" aria-busy="true">${WINDOW_GHOST()}${WINDOW_GHOST()}</div>${signRow}`
      : `<div class="windows"><span class="unproved">${DAY_NOT_READ}${why ? `<span class="why"> · ${esc(why)}</span>` : ""}</span>${rows}</div>${signRow}`;
  } else if (p.kind === "plan") {
    const first = c && serving(p, c) === a;
    const next = first ? nextOf(p, c) : null;
    body = windowsHtml(a, next, first ? c.lastLeft : null, usageDoor(a, c), next ? ordered(p, c).indexOf(next) + 1 : 0) + signRow;
  } else {
    // the model it serves, and the door to its command on Models, where its log and its model are
    // a server that does not answer offers the act that asks it again, on its own card, beside the door to its command
    const checking = ui.checking === a.id;
    const still = a.down && ui.stillDown === a.id && !checking ? `<p class="lmc-still again-err${ui.shook === `recheck:${a.id}` ? " failed" : ""}">${DRAW.result("Still not answering", "bad")}</p>` : "";
    // Check again stands at one place on every local card: where the server answers its place is held, so Models never moves
    const again = a.down ? DRAW.act({ word: checking ? "Checking" : "Check again", act: "recheck", cls: "quiet small lmc-again", busy: checking, disabled: checking, data: ` data-a="${esc(a.id)}"` })
      : `<span class="lmc-hold" aria-hidden="true"></span>`;
    const m = localModel(a.model);
    body = `<div class="local-model lmc-body"><div class="lmc-row"><b class="lmc-name">${esc(m.name)}</b>${m.qual ? `<span class="lmc-qual">${esc(m.qual)}</span>` : ""}${m.tag ? `<span class="lmc-tag">${esc(m.tag)}</span>` : ""}</div>`
      + `${still}<div class="lmc-foot">${again}<a class="act quiet small door" href="models.html?cmd=${encodeURIComponent(a.id)}">Models${DRAW.go}</a></div></div>`;
  }
  // a key read from the environment or a file says so on its name line, as a plan login's tier does; one in splice's
  // store is the plain case and carries no tag
  const src = p.kind === "key" && a.has && KEY_FROM[a.from] ? `<span class="plan src">${KEY_FROM[a.from]}</span>` : "";
  const socket = p.kind === "plan" ? `<span class="socket">${i + 1}</span>` : "";
  const renameErr = ui.rename === spot && ui.renameErr ? asErr(ui.renameErr) : null;
  const slotErr = c && ui.slotErr && ui.slotErr.a === a.id && ui.slotErr.c === c.cmd ? ui.slotErr : null;
  return `<article class="${cls}" data-key="card:${spot}" data-p="${p.id}" data-a="${a.id}">${socket}<div class="top">${grip}`
    + `${nameHtml(p, a, spot)}${nameIt}${sub}${src}<div class="slot${slotErr && ui.shook === slotKey(a, c) ? " failed" : ""}">${slotHtml(p, c, a, serves)}</div>${more}</div>`
    + `${slotErr ? reasonHtml(slotErr, slotKey(a, c), "onslot") : ""}${armRow(p, c, a)}`
    + `${mail}${renameErr ? `<div class="rename-err${ui.shook === "rename" ? " failed" : ""}" id="rename-err">${refusalHtml(renameErr, "rename")}${reasonHtml(renameErr, "rename")}</div>` : ""}`
    + `${ui.menu === spot ? menuHtml(p, a, spot) : ""}${body}${sessionsHtml(p, a, i + 1, !!live)}</article>`;
}
const KEY_FROM = { environment: "Environment", file: "File" };
// A LOCAL MODEL AS ITS HEAD LABELS IT, "Name 7B (local, 1.72bpw)": the name he would say, the words that tell two
// instances of one model apart, and how far it is compressed, in plain words. The group's header already says where it runs
// ("local", "vast.ai"), and a file format or a quantisation code is the format's word, never the model's name.
const LOCAL_WHERE = /^(?:local|vast(?:\.ai)?)$/i;
const LOCAL_OPTION = /^lens$/i; // a runtime option the label spells after its instance words; it tells no instance apart
const bitsWord = (n) => { const v = Math.round(n * 10) / 10; return `compressed to ${v} ${v === 1 ? "bit" : "bits"}`; };
function localModel(label) {
  const whole = String(label ?? "").trim(), inside = [];
  let name = whole.replace(/\(([^)]*)\)/g, (_, g) => { inside.push(...g.split(",")); return " "; }), bits = null;
  const take = (re, get) => { name = name.replace(re, (...m) => { bits ??= get(m); return " "; }); };
  take(/\b(\d+(?:\.\d+)?)\s*bpw\b/i, (m) => +m[1]);
  take(/\bI?Q(\d+)(?:_[A-Z0-9]+)*\b/, (m) => +m[1]);
  take(/\bB?F(16|32)\b/, (m) => +m[1]);
  name = name.replace(/\bGGUF\b/gi, " ").replace(/\s+/g, " ").replace(/[-\s]+$/, "").trim();
  const quals = [];
  for (const raw of inside) {
    const w = raw.trim();
    if (!w || LOCAL_WHERE.test(w) || LOCAL_OPTION.test(w)) continue;
    const q = /^(\d+(?:\.\d+)?)\s*bpw$/i.exec(w);
    if (q) { bits ??= +q[1]; continue; }
    quals.push(w);
  }
  return { name: name || whole, qual: quals.join(", "), tag: bits != null ? bitsWord(bits) : "" };
}
const asErr = (e) => (typeof e === "string" ? { word: e, why: "" } : e);
// the windows a provider's logins report, in their order, so a login with no reading draws the same rows empty; a
// provider none of whose logins was read draws one row, unlabelled, rather than guess which windows it keeps
const WINDOW_ORDER = ["5 hours", "Week", "Month"];
function windowLabels(p) {
  const seen = new Set(p.accounts.flatMap((x) => (x.windows || []).map((w) => w.label)));
  const labels = WINDOW_ORDER.filter((l) => seen.has(l));
  return labels.length ? labels : [""];
}

// ---------- the tiles that end a rail, and the one that ends the page ----------
function addTileHtml(p, c) {
  const t = `add:${c.cmd}`, s = ui.signin[t];
  if (s) return DRAW.addForm({ body: signingHtml(t), data: ` data-key="${t}"`, socket: true, failed: s.state === "fail" });
  if (ui.naming?.cmd === c.cmd) { // a new login may be named before it signs in; left empty, Enter signs in and splice names it
    const d = `data-p="${p.id}" data-c="${c.cmd}"`, err = ui.naming.err;
    return DRAW.addForm({ cls: `naming${ui.enter === "naming" ? " enter" : ""}`, failed: Boolean(err), data: ` data-key="${t}"`, socket: true,
      body: `<input class="field add-name" value="${esc(ui.naming.draft)}" placeholder="Name" aria-label="Name" spellcheck="false" maxlength="48"${err ? ' aria-invalid="true"' : ""}>`
        + `<button class="act primary" data-act="add-go" ${d}>Sign in</button><button class="act quiet" data-act="cancel-add">Cancel</button>`
        + `${err ? DRAW.result(err, "bad") : ""}` });
  }
  return DRAW.addTile({ word: "Add account", act: "add", data: ` data-key="${t}" data-p="${p.id}" data-c="${c.cmd}"`, socket: true });
}
function switchHtml(p, c) {
  if (laneAccts(p, c).length < 2) return "";
  const disabled = Boolean(ui.orderSaving || ui.removing || c.orderable.size < 2);
  // the lit part is its own shape under the two words, so a redraw can slide it from the choice it stood on (render)
  return DRAW.switcher([["soonest", "Soonest reset"], ["mine", "My order"]], c.mode, "mode", `Account order${p.cmds.length > 1 ? `, ${c.cmd}` : ""}`,
    { data: ` data-p="${p.id}" data-c="${c.cmd}"`, disabled })
    .replace("<div class=\"switch\"", `<div class="switch abl-sw" data-sw="${esc(p.id)}|${esc(c.cmd)}" data-at="${c.mode}"`);
}
const railCards = (p, c) => {
  const srv = new Map(p.cmds.map((x) => [x, serving(p, x)]));
  return ordered(p, c).map((a, i) => cardHtml(p, c, a, i, p.cmds.filter((x) => srv.get(x) === a))).concat(addTileHtml(p, c));
};
function railHtml(p, c, span = 1, cuts = null, strips = null) {
  const cards = railCards(p, c);
  if (span < 2) return `<div class="rail" data-rail="${p.id}">${cards.join("")}</div>`;
  // a provider wider than one column reads its order down each column and on at the top of the next, each column on its
  // own length of rail, the numbers carrying on (1-5, 6-8 and Add account): still read top to bottom (hitstop 0360fc4).
  // Each column takes the run of cards that leaves the tallest column as short as it can be (cutRail), cut where the
  // board was packed, so a card whose rows grow later never hops to the next column
  return `<div class="rails" data-rail="${p.id}" style="--span:${span}">${wideCols(runs(cards, cuts, span), strips)}</div>`;
}
// each column of a wide provider: its strips (the commands dealt out down the columns in turn, each one column wide, so every strip
// ends where every other strip on the board ends), then its run of cards on the rail
const wideCols = (runs, strips) => runs.map((x, i) => `<div class="abl-col">${(strips?.[i] || []).join("")}<div class="rail">${x.join("")}</div></div>`).join("");
// cards cut into runs at [cuts]; cuts that do not fit them (a card added since) cut them into even runs
function runs(cards, cuts, span) {
  const at = Array.isArray(cuts) && cuts.length === span - 1 && cuts.every((x, i) => x > (cuts[i - 1] ?? 0) && x < cards.length) ? cuts
    : Array.from({ length: span - 1 }, (_, i) => Math.ceil((cards.length * (i + 1)) / span));
  return [0, ...at].map((x, i) => cards.slice(x, at[i] ?? cards.length));
}
// Every provider reads the same: its name with the order switch, a day per command with the command as its tab, then
// each account once on one rail, carrying the chip of every command it serves. Each command keeps its own order
// (AccountOrderStore.kt:1, :24): where a plan provider serves two or more, the pressed tab picks whose order the rail and
// the switch show.
const laneOf = (p) => p.cmds.find((c) => c.cmd === ui.lane[p.id]) || p.cmds[0];
// The last time splice moved a command from one login to another, and why (spec 11h, AccountPoolJson last_switch). The
// reason is the daemon's own phrase (AccountSwitchReason.kt), said here in his words; one it does not know reads as sent.
const MOVED_WHY = { "operator pinned this account": "You pinned it", "operator account order": "Your order",
  "primary account reset": "Main account reset", "quota resets sooner": "Resets sooner", "provider rate limit reached": "Rate limited",
  "rate limit exceeds turn wait budget": "Rate limited too long to wait", "5-hour quota exhausted": "5-hour limit used up",
  "7-day quota exhausted": "Weekly limit used up", "quota usage reading full": "Usage at its limit", "account unavailable": "Account unavailable",
  "previous login needs sign-in": "Needs sign-in", "previous login's token expired": "Token expired", "session signed in to this account": "Signed in here",
  "5-hour plan limit reached": "5-hour limit used up", "7-day plan limit reached": "Weekly limit used up", "7d window exhausted": "Weekly limit used up" };
// a plan's own weekly window by name, "7-day Fable plan limit reached" (AccountSwitchReason.kt planWindow), reads as the
// window his card draws: "Weekly Fable limit used up"
const movedWhy = (r) => {
  const named = /^7-day (.+) plan limit reached$/.exec(r ?? "");
  return MOVED_WHY[r] ?? (named ? `Weekly ${named[1]} limit used up` : r ? r[0].toUpperCase() + r.slice(1) : "");
};
// A command whose pool is down to one usable login says the next plan limit has nothing to move to, and until when: the
// earliest time a held login comes back, from /api/accounts' head_pools, the reading status and doctor use (splice-lead,
// Oct 13: claude-splice ran on one live login most of the day). Null for a pool of one, which never had a fallback.
function lastLeftOf(pool) {
  const all = pool?.account_pool?.accounts ?? [];
  const open = all.filter((a) => a.available && a.credential_present !== false);
  if (all.length < 2 || open.length !== 1) return null;
  const now = Date.now() / 1000;
  const back = all.filter((a) => !open.includes(a)).map((a) => a.blocked_until_epoch_seconds).filter((s) => s != null && s > now);
  return { untilMs: back.length ? Math.min(...back) * 1000 : null };
}
// A refused order save says so in the provider's own header, beside the switch it came from, and shakes once there: the word,
// and splice's reason beside it on at most two lines, in the room the header always holds, so nothing on the board moves down
// to make room for it. The order goes back to the one splice still holds. The next press clears it.
function providerHtml(p, span = 1) {
  const refusal = (c) => (ui.orderErr && c && ui.orderErr.cmd === c.cmd ? ui.orderErr : null), key = (c) => `order:${c.cmd}`;
  const saving = (c) => (ui.orderSaving === c.cmd ? `<span class="abl-busy" role="status"><i class="abl-spin" aria-hidden="true"></i>Saving order</span>` : "");
  const said = (c) => (refusal(c) ? `<span class="order-err abl-refusal${ui.shook === key(c) ? " failed" : ""}">${refusalHtml(refusal(c))}${refusal(c).why ? `<span class="abl-why">${esc(refusal(c).why)}</span>` : ""}</span>` : "");
  const group = (end, body) => DRAW.group({ name: p.name, c: colorOf(p.id), key: `prov:${p.id}`, cls: `provider${span > 1 ? " wide" : ""}`,
    style: span > 1 ? `--span:${span}` : "", end, body });
  if (p.kind !== "plan") {
    const srv = serving(p), meters = (p.kind === "local" ? p.cmds.filter((c) => c.budget) : p.cmds).map((c) => meterHtml(c)).join("");
    // a model on this computer or a rented GPU bills nothing per token: no Day row, unless he already set a budget on it
    // (Marlin and fin, Oct 10), which keeps its row with no figure and no line
    // a key card carries the commands that send with it; a local card its own command
    const serves = (a) => (p.kind === "local" ? p.cmds.filter((c) => c.head === a.id) : p.kind === "key" ? p.cmds.filter((c) => a.has && (a.heads || []).includes(c.head)) : a === srv ? p.cmds : []);
    return group("", `${meters}<div class="rail plain" data-rail="${p.id}">${p.accounts.map((a, i) => cardHtml(p, null, a, i, serves(a))).join("")}</div>`);
  }
  const c = laneOf(p), many = p.cmds.length > 1;
  const meters = p.cmds.map((x) => meterHtml(x, many ? { p, on: x === c } : null));
  // over k columns the strips are dealt down the columns, one column wide each, and a provider with fewer commands than columns
  // leaves the columns beside them to their cards
  const dealt = span > 1 ? Array.from({ length: span }, (_, i) => meters.filter((_, k) => k % span === i)) : null;
  return group(said(c) + saving(c) + switchHtml(p, c), `${span > 1 ? "" : meters.join("")}${railHtml(p, c, span, layout?.wide?.id === p.id ? layout.wide.cuts : null, dealt)}`);
}
function addProviderHtml() {
  const have = new Set(data.map((p) => p.id));
  const st = ui.addProv;
  if (!st) return `<section class="provider" data-key="addprov"><header></header>${DRAW.addTile({ word: "Add provider", act: "add-provider" })}</section>`;
  if (st.stage === "pick") {
    const picks = CATALOG.filter((c) => !have.has(c.id)).map((c) => `<button class="pick" style="--c:${colorOf(c.id)}" data-act="pick" data-id="${c.id}"><span class="blot">${c.name[0]}</span>${c.name}</button>`).join("");
    // the pick's own edge is the text's quiet ink: the key light stays on the one serving socket and the one primary act
    return `<section class="provider" data-key="addprov"><header></header>${DRAW.addForm({ cls: ui.enter === "pick" ? "enter" : "", data: ` style="--c:var(--text-mute)"`,
      body: `<div class="providers">${picks}</div><button class="act quiet" data-act="cancel-prov">Cancel</button>` })}</section>`;
  }
  const c = CATALOG.find((x) => x.id === st.id);
  const inner = st.stage === "key"
    ? `<input class="field" id="key-in" type="password" placeholder="Paste key" aria-label="API key" autocomplete="off" spellcheck="false"><button class="act primary" data-act="save-key" disabled>Add</button><button class="act quiet" data-act="cancel-prov">Cancel</button>`
    : signingHtml("prov");
  return DRAW.group({ name: c.name, c: colorOf(c.id), key: "addprov", cls: "provider", body: DRAW.addForm({ body: inner, failed: ui.signin.prov?.state === "fail" }) });
}

// ---------- the board: providers stacked into columns, each into the shortest so far ----------
// The placement holds until the width or the set of providers changes, so opening an editor never throws a provider
// into the next column.
let layout = null;
// HOW TALL EACH PART STANDS, in rem, from the rules that draw it (console.css .card, .windows, .rail, .provider, .meter): a
// login's card from its own reading alone (its rows, its held line, its address, its Next row), a provider from its head, its
// days and its rail. WHAT THE SESSIONS ANSWER LATER IS NEVER COUNTED (which doors and which moves a card holds): they arrive
// seconds after the board, so a packing that counted them cut a wide rail at one card before they landed and at the next
// after, and cards hopped columns as the page settled. Everything counted here is known from /api/accounts and /api/status,
// which the ghosts of a first visit read too, so the ghosts and the landed board cut at the same card. `serving` is the one
// allowance made for what is not known yet: the login that serves carries the sessions it serves, so its card stands taller
// than a held one by the hairline over its Next row and its row of doors. It is the same number before the sessions answer
// and after, so it moves nothing, and it keeps the first run of a wide rail from standing a card taller than the second.
const REM = { local: 10.4, card: 4.65, email: 1.4, row: 2.7, outbox: 3.3, line: 1.9, gap: 1, add: 2.4, head: 3.6, strip: 17.7, idle: 6.9, bar: 2.43, prov: 2.4, addProv: 4.6, serving: 6.6, doors: 5, doors2: 7.3 };
// a plan login's height; [next] is the serving login, which carries the Next row
function loginRem(a, next) {
  const top = REM.card + (a.email ? REM.email : 0);
  if (a.out) return top + REM.outbox;
  if (a.noUsage) return top + REM.line;
  if (!a.windows.length) return top + 3 * REM.row; // not read: its line and the provider's windows drawn empty
  const rows = a.windows.length + (a.windows.some((w) => w.label === "Week") ? a.models.length : 0) + (held(a) ? 1 : 0) + (a.staleAt != null ? 1 : 0) + (next ? 1 : 0);
  // the sessions it serves, as the last visit saw them (none before there was one), and what the serving login adds: the hairline, its moves
  const doors = a.sessions === null ? DOORS_SEEN.get(a.id) ?? 0 : a.sessions?.length || 0;
  return top + rows * REM.row + (doors > SESS_SHOWN - 1 ? REM.doors2 : doors ? REM.doors : 0) + (next ? REM.serving : 0);
}
const runRem = (hs) => hs.reduce((t, h) => t + h, 0) + Math.max(0, hs.length - 1) * REM.gap;
// a provider's cards as heights, Add account last on a plan's rail; a key and a model on this computer are one line each
const railRems = (p) => {
  if (p.kind !== "plan") return p.accounts.map(() => (p.kind === "local" ? REM.local : REM.card + REM.line));
  const rail = ordered(p, laneOf(p));
  return [...rail.map((a, i) => loginRem(a, i === 0 && rail.length > 1)), REM.add];
};
// a strip's height: the hero row and the hours, and a row for each bar the day draws (the requests; the dollars or the budget against its cap)
const stripRem = (c) => {
  if (isIdle(c)) return REM.idle;
  const split = c.split && !c.unread ? c.split : null, { usd } = dayBars(c, split);
  return REM.strip + REM.bar * ((c.turns != null && !c.local && c.unread !== DAY_READING ? 1 : 0) + (usd || capFill(c, split) ? 1 : 0) - 2);
};
const stripsOf = (p) => (p.kind === "local" ? p.cmds.filter((c) => c.budget) : p.cmds).map(stripRem);
// a provider as the packing weighs it: the board and a first visit's ghosts hand it the same shape
const packItem = (p) => ({ id: p.id, plan: p.kind === "plan", sh: stripsOf(p), hs: railRems(p) });
// THE CUTS THAT SPLIT A WIDE RAIL into k runs in its order, each two cards or more, the tallest run as short as it can be.
// Split by count, a rail of cards of different heights stood one column 600 px longer than the next at 3822 (Oct 27): the
// serving login with its doors and moves at the top of the first, a signed-out one at the foot of the second.
// [lead]: what each column already holds above its run (its strips), so the cards are cut to level the columns, not the runs.
function cutRail(hs, k, min = 2, lead = []) {
  const n = hs.length, run = (i, j, m) => runRem(hs.slice(i, j)) + (lead[m] || 0), from = [];
  let best = Array.from({ length: n + 1 }, (_, j) => (j >= min ? run(0, j, 0) : Infinity)); // best[j]: the tallest run, first j cards
  for (let m = 2; m <= k; m++) {
    const next = Array(n + 1).fill(Infinity), at = Array(n + 1).fill(-1);
    for (let j = m * min; j <= n; j++) for (let i = (m - 1) * min; i <= j - min; i++) {
      const t = Math.max(best[i], run(i, j, m - 1));
      if (t < next[j]) { next[j] = t; at[j] = i; }
    }
    from.push(at); best = next;
  }
  const cuts = [];
  for (let m = k, j = n; m >= 2; m--) { j = from[m - 2][j]; cuts.unshift(j); }
  return { cuts, tall: best[n] };
}
// The board's tallest column is as short as it can be (Marlin's walk item 8, Oct 12: on desk c Claude's seven cards ran one
// column 2,459 px long at 3828 with most of the page empty; hitstop's rule by width, 0360fc4). The plan provider with the
// tallest rail may take k columns, its cards cut down them (cutRail), when that makes the tallest column shorter; the rest
// pack into the other columns, each into the shortest so far. Its columns keep two cards or more, one column is always left
// for the others and Add provider, and a split that saves under 15% of the height isn't made, so a full board keeps one
// column each and 1440 (two columns) never splits. Split or not, that provider opens the board at the top of the first
// column, so the one with the most logins stands in one place at every width (at 1440 it fell to the foot of the second
// column, under the fold); the rest keep the order splice lists them in.
// The board's columns should stand near one height: the shortest column is at least this share of the tallest, so no column
// is left holding a third of the page beside walls of cards. A packing short of it costs more, in proportion to the miss.
const LEVEL = 0.7;
// Add provider stands under the shortest column while a provider is left to add; with all of them here it offers none
const addLeft = () => !!ui.addProv || CATALOG.some((c) => !data.some((p) => p.id === c.id));
const cost = (heights) => {
  const tall = Math.max(...heights), low = Math.min(...heights);
  // among the packings that are level enough, the shorter board wins, and then the more even one
  return tall * (1 + 2 * Math.max(0, LEVEL - (tall ? low / tall : 1))) + (tall - low) * 0.01;
};
// [ps] packed down [n] columns. Each goes into the shortest column so far, then single moves and swaps between columns are
// kept while they bring the board's columns (with [beside], the heights of columns that stand next to them) closer to level.
// Within a column the providers keep the order splice lists them in. [pin]: the first provider stays at the top of the first column.
function pack(ps, n, w, { beside = [], pin = false, add: withAdd = addLeft() } = {}) {
  const cols = DRAW.packCols(ps, n, w);
  const at = new Map(); // provider -> its column
  cols.forEach((c, i) => c.items.forEach((p) => at.set(p, i)));
  const heights = () => {
    const h = Array(n).fill(0);
    const held = Array(n).fill(0);
    for (const [p, i] of at) { h[i] += w(p); held[i]++; }
    for (let i = 0; i < n; i++) if (held[i]) h[i] -= REM.prov; // no gap under a column's last provider
    if (withAdd) h[h.indexOf(Math.min(...h))] += REM.addProv; // Add provider ends the shortest column
    return h;
  };
  const score = () => cost([...beside, ...heights()]);
  let best = score(), better = true;
  // few enough providers: every way of dealing them into the columns is weighed, and the most level one stands
  const free = ps.filter((p, i) => !(pin && i === 0));
  if (n > 1 && n ** free.length <= 400000) {
    const keep = new Map(at);
    const deal = (i) => {
      if (i === free.length) { const sc = score(); if (sc < best - 1e-6) { best = sc; for (const [p, c] of at) keep.set(p, c); } return; }
      for (let c = 0; c < n; c++) { at.set(free[i], c); deal(i + 1); }
    };
    deal(0);
    for (const [p, c] of keep) at.set(p, c);
    better = false;
  }
  const movable = ps.filter((p, i) => !(pin && i === 0));
  while (better) {
    better = false;
    for (const p of movable) {
      const from = at.get(p);
      for (let to = 0; to < n; to++) {
        if (to === from) continue;
        at.set(p, to);
        const s = score();
        if (s < best - 1e-6) { best = s; better = true; break; }
        at.set(p, from);
      }
      for (const q of movable) {
        const a = at.get(p), b = at.get(q);
        if (a === b || ps.indexOf(q) < ps.indexOf(p)) continue;
        at.set(p, b); at.set(q, a);
        const s = score();
        if (s < best - 1e-6) { best = s; better = true; } else { at.set(p, a); at.set(q, b); }
      }
    }
  }
  const out = Array.from({ length: n }, () => ({ w: 0, items: [] }));
  for (const p of ps) { const c = out[at.get(p)]; c.items.push(p); c.w += w(p); }
  const add = out.reduce((m, c) => (c.w < m.w ? c : m)); if (withAdd) add.w += REM.addProv;
  return { cols: out.map((c) => c.items.map((p) => p.id)), add: out.indexOf(add), heights: out.map((c) => c.w - (c.items.length ? REM.prov : 0)) };
}
// how many columns the board's width holds: one per 40rem with the gap between, the board's own rule at every width
function colCount() { return DRAW.colsFit(board, 40); }
// EACH PROVIDER KEEPS ITS COLUMN FROM VISIT TO VISIT (Oct 27): the packing is kept with the providers and cards it was made
// for, and the next visit with the same ones at the same width lands each provider where he last found it, however its
// cards' rows have grown or shrunk since; a provider or a card added or gone packs the board again, and the providers
// slide to their new columns (render).
const PACK_KEY = "splice-accounts-packing";
const PACK_RULE = 17; // a packing or a ghost shape kept from an older rule packs again
function keptPacking(n, ids) {
  let kept = null;
  try { kept = JSON.parse(localStorage.getItem(PACK_KEY) || "null"); } catch { kept = null; }
  return kept && kept.rule === PACK_RULE && kept.n === n && kept.ids === ids && Array.isArray(kept.cols) ? kept : null;
}
// THE PACKING, CHECKED AGAINST THE BOARD AS IT STANDS. The packing is made from heights worked out before anything is drawn; once the
// sessions have landed and every provider stands at the height it will keep, each is measured and the board is dealt again on
// those heights, so the columns end level (the shortest at least LEVEL of the tallest) whatever the estimates missed. It is done
// once for a packing: the result is kept like any other, so the next visit lands there and nothing moves again.
function settle(L) {
  if (L.settled || unheard || !atRest() || ui.probing || !data.every((p) => p.accounts.every((a) => a.sessions !== null))) return false;
  L.settled = true;
  const px = parseFloat(getComputedStyle(document.documentElement).fontSize), sec = (id) => board.querySelector(`[data-key="prov:${CSS.escape(id)}"]`);
  const rem = (el) => (el ? el.getBoundingClientRect().height / px : 0);
  const big = L.wide ? findProv(L.wide.id) : null, wideEl = big ? sec(big.id) : null;
  const beside = wideEl ? [...wideEl.querySelectorAll(":scope > .rails > .abl-col")].map((c) => (c.getBoundingClientRect().bottom - wideEl.getBoundingClientRect().top) / px) : [];
  const items = data.filter((p) => p !== big).map((p) => ({ id: p.id })), at = new Map(data.map((p) => [p.id, rem(sec(p.id)) + REM.prov]));
  const first = L.cols[0]?.[0], r = big ? pack(items, L.n - L.wide.k, (p) => at.get(p.id), { beside })
    : pack([...items.filter((p) => p.id === first), ...items.filter((p) => p.id !== first)], L.n, (p) => at.get(p.id), { pin: true });
  const now = JSON.stringify(L.cols);
  if (JSON.stringify(r.cols) === now && r.add === L.addAt) { try { localStorage.setItem(PACK_KEY, JSON.stringify(L)); } catch { /* a full store packs again next visit */ } return false; }
  layout = { ...L, cols: r.cols, addAt: r.add, settled: true };
  try { localStorage.setItem(PACK_KEY, JSON.stringify(layout)); } catch { /* a full store packs again next visit */ }
  return true;
}
function columns() {
  const n = colCount();
  // laid out again when the width, the providers or a plan provider's count of cards changes, never for an open editor
  const ids = data.map((p) => p.id).join() + "|" + data.map((p) => (p.kind === "plan" ? laneAccts(p, laneOf(p)).length : 0)).join();
  // the packing weighs nothing the sessions answer, so it is the same before they land and after
  if (!layout || layout.n !== n || layout.ids !== ids) {
    layout = keptPacking(n, ids) || packed(n, ids);
    try { localStorage.setItem(PACK_KEY, JSON.stringify(layout)); } catch { /* a full store packs again next visit */ }
  }
  return layout;
}
const packed = (n, ids) => ({ rule: PACK_RULE, n, ids, ...packShape(data.map(packItem), n) });
// The packing rule on providers given as heights ({ id, plan, strips, hs: each card's rem, Add account last on a plan's
// rail}), shared by the board and by a first visit's ghosts, so the ghosts stand where the board will land
function packShape(items, n) {
  const w = (p) => REM.head + p.sh.reduce((t, h) => t + h, 0) + runRem(p.hs) + REM.prov;
  const big = items.filter((p) => p.plan).reduce((m, p) => (!m || runRem(p.hs) > runRem(m.hs) ? p : m), null);
  // a split is weighed against the board packed in splice's own order, as it always was; unsplit, the big one leads
  const one = pack(big ? [big, ...items.filter((p) => p !== big)] : items, n, w, { pin: !!big });
  let best = { k: 1, cuts: null, ...one, score: cost(one.heights) };
  if (big && n > 1) {
    const rest = items.filter((p) => p !== big), hs = big.hs;
    for (let k = 2; k <= Math.min(n - 1, Math.floor(hs.length / 2)); k++) {
      // over k columns its strips are dealt down them in turn, and each column carries its strips, then its run of cards
      const lead = Array.from({ length: k }, (_, i) => big.sh.filter((_, j) => j % k === i).reduce((t, h) => t + h, 0)), cut = cutRail(hs, k, 2, lead), bounds = [0, ...cut.cuts, hs.length];
      const wide = bounds.slice(1).map((e, i) => REM.head + lead[i] + runRem(hs.slice(bounds[i], e)));
      const r = pack(rest, n - k, w, { beside: wide });
      const score = cost([...wide, ...r.heights]);
      if (score < best.score * 0.85) best = { k, cuts: cut.cuts, ...r, score }; // 15% better or no split
    }
  }
  return { wide: best.k > 1 ? { id: big.id, k: best.k, cuts: best.cuts } : null, cols: best.cols, addAt: best.add };
}
addEventListener("resize", () => {
  if (!readOnce) { ghostBoard(); return; }
  const n = layout?.n; if (columns().n !== n) render();
});

// ---------- before splice answers: the board as it stood last time, in ghosts ----------
// THE FIRST FRAME STANDS WHERE THE BOARD WILL LAND (Oct 26). The board was empty until all seven reads answered, 4 s and
// more on his machine, then fell in at once; a splice that did not answer left only Add provider. Each visit now keeps
// the shape it drew (its columns, each provider's strips and cards, each card's rows, doors and height), never a name
// or a figure, and the next one draws that shape as ghosts until splice answers, each at the height it had, so nothing
// moves when the board lands. A first visit, or a width the last one never drew, gets a plain shape of the same parts.
const SHAPE_KEY = "splice-accounts-shape";
let shapeSaved = "";
const remOf = (el) => (el ? +(el.getBoundingClientRect().height / parseFloat(getComputedStyle(document.documentElement).fontSize)).toFixed(2) : 0);
function shapeOf(L) {
  const doors = (a) => (a.sessions === null ? DOORS_SEEN.get(a.id) ?? 0 : a.sessions?.length || 0);
  const prov = (p) => {
    const sec = board.querySelector(`[data-key="prov:${CSS.escape(p.id)}"]`);
    // each card: its window rows (its Next row too), its doors, its height, the lines over its rows (the address, the
    // held line) and the move lines under its doors
    const card = (a) => {
      const el = sec?.querySelector(`[data-key="card:${CSS.escape(`${p.id}/${a.id}`)}"]`);
      // a login with no window at all is the one line "No limits reported" (-1), a signed-out one its outbox (0)
      const wins = (a.windows?.length || 0) + (a.models?.length || 0);
      const rows = p.kind !== "plan" || a.out ? 0 : wins ? wins + (el?.querySelector(".windows > .next") ? 1 : 0) : -1;
      const over = (el?.querySelector(":scope > .email") ? 1 : 0) + (el?.querySelector(".windows > .heldline") ? 1 : 0);
      return [rows, Math.min(SESS_SHOWN, doors(a)), remOf(el), over, el?.querySelectorAll(":scope > .moves > .moveline").length || 0];
    };
    return { id: p.id, k: p.kind, d: [...(sec?.querySelectorAll(".meter.day") || [])].map(remOf), a: (p.kind === "plan" ? ordered(p, laneOf(p)) : p.accounts).map(card) };
  };
  return { rule: PACK_RULE, n: L.n, wide: L.wide ? { k: L.wide.k, cuts: L.wide.cuts, p: prov(findProv(L.wide.id)) } : null, cols: L.cols.map((ids) => ids.map((id) => prov(findProv(id)))),
    doors: Object.fromEntries(data.flatMap((p) => p.accounts).map((a) => [a.id, doors(a)]).filter(([, k]) => k > 0)) };
}
// the board at rest: an open form, menu, reason or list of doors is not the shape the next visit lands on
const atRest = () => !(ui.menu || ui.rename || ui.replace || ui.editor || ui.naming || ui.addProv || ui.moving || Object.keys(ui.signin).length || ui.why.size || ui.doors.size || ui.moves.size);
const tall = (h) => (h ? `;min-height:${h}rem` : "");
const ghostDoors = (n) => (n ? `<div class="onlogin" aria-hidden="true">${Array.from({ length: n }, (_, j) => `<span class="sdoor gdoor"><i class="ghost lampg"></i>${GHOST.bar(4.2 + (j % 3) * 1.3)}</span>`).join("")}</div>` : "");
function ghostCard(kind, [rows, doors, h, over = 0, moves = 0], k) {
  const lines = (n, w, cls) => Array.from({ length: n }, () => `<span class="${cls}">${GHOST.bar(w)}</span>`).join("");
  const body = kind === "key" || rows < 0 ? `<div class="keysrc">${GHOST.bar(13)}</div>` : kind === "local" ? `<div class="local-model lmc-body"><div class="lmc-row">${GHOST.bar(12)}</div><div class="lmc-foot">${GHOST.bar(7.6, "chip")}${GHOST.bar(5.5, "chip")}</div></div>`
    : rows ? `<div class="windows">${lines(Math.max(0, over - 1), 12, "heldline")}${Array.from({ length: rows }, WINDOW_GHOST).join("")}</div>` : `<div class="outbox">${GHOST.bar(5.4, "chip")}</div>`;
  return `<article class="card gcard ghosted" style="--i:${Math.min(k, 3)}${tall(h)}">${kind === "plan" ? `<span class="socket"></span>` : ""}`
    + `<div class="top">${GHOST.bar(7.4, "big")}<span class="slot">${GHOST.bar(6.2, "chip")}</span></div>${over ? lines(1, 9.5, "email") : ""}${body}${ghostDoors(doors)}`
    + `${moves ? `<div class="moves"><span class="mkick">${GHOST.bar(3.2)}</span>${Array.from({ length: moves }, () => `<div class="moveline">${GHOST.bar(15)}</div>`).join("")}</div>` : ""}</article>`;
}
// a day with no request is one row on the board (meterHtml), so its ghost is one row; the height the strip kept says which it was
const ghostStrip = (h) => {
  const kicker = (w) => `<span class="label ads-k">${w}</span>`, row = (w) => `${kicker(w)}<div class="bar ads-req">${GHOST.track()}</div><span class="ads-words">${GHOST.bar(7.4)}</span>`;
  if (h && h < 7) return `<div class="meter day ghosted idle" style="${tall(h).slice(1)}"><span class="label">Day</span><div class="dayline"><span class="ads-fig ads-ghost">${GHOST.bar(9.5)}</span></div><span class="when">${GHOST.ring()}${GHOST.bar(6.2)}</span></div>`;
  return `<div class="meter day ghosted" style="${tall(h).slice(1)}"><span class="label">Day</span><div class="dayline"><span class="ads-fig ads-ghost">${GHOST.fig(5.6)}</span><span class="ads-unit">${GHOST.bar(9.5)}</span></div>`
    + `<span class="when">${GHOST.ring()}${GHOST.bar(6.2)}</span>${row("Requests")}${!h || h > REM.strip - 1 ? row("Dollars") : ""}<span class="dayrun">${GHOST.track()}</span></div>`;
};
function ghostProvider(g, span = 1, cuts = null) {
  // a shape kept before the heights were (a count of strips) still draws, at the plain height
  const dayHs = Array.isArray(g.d) ? g.d : Array.from({ length: g.d || 0 }, () => 0), strips = dayHs.map((h) => ghostStrip(h)).join(""), cards = (g.a || []).map((x, k) => ghostCard(g.k, x, k));
  const head = `<header><h2><span class="blot"></span>${GHOST.bar(6.4, "big")}</h2></header>`;
  // a ghost provider carries the key its landed provider will, so the board that lands over it slides each provider from
  // where its ghost stood to where it settles (render) and nothing is moved in one frame
  const key = g.id ? ` data-key="prov:${esc(g.id)}"` : "";
  if (g.k !== "plan") return `<section class="provider ghostp"${key}>${head}${strips}<div class="rail plain">${cards.join("")}</div></section>`;
  cards.push(`<div class="add ghosted" style="--i:3"><span class="socket"></span>${GHOST.bar(7)}</div>`); // Add account
  // a wide rail's ghosts are cut where its cards were
  const rail = span > 1 ? `<div class="rails" style="--span:${span}">${wideCols(runs(cards, cuts, span), Array.from({ length: span }, (_, i) => dayHs.filter((_, k) => k % span === i).map((h) => ghostStrip(h))))}</div>`
    : `<div class="rail">${cards.join("")}</div>`;
  return `<section class="provider ghostp${span > 1 ? ` wide" style="--span:${span}` : ""}"${key}>${head}${span > 1 ? "" : strips}${rail}</section>`;
}
// A FIRST VISIT, or a width the last one never drew, has no kept shape: its ghosts come from splice's own list of commands
// as soon as that answers (it answers well before the rest), one provider per family with a strip per command. Where the
// list of logins has answered with it, each plan login is a card with the rows its reading holds (a window per figure, a
// model's slice, the held line, the address), and the board is packed and its widest rail cut by the board's own rule
// (packShape), so the ghosts stand where the board lands; without it, two cards per plan command. Until the list
// answers, one plain provider per column.
let registry = null, logins = null, askedRegistry = false;
// each login of a plan provider as a ghost card, in the order the board draws them, from the same login and the same height
// rule the board packs on: [rows of windows, doors, its own height, lines over the rows, moves, packing height]
function ghostRail(p) {
  const rail = ordered(p, laneOf(p));
  return rail.map((a, i) => {
    const next = i === 0 && rail.length > 1;
    const wins = a.windows.length + (a.windows.some((w) => w.label === "Week") ? a.models.length : 0);
    const rows = a.out ? 0 : wins ? wins + (next ? 1 : 0) : -1;
    return [rows, 0, 0, (a.email ? 1 : 0) + (held(a) ? 1 : 0), 0, loginRem(a, next)];
  });
}
function shapeFromRegistry(n, kept = new Map()) {
  const provs = new Map();
  for (const h of registry) {
    const id = (FAMILY[h.family] || { id: h.family || h.key }).id;
    const kind = h.family === "local" || h.family === "vast" ? "local" : h.authKind === "api-key" ? "key" : "plan";
    const p = provs.get(id) || { id, kind, cmds: [], accounts: [] };
    p.cmds.push({ cmd: h.label || h.key, head: h.key, mode: "soonest", pin: null, budget: null, order: [] });
    provs.set(id, p);
  }
  const ps = [...provs.values()].map((p) => {
    const cmds = p.cmds.length, heads = new Set(p.cmds.map((c) => c.head));
    let a;
    if (p.kind === "plan" && logins) {
      for (const row of logins) if ((row.heads || []).some((x) => heads.has(x))) { const login = planAccount(row); if (login) p.accounts.push(login); }
      for (const c of p.cmds) c.order = p.accounts.filter((x) => (x.row.heads || []).includes(c.head)).map((x) => x.id);
      a = ghostRail(p);
    } else if (p.kind === "plan") a = Array.from({ length: cmds + 1 }, () => [2, 0, 0, 0, 0, REM.card + 2 * REM.row]);
    else a = p.cmds.map(() => [0, 0, 0, 0, 0, p.kind === "local" ? REM.local : REM.card + REM.line]);
    // the strips' heights a visit at another width kept stand for the same commands here: an idle command is one row on the board
    const was = kept.get(p.id), count = p.kind === "local" ? 0 : cmds;
    return { id: p.id, k: p.kind, d: was?.length === count ? was : Array.from({ length: count }, () => 0), a };
  });
  const L = packShape(ps.map((g) => ({ id: g.id, plan: g.k === "plan", sh: g.d.map((h) => h || REM.strip),
    hs: [...g.a.map((x) => x[5]), ...(g.k === "plan" ? [REM.add] : [])] })), n);
  const byId = (id) => ps.find((g) => g.id === id);
  return { n, wide: L.wide ? { k: L.wide.k, cuts: L.wide.cuts, p: byId(L.wide.id) } : null, cols: L.cols.map((ids) => ids.map(byId)) };
}
// A first visit that has heard nothing yet draws the board most logins land as: the first plan provider is the longest and
// stands two columns wide on a wide screen (its strip over the first, its cards read down both), and the providers after it
// stack in the columns beside it, a plan's card or a model on this computer or a key under each. It is one honest guess at the
// shape, drawn only for the moment the daemon's own list takes, and replaced by the shape that list gives.
function plainShape(n) {
  const plan = (cards) => ({ k: "plan", d: [0], a: Array.from({ length: cards }, () => [2, 0]) });
  const key = { k: "key", d: [0], a: [[0, 0]] }, local = { k: "local", d: [], a: [[0, 0], [0, 0]] };
  if (n < 4) return { n, wide: null, cols: Array.from({ length: n }, (_, i) => [i % 2 ? key : plan(2)]) };
  const stacks = [[plan(1), local], [plan(1), plan(1), plan(1)], [key, key, local]];
  return { n, wide: { k: 2, cuts: [3], p: plan(5) }, cols: Array.from({ length: n - 2 }, (_, i) => stacks[i % stacks.length]) };
}
function ghostBoard() {
  let shape = null;
  try { shape = JSON.parse(localStorage.getItem(SHAPE_KEY) || "null"); } catch { shape = null; }
  const n = colCount();
  if (!shape || shape.n !== n || shape.rule !== PACK_RULE || !Array.isArray(shape.cols)) {
    const kept = new Map([...(shape?.wide ? [shape.wide.p] : []), ...(shape?.cols || []).flat()].filter((g) => g?.id && Array.isArray(g.d)).map((g) => [g.id, g.d]));
    shape = registry?.length ? shapeFromRegistry(n, kept) : null;
    if (!registry) {
      // nothing is drawn for the moment the list takes, so the ghosts do not land twice; a list that is slow to come
      // leaves the plain shape
      if (!askedRegistry) {
        askedRegistry = true;
        const plain = setTimeout(() => { registry = registry ?? []; if (!readOnce) ghostBoard(); }, 600);
        Promise.all([API.get("/api/status"), API.get("/api/accounts")]).then(([st, ac]) => {
          clearTimeout(plain);
          logins = ac.ok ? ac.body?.accounts || null : null;
          registry = st.ok ? st.body?.registry || [] : registry ?? [];
          if (!readOnce) ghostBoard();
        });
      }
      board.setAttribute("aria-busy", "true");
      return;
    }
  }
  if (!shape) shape = plainShape(n);
  for (const [id, k] of Object.entries(shape.doors || {})) if (!DOORS_SEEN.has(id)) DOORS_SEEN.set(id, k);
  board.innerHTML = DRAW.boardCols([...(shape.wide ? [{ span: shape.wide.k, body: ghostProvider(shape.wide.p, shape.wide.k, shape.wide.cuts) }] : []),
    ...shape.cols.map((ps) => ({ body: ps.map((g) => ghostProvider(g)).join("") }))]);
  board.setAttribute("aria-busy", "true");
}

// ---------- the page head: Read again, and splice not answering ----------
// A read splice did not answer keeps the board it held and says so here, once, beside the act that asks again, which is
// then Try again; the page also asks again on its own every 5 s (askAgainSoon).
function headState() {
  const say = document.getElementById("unheard"), btn = document.querySelector('[data-act="probe"]');
  if (!say || !btn) return;
  const at = unheard ? String(unheard.at) : "";
  if (say.dataset.at !== at) { // once per stretch of silence, so a redraw never plays its arrival again
    say.dataset.at = at; say.hidden = !unheard;
    say.innerHTML = unheard ? `${ICON.bang}<span>splice not answering</span><span class="since">since <b>${clock(new Date(unheard.at))}</b></span>`
      + `<svg class="asking go" viewBox="0 0 24 24" aria-hidden="true"><circle class="trk" cx="12" cy="12" r="9" pathLength="100"/><circle class="arc" cx="12" cy="12" r="9" pathLength="100"/></svg>` : "";
  }
  const w = btn.querySelector(".w");
  if (w) w.textContent = unheard ? "Try again" : "Read again";
  // no day on the board read: said here once, beside the act that reads again, with splice's reason; while splice is not
  // answering at all, the line above already says why
  const day = document.getElementById("dayunread"), said = !!DAY_UNREAD && !unheard;
  if (day && day.dataset.said !== String(said)) {
    day.dataset.said = String(said); day.hidden = !said;
    day.innerHTML = said ? `${ICON.bang}<span>Day not read</span>` : "";
  }
}

// ---------- render, with every moved object sliding from where it was ----------
// Chrome fires focusout on a focused field as the board replaces it; `drawing` keeps that from reading as him leaving it.
let drawing = false, landed = false;
const DAYS_GROWN = new Set(); // the Day rows whose hours have risen in once, so a redraw never plays it again
// THE FIELDS BEING TYPED IN OUTLIVE A REDRAW: the board is drawn again whenever a read lands (the sessions, the day, a
// refusal), and a field drawn from the data lost what he had typed and where his cursor was. The budget's amount is
// kept by the editor itself (ui.editor.cap), so only its focus is carried. A key goes back into its field as a value,
// never into the page's markup.
function fieldSel(f) {
  if (f.matches(".rename")) return `.field.rename[data-s="${CSS.escape(f.dataset.s || "")}"]`;
  if (f.matches(".code-in")) return `.code-in[data-t="${CSS.escape(f.dataset.t || "")}"]`;
  // a key field is told apart by its login; the new provider's, which has none, by that
  if (f.id === "key-in") return f.dataset.a ? `#key-in[data-a="${CSS.escape(f.dataset.a)}"]` : "#key-in:not([data-a])";
  return f.id ? `#${f.id}` : f.matches(".add-name") ? ".add-name" : null;
}
// the act or door that held the focus, by what it does and what it acts on, so the same one holds it after the redraw
function focusSel(el) {
  if (!el || el === board || !board.contains(el) || el.matches("input")) return null;
  if (el.dataset.act) return ["act", "p", "a", "c", "s", "t", "k", "v"].filter((k) => el.dataset[k] != null).map((k) => `[data-${k}="${CSS.escape(el.dataset[k])}"]`).join("");
  if (el.matches(".dayrun")) { const m = el.closest("[data-meter]")?.dataset.meter; return m ? `[data-meter="${CSS.escape(m)}"] .dayrun` : null; }
  if (el.matches("a[href]")) return `a[href="${CSS.escape(el.getAttribute("href"))}"]`;
  return null;
}
function keepFields() {
  const kept = [...board.querySelectorAll("input.field")].map((f) => ({ sel: fieldSel(f), v: f.value, own: f.id !== "cap-in",
    focus: f === document.activeElement, s: f.selectionStart, e: f.selectionEnd })).filter((x) => x.sel);
  const other = focusSel(document.activeElement);
  return other ? [...kept, { sel: other, focus: true, act: true }] : kept;
}
function putFields(kept) {
  for (const k of kept) {
    if (k.act) { board.querySelector(k.sel)?.focus({ preventScroll: true }); continue; }
    const f = board.querySelector(k.sel);
    if (!f) continue;
    // a key put back in its field turns Save on, and leaves any refusal the draw itself made standing
    if (k.own && f.value !== k.v && !f.readOnly) { f.value = k.v; if (f.id === "key-in") f.parentElement.querySelector('[data-act="save-replace"], [data-act="save-key"]')?.toggleAttribute("disabled", !k.v.trim()); }
    if (k.focus) { f.focus({ preventScroll: true }); try { f.setSelectionRange(k.s, k.e); } catch { /* a field with no caret */ } }
  }
}
// EVERY METER THAT CHANGED SLIDES FROM ITS LAST WIDTH TO ITS NEW ONE over 240 ms, when a read lands with new figures,
// so what moved is what changed; a meter that grows from nothing (ui.grow) or is drawn for the first time is left alone
const barId = (b) => { const h = b.closest("[data-a], [data-meter], [data-grow]"); return h && (h.dataset.a || h.dataset.meter || h.dataset.grow); };
function barsNow() {
  const seen = new Map(), out = new Map();
  for (const b of board.querySelectorAll(".bar > b[data-v]")) {
    const id = barId(b); if (!id) continue;
    const i = seen.get(id) ?? 0; seen.set(id, i + 1);
    out.set(`${id}#${i}`, b.style.width);
  }
  return out;
}
function slideBars(before, grown) {
  if (!before.size || window.matchMedia("(prefers-reduced-motion: reduce)").matches) return;
  const seen = new Map(), moving = [];
  for (const b of board.querySelectorAll(".bar > b[data-v]")) {
    const id = barId(b); if (!id) continue;
    const i = seen.get(id) ?? 0; seen.set(id, i + 1);
    const from = before.get(`${id}#${i}`);
    if (!from || from === b.style.width || grown?.has?.(id)) continue;
    moving.push([b, b.style.width]); b.style.transition = "none"; b.style.width = from;
  }
  if (!moving.length) return;
  void board.offsetWidth;
  for (const [b, to] of moving) { b.style.transition = "width var(--t-snap) var(--e-out)"; b.style.width = to; }
}
function render({ flip = false } = {}) {
  if (!board.isConnected || drag) return;
  headState();
  // a splice that has not answered once this visit keeps the ghost board, never an empty one offering Add provider
  if (!readOnce) { if (!board.querySelector(".ghostp")) ghostBoard(); return; }
  board.removeAttribute("aria-busy");
  // what slides: every object after an act (flip), and else the providers alone, which slide only when the board packs
  // them into other columns
  const sliding = flip ? "[data-key]" : '[data-key^="prov:"]', before = new Map(), was = layout;
  for (const el of board.querySelectorAll(sliding)) before.set(el.dataset.key, el.getBoundingClientRect());
  const L = columns(), typed = keepFields(), barsBefore = barsNow(), entered = ui.enter, grown = ui.grow;
  const switches = new Map([...board.querySelectorAll(".abl-sw[data-sw]")].map((e) => [e.dataset.sw, e.dataset.at]));
  drawing = true;
  // Add provider sits under the shortest column while a provider is left to add; with all of them here it has none to offer
  const addable = addLeft();
  board.innerHTML = DRAW.boardCols([...(L.wide ? [{ span: L.wide.k, body: providerHtml(findProv(L.wide.id), L.wide.k) }] : []),
    ...L.cols.map((ids, i) => ({ body: ids.map((id) => providerHtml(findProv(id))).join("") + (addable && i === L.addAt ? addProviderHtml() : "") }))]);
  drawing = false;
  // the lit part of an order switch slides from the choice it stood on to the one it now stands on, over 240 ms
  for (const sw of board.querySelectorAll(".abl-sw[data-sw]")) {
    const was = switches.get(sw.dataset.sw);
    if (!was || was === sw.dataset.at) continue;
    sw.style.setProperty("--abl-x", was === "mine" ? "100%" : "0%");
    void sw.offsetWidth;
    sw.style.removeProperty("--abl-x");
  }
  // the board it held, while splice does not answer: still there, quieter, under the line in the head that says so
  board.classList.toggle("unheard", !!unheard);
  // THE RESET COLUMN IS ONE WIDTH ON THE WHOLE BOARD: as wide as its longest time, so every card's bars and every Day
  // row's end on one line in their column, whatever each one's own reset says
  board.style.setProperty("--when-n", String(Math.max(15, ...[...board.querySelectorAll(".when")].map((e) => e.textContent.trim().length))));
  // the first board after the ghosts rises into their places once
  if (!landed) { landed = true; board.classList.add("landing"); setTimeout(() => board.classList.remove("landing"), 700); }
  // the meters take their widths, growing over 240 ms the ones that changed; while splice asks the providers again the
  // last figure stays on screen and the track stripes (kit.js DRAW.paint, which every page now shares)
  DRAW.paint(board, { grow: ui.grow, reading: ui.probing });
  if (!ui.probing && grown !== "all") slideBars(barsBefore, grown);
  // the logins at their limit ride Accounts' link on every page (kit.js navMark), each until its window comes back
  if (!unheard) {
    // counted against the logins whose limits splice reads, as Usage and Settings count them (lead look, Oct 29: the bar read
    // "of 17" with keys and local servers in it, beside "of 14" on Usage and "of 9" on Settings)
    const read = data.flatMap((p) => p.accounts).filter((a) => !a.out && (a.windows || []).length), out = read.filter(held);
    navMark("accounts.html", out.length, "limit", out.map((a) => Math.max(0, ...a.windows.filter(atLimit).map((w) => w.resetMs ?? 0)) || null), read.length);
  }
  // a day's hours rise in with the bars, when the fresh reading lands (ui.grow), and a day that lands after it rises on
  // first sight; while the page is still asking the providers they stand, as the striped bars do
  for (const run of board.querySelectorAll(".dayrun[role=group]")) {
    const id = run.closest("[data-meter]")?.dataset.meter, asked = ui.grow === "all" || !!ui.grow?.has?.(id);
    if (id && (asked || (!DAYS_GROWN.has(id) && !ui.probing))) { run.classList.add("grow"); DAYS_GROWN.add(id); }
  }
  ui.grow = null;
  ui.enter = null; // a form or a menu rises in on the draw that opened it, and stands still on every later one
  ui.shook = null; // a refusal shakes on the draw that first said it, and stands still on every later one
  // a move rises once: the ones drawn by now are known, and only one that arrives while the page is open pops, never the
  // moves of the first landing (the doors under them are still being asked until the sessions answer)
  for (const el of board.querySelectorAll(".moveline[data-key]")) MOVES_SEEN.add(el.dataset.key);
  if (!movesPrimed && data.every((p) => p.accounts.every((a) => a.sessions !== null))) movesPrimed = true;
  watchForRoll(); // whatever is drawn now, the page knows when the soonest of these windows rolls
  if (ui.pulse) { board.querySelectorAll(`.card[data-a="${ui.pulse}"]`).forEach((el) => el.classList.add("pulse-once")); ui.pulse = null; }
  const menu = board.querySelector(".menu"); // a menu that would cross the bottom of the window opens upward
  if (menu && menu.getBoundingClientRect().bottom > innerHeight - 12) menu.classList.add("up");
  // a field opened by this draw takes the focus with its text picked; one he was typing in keeps what he typed, his focus
  // and his place in it, whatever redrew the board under him (the sessions or the day landing, a refusal)
  const opened = entered === "rename" ? board.querySelector(".field.rename")
    : entered === "key" ? board.querySelector(`.card[data-key="card:${CSS.escape(ui.replace ?? "")}"] #key-in`) ?? keyIn() : null;
  if (opened) { opened.focus(); opened.select?.(); } else putFields(typed);
  if (settle(L)) { render({ flip }); return; }
  const shape = atRest() && !ui.probing ? JSON.stringify(shapeOf(L)) : shapeSaved;
  if (shape !== shapeSaved) { shapeSaved = shape; try { localStorage.setItem(SHAPE_KEY, shape); } catch { /* a full store keeps the last shape */ } }
  if (!flip && L === was) return;
  const moved = new Map();
  for (const el of board.querySelectorAll(sliding)) {
    const a = before.get(el.dataset.key);
    if (!a) continue;
    const b = el.getBoundingClientRect();
    let dx = a.left - b.left, dy = a.top - b.top;
    const host = el.parentElement.closest("[data-key]");
    if (host && moved.has(host)) { dx -= moved.get(host)[0]; dy -= moved.get(host)[1]; }
    moved.set(el, [a.left - b.left, a.top - b.top]);
    if (Math.abs(dx) < 1 && Math.abs(dy) < 1) continue;
    el.style.transition = "none"; el.style.transform = `translate(${dx}px, ${dy}px)`;
    requestAnimationFrame(() => requestAnimationFrame(() => { el.style.transition = "transform var(--t-move) var(--e-io)"; el.style.transform = ""; }));
  }
}
const findProv = (id) => data.find((p) => p.id === id);
const findAcct = (id) => data.flatMap((p) => p.accounts).find((a) => a.id === id);
const provOf = (a) => data.find((p) => p.accounts.includes(a));
function findCmd(name) {
  for (const p of data) { const c = p.cmds.find((x) => x.cmd === name); if (c) return { p, c }; }
  return {};
}
// ---------- a fresh reading every time the page opens: the refresh glyph turns, then the bars grow ----------
// splice asks every provider for its limits (POST /api/usage/probe), then the page reads everything again.
async function probe() {
  const glyph = document.querySelector('[data-act="probe"]');
  // what splice last knew is drawn at once and marked as being checked: the page never reads empty while every
  // provider is asked, which takes up to half a minute on his machine (Marlin, splice-lead, Oct 11); before its first
  // answer the board stands as its ghosts (render)
  if (!data.length) { render(); await load(); }
  ui.probing = true; glyph.classList.add("turning"); render();
  await API.post("/api/usage/probe");
  await load();
  // the first reading of a visit grows every bar from nothing; a later one slides only the bars whose figure changed
  ui.probing = false; glyph.classList.remove("turning"); ui.grow = probed ? null : "all"; probed = true; render();
}
let probed = false;
// THE BOARD STAYS CURRENT WHILE IT IS OPEN (Oct 27): it read once when the page opened and then only when he acted, so a
// board left open showed the logins serving, the sessions on them and the day as they were an hour ago, and a splice
// that stopped answering went unsaid. It reads again every minute while it is in view and at rest (no form, menu or
// drag open, no hour being read under the pointer), and at once when he comes back to it after a minute away; what
// changed slides into place and the rest stands still.
const KEEP_MS = 60000;
let readAt = Date.now();
async function keepCurrent() {
  if (document.visibilityState !== "visible" || !readOnce || unheard || ui.probing || drag || ui.orderSaving || ui.removing) return;
  if (!atRest() || board.querySelector(".dayrun.at") || Date.now() - readAt < KEEP_MS - 1000) return;
  readAt = Date.now();
  await load();
  if (atRest() && !drag) render();
}
setInterval(keepCurrent, KEEP_MS);
document.addEventListener("visibilitychange", keepCurrent);
// ---------- a window that rolls while the page is open ----------
// The row goes empty the moment its reset passes, and the page reads again right then, so the true figure takes its
// place within seconds (Marlin, Oct 10, 2026). Without this the empty row would sit there until someone pressed the
// refresh glyph: honest, and useless. The read is the same one the page opens with, which asks every provider at
// once, because a reset that has passed is usually a reset several accounts on that plan share.
let rollWatch = null;
function watchForRoll() {
  clearTimeout(rollWatch);
  const reads = data.flatMap((p) => p.accounts || []).flatMap((a) => [...(a.windows || []), ...(a.models || [])]);
  const soonest = Math.min(...reads.map((w) => w.resetMs).filter((ms) => ms != null && ms > Date.now()));
  if (!Number.isFinite(soonest)) return; // every window here has either rolled already or reported no reset at all
  // A second past the moment, so what the read finds is a window that HAS rolled. setTimeout cannot hold more than
  // 24.8 days and a month window's reset is further out than that, so a wait past the cap re-arms instead of reading.
  const wait = Math.min(soonest + 1000 - Date.now(), 0x7fffffff);
  rollWatch = setTimeout(() => (Date.now() >= soonest ? probe() : watchForRoll()), Math.max(wait, 1000));
}

// After an act: read again, and let what changed slide into place (grow: the account or command whose bars refill). An
// act splice refused is said ([no]) once the board it left is read, so its shake plays on the board that stays.
async function refresh(grow = null, no = null) {
  await load();
  ui.grow = grow ? new Set([grow]) : null;
  no?.();
  render({ flip: true });
}

// ---------- adding an account, inside the tile ----------
// A typed name is kept against the ref the new login proves once it lands (AccountNameRoute.kt); Enter alone signs in
// with no chosen name, and the login reads splice's default for its plan (Marlin, Oct 12). The sign-in itself still mints
// its own folder label, which stays the pool selector.
function startAdd(p, cmd, field) {
  const v = field.value.trim(), err = v ? refNameError(p, {}, v) : null;
  if (err) { ui.naming = { cmd, draft: v, err }; render(); return; }
  const { c } = findCmd(cmd), before = new Set(p.accounts.map((x) => x.row?.ref).filter(Boolean));
  ui.naming = null;
  // an OAuth command's added login takes a new place in its pool ("auto", OAuthAccountFiles' next free label); sent with no
  // label it signed the FIRST login in again, over its file, so a second Muse account replaced the spent one rather than
  // joining beside it (Oct 13, 5:38 AM). The first login of a command has no pool yet and still lands as its first.
  const pooled = /^(muse|kimi)-oauth$/.test(c.authKind ?? "") && p.accounts.some((x) => x.row?.heads?.includes(c.head));
  startSignin(`add:${cmd}`, { start: `/api/auth/${encodeURIComponent(c.head)}/login`, body: pooled ? { label: "auto" } : {}, poll: c.head, name: v || null, before });
}
async function nameAdded(how) {
  const added = data.flatMap((p) => p.accounts).find((x) => x.row?.ref && !how.before.has(x.row.ref) && x.row.heads.includes(how.poll));
  if (!added) return; // the sign-in landed on a login already here: its name stays as it was
  if ((await API.put(namePath(added.row.ref), { name: how.name })).ok) await refresh(added.id);
}
// ---------- adding a provider: one add on splice's own route (AddRoutes.kt), saved once it can sign in ----------
// The provider picks the profile splice adds it from (GET /api/add/profiles). Saving writes its command and restarts
// splice (AddRoutes.kt:115), so the tile waits until splice answers again with the provider on the board.
const PROFILE = { claude: "claude", gpt: "codex", grok: "grok", kimi: "kimi", muse: "muse", router: "openrouter", deepseek: "deepseek" };
async function pickProvider(c) {
  const res = await API.post("/api/add", { profile: PROFILE[c.id] });
  const add = res.ok ? res.body : null;
  ui.addProv = { id: c.id, stage: add?.sign_in_by === "key" ? "key" : "sign", add: add?.id || null, env: add?.key_env || null };
  if (ui.addProv.stage === "key") { render(); keyIn()?.focus(); return; }
  const done = () => saveAdd(c.id, "prov");
  if (add?.sign_in_by === "login") {
    startSignin("prov", { start: `/api/add/${encodeURIComponent(add.id)}/login`, watch: `/api/add/${encodeURIComponent(add.id)}`, done });
    return;
  }
  const again = () => { dropAdd(); pickProvider(c); }; // Try again opens the add afresh: there is no sign-in to restart
  ui.signin.prov = { how: { done, again }, state: "wait" }; render(); // nothing to sign in to (Claude's login is forwarded)
  // splice refuses a provider whose name or command is taken with a 409 naming that field and no failure kind
  // (AddRefusal.field): that reads Already added, not Not completed
  const taken = res.status === 409 && ["name", "command"].includes(res.body?.field);
  if (add) finishSignin("prov"); else failSignin("prov", taken ? { failure_kind: "already_added" } : res.body);
}
// The save, then splice's restart: true once the provider is back on the board. A refused save, or a splice that does
// not come back in a minute and a half, shows on the tile it was added from.
async function saveAdd(pid, target) {
  const res = await API.post(`/api/add/${encodeURIComponent(ui.addProv.add)}/save`);
  if (!res.ok) { if (target === "key") keyState(keyIn(), "Not saved"); else failSignin(target, res.body); return false; }
  ui.addProv.add = null; // saved: nothing left to discard
  for (let i = 0; i < 90; i++) {
    await new Promise((r) => setTimeout(r, 1000));
    await load();
    if (data.some((p) => p.id === pid)) return true;
  }
  if (target === "key") keyState(keyIn(), "Not saved"); else failSignin(target);
  return false;
}
function dropAdd() { // an add that will not be saved is closed on splice too
  if (ui.addProv?.add) API.del(`/api/add/${encodeURIComponent(ui.addProv.add)}`);
  ui.addProv = null;
}
async function saveKey(from) {
  const st = ui.addProv, field = keyIn(from);
  if (!st.add || !st.env) { keyState(field, "Not saved"); return; }
  const res = await API.put(`/api/keys/${encodeURIComponent(st.env)}`, { value: pasted(from) });
  if (!res.ok) { keyState(field, "Not saved"); return; }
  field.parentElement.querySelectorAll("button").forEach((b) => { b.disabled = true; });
  if (await saveAdd(st.id, "key")) { ui.addProv = null; await refresh(st.id); }
  else field.parentElement.querySelectorAll("button").forEach((b) => { b.disabled = false; });
}
// The board can hold several key fields at once: every key with no key, the one being replaced, a new provider's. Each act
// reads the field in its own box (the box of the act pressed), never the first one on the page.
const keyIn = (from) => from?.parentElement?.querySelector("#key-in") ?? board.querySelector('[data-key="addprov"] #key-in') ?? board.querySelector("#key-in");
const pasted = (from) => (keyIn(from)?.value ?? "").trim(); // the value goes one way: into the store, never back
// The key field's state, set in place so the pasted value is never written back into the page. Save stays off while the
// field is empty (KeyWrites.kt:26).
function keyState(field, word) {
  const save = field.parentElement.querySelector('[data-act="save-replace"], [data-act="save-key"]');
  let el = field.parentElement.querySelector(".key-err");
  if (save) save.disabled = !field.value.trim();
  if (!word) { el?.remove(); field.removeAttribute("aria-invalid"); field.removeAttribute("aria-describedby"); return; }
  if (!el) { el = Object.assign(document.createElement("span"), { className: "state limit key-err", id: "key-err" }); el.setAttribute("role", "status"); field.after(el); }
  el.textContent = word; field.setAttribute("aria-invalid", "true"); field.setAttribute("aria-describedby", "key-err");
  const box = field.parentElement; box.classList.remove("failed"); void box.offsetWidth; box.classList.add("failed");
}
// An order names only the accounts splice holds in this command's order (its effective_order): it refuses a whole order
// naming one it doesn't, such as a login place that never held a sign-in, and My order then fell back to Soonest reset.
const orderLabels = (c, list) => list.map((x) => labelOn(x, c.head)).filter((l) => c.orderable?.has(l));
// A REFUSED ACT SAYS SO IN TWO WORDS WHERE IT WAS PRESSED, and splice's own reason opens under them. splice writes these
// refusals for a person ("'X' could not be removed; its folder is still on disk", "this native login is busy"), and
// the reason is what tells a login that only needs a wait from one that needs his hand. The words stand alone; the
// reason is one press away, never a sentence on the card.
function mutationError(res, word) {
  const why = res.status === 0 ? "splice did not answer" : typeof res.body?.error === "string" ? res.body.error.trim() : "";
  return { word, why };
}
// A refusal where it happened: its word in the limit red where the act was, and splice's own reason printed beside it in the
// same place, readable with no press. Where splice gave no reason only the word shows, and none is made up. The reason is
// drawn by reasonHtml where the object has room for a line; [key] names this refusal.
function refusalHtml(err) {
  return DRAW.result(err.word, "bad");
}
const reasonHtml = (err, key, cls = "") => (err?.why
  ? `<p class="reason${cls ? ` ${cls}` : ""}${ui.shook === key ? " unfold" : ""}">${esc(err.why)}</p>` : "");
async function saveOrder(c, list, mode, unpin = false) {
  if (ui.orderSaving || ui.removing) return;
  accountRevision++;
  ui.orderSaving = c.cmd; ui.orderErr = null;
  if (mode === "mine") c.order = list.map((a) => a.id);
  c.mode = mode;
  if (unpin) c.pin = null;
  render({ flip: true });
  let res = await API.put(headPath(c, "order"), { order: mode === "mine" ? orderLabels(c, list) : [] });
  if (res.ok && unpin) res = await API.del(headPath(c, "switch"));
  ui.orderSaving = null;
  await refresh(null, res.ok ? null : () => { ui.orderErr = { cmd: c.cmd, ...mutationError(res, "Order not saved") }; refused(`order:${c.cmd}`); });
}
// A refusal just drawn shakes once where it happened, and its reason starts folded under its word.
function refused(key) { ui.shook = key; ui.reasons.delete(key); }
// ---------- reorder: drag by the grip, or arrows on it ----------
// The new order is the command's own (PUT /api/auth/{head}/order); a refused one reads the order splice still holds.
async function moveTo(p, c, aid, index) {
  if (!p || !c || ui.orderSaving || ui.removing) return;
  const list = [...ordered(p, c)], from = list.findIndex((a) => a.id === aid);
  index = Math.max(0, Math.min(index, list.length - 1));
  if (from < 0 || from === index) return;
  const [a] = list.splice(from, 1); list.splice(index, 0, a);
  // Placing another account before the pin also gives the pin way to the chosen order.
  await saveOrder(c, list, "mine", !!c.pin && list[0].id !== c.pin);
  board.querySelector(`[data-grip][data-c="${CSS.escape(c.cmd)}"][data-a="${CSS.escape(aid)}"]`)?.focus();
}
const clearDrop = () => board.querySelectorAll(".drop-before, .drop-after").forEach((el) => el.classList.remove("drop-before", "drop-after"));
function finishDrag(cancel = false) {
  if (!drag) return;
  const moved = drag; drag = null;
  if (board.hasPointerCapture(moved.pointer)) board.releasePointerCapture(moved.pointer);
  clearDrop(); board.querySelector(".dragging")?.classList.remove("dragging");
  if (cancel || !moved.active || moved.index == null) { render(); return; }
  const { p, c } = findCmd(moved.cmd);
  moveTo(p, c, moved.aid, moved.index);
}
board.addEventListener("pointerdown", (e) => {
  const g = e.target.closest("[data-grip]");
  if (!g || g.disabled || !e.isPrimary || e.button !== 0 || ui.orderSaving || ui.removing) return;
  e.preventDefault(); g.focus();
  drag = { pointer: e.pointerId, cmd: g.dataset.c, aid: g.dataset.a, x: e.clientX, y: e.clientY, active: false, index: null };
  board.setPointerCapture(e.pointerId);
});
board.addEventListener("pointermove", (e) => {
  if (!drag || drag.pointer !== e.pointerId) return;
  if (!drag.active && Math.hypot(e.clientX - drag.x, e.clientY - drag.y) < 5) return;
  drag.active = true; e.preventDefault(); clearDrop(); drag.index = null;
  const { p, c } = findCmd(drag.cmd), list = ordered(p, c);
  board.querySelector(`[data-grip][data-a="${CSS.escape(drag.aid)}"]`)?.closest(".card")?.classList.add("dragging");
  const card = document.elementFromPoint(e.clientX, e.clientY)?.closest(".card[data-a]");
  const target = card?.querySelector("[data-grip]");
  if (!target || target.dataset.c !== drag.cmd) return;
  const from = list.findIndex((a) => a.id === drag.aid), to = list.findIndex((a) => a.id === target.dataset.a);
  if (to < 0 || from < 0 || to === from) return;
  const rect = card.getBoundingClientRect(), after = e.clientY >= rect.top + rect.height / 2;
  drag.index = to + (after ? 1 : 0) - (from < to ? 1 : 0);
  card.classList.add(after ? "drop-after" : "drop-before");
});
board.addEventListener("pointerup", (e) => { if (drag?.pointer === e.pointerId) finishDrag(); });
board.addEventListener("pointercancel", () => finishDrag(true));
board.addEventListener("lostpointercapture", () => finishDrag(true));
addEventListener("blur", () => finishDrag(true));
// ---------- keys: arrows on a grip, Enter and Escape in the fields ----------
board.addEventListener("keydown", (e) => {
  if (e.key === "Escape" && drag) { e.preventDefault(); finishDrag(true); return; }
  if (e.target.matches?.(".dayrun[role=group]") && ["ArrowLeft", "ArrowRight", "Home", "End", "Enter"].includes(e.key)) { walkHours(e); return; }
  const g = e.target.closest("[data-grip]");
  if (g && !g.disabled && (e.key === "ArrowUp" || e.key === "ArrowDown")) {
    e.preventDefault();
    const p = findProv(g.dataset.grip), c = findCmd(g.dataset.c).c, i = ordered(p, c).findIndex((a) => a.id === g.dataset.a);
    moveTo(p, c, g.dataset.a, i + (e.key === "ArrowUp" ? -1 : 1));
    return;
  }
  if (e.key !== "Enter") return;
  if (e.target.matches(".code-in")) submitCode(e.target);
  else if (e.target.matches(".rename")) saveRename(e.target, true);
  else if (e.target.matches(".add-name")) e.target.parentElement.querySelector('[data-act="add-go"]')?.click();
  else if (e.target.matches("#key-in")) e.target.parentElement.querySelector('[data-act="save-replace"], [data-act="save-key"]')?.click();
});
// ---------- the hour under the pointer, or under the arrow keys on a focused day ----------
// What an hour holds stands over its column, where the strip's busiest hour says its count, while the pointer is on it or
// the arrow keys have walked to it, the column outlined; Enter on the track opens that hour's requests, as a press on
// its column does. Leaving the track takes it away, and the busiest hour's count stands again.
function sayHour(run, cell = null) {
  for (const r of board.querySelectorAll(".dayrun.at")) if (r !== run || !cell) {
    r.classList.remove("at"); r.querySelector(".hours > .cur")?.classList.remove("cur");
    const tag = r.querySelector(".hourat"); if (tag) tag.textContent = "";
  }
  const tag = run?.querySelector(".hourat");
  if (!cell || !tag) return;
  run.querySelector(".hours > .cur")?.classList.remove("cur");
  cell.classList.add("cur"); run.classList.add("at");
  tag.style.setProperty("--i", String([...cell.parentElement.children].indexOf(cell)));
  tag.textContent = cell.dataset.said;
}
function walkHours(e) {
  const run = e.target, cells = [...run.querySelectorAll(".hours > [data-said]")];
  if (!cells.length) return;
  e.preventDefault();
  const i = cells.findIndex((x) => x.classList.contains("cur"));
  if (e.key === "Enter") { if (cells[i]?.href) cells[i].click(); return; }
  const j = e.key === "Home" ? 0 : e.key === "End" || i < 0 ? cells.length - 1 : Math.max(0, Math.min(cells.length - 1, i + (e.key === "ArrowLeft" ? -1 : 1)));
  sayHour(run, cells[j]);
}
board.addEventListener("pointerover", (e) => {
  const run = e.target.closest?.(".dayrun[role=group]");
  if (!run) { if (board.querySelector(".dayrun.at")) sayHour(null); return; }
  const cell = e.target.closest(".hours > [data-said]");
  if (cell) sayHour(run, cell);
});
board.addEventListener("pointerleave", () => sayHour(null));
// a track the keys reach says its latest hour at once, so the first arrow press is never spent finding where it stands
board.addEventListener("focusin", (e) => {
  if (!e.target.matches?.(".dayrun[role=group]")) return;
  const cells = e.target.querySelectorAll(".hours > [data-said]");
  if (cells.length && !e.target.querySelector(".hours > .cur")) sayHour(e.target, cells[cells.length - 1]);
});
board.addEventListener("focusout", (e) => { if (e.target.matches?.(".dayrun[role=group]")) sayHour(null); });
board.addEventListener("paste", (e) => {
  if (e.target.matches(".code-in")) setTimeout(() => submitCode(e.target), 0);
  if (!e.target.matches("#key-in")) return;
  // A pasted key loses the spaces and line breaks at its ends; one left inside is a key splice won't store
  // (KeyWrites.kt:27). A text field would join it silently, so the paste is read here, and a key it can't take
  // leaves the field empty: behind the dots, one typed character would turn Save on for a key he can't see.
  e.preventDefault();
  const v = e.clipboardData.getData("text").trim(), f = e.target, bad = /[\r\n]/.test(v);
  f.value = bad ? "" : v;
  keyState(f, bad ? "Invalid key" : null);
});
board.addEventListener("input", (e) => {
  if (e.target.matches("#key-in")) { keyState(e.target, null); if (ui.keyErr) { ui.keyErr = null; render(); } }
  // a typed amount is the choice now, so a preset it no longer matches loses its outline at once (Marlin's walk, Oct 10)
  if (e.target.matches("#cap-in")) {
    const v = Number(e.target.value);
    if (v > 0) ui.editor.cap = v;
    e.target.closest(".editor").querySelectorAll('[data-act="preset"]').forEach((b) => b.setAttribute("aria-pressed", String(Number(b.dataset.v) === v)));
    previewCap(e.target); // the cap moves on today's spend as he types it
  }
});
// Save and Cancel take the press without taking focus, so the field's blur can't save and redraw before their click
// lands. Tab between the field and its acts keeps the rename open; leaving all three saves, as leaving the field did.
const inRename = (el) => !!el?.matches?.(".rename, [data-act=finish-rename], [data-act=cancel-rename]");
board.addEventListener("mousedown", (e) => { if (e.target.closest("[data-act=finish-rename], [data-act=cancel-rename]")) e.preventDefault(); });
board.addEventListener("focusout", (e) => {
  const f = board.querySelector(".field.rename");
  if (!drawing && f && inRename(e.target) && !inRename(e.relatedTarget)) saveRename(f, false);
});
const closeAll = () => Object.assign(ui, { menu: null, armed: null, removeErr: null, rename: null, renameErr: null, renameDraft: null, replace: null, keyErr: null, editor: null, naming: null });
document.addEventListener("keydown", (e) => {
  if (e.key !== "Escape" || !(ui.menu || ui.rename || ui.replace || ui.editor || ui.naming)) return;
  closeAll(); render();
});
// Enter on a name splice won't take keeps the field open with the reason; leaving the field keeps the old name.
async function saveRename(field, enter) {
  if (ui.rename !== field.dataset.s) return;
  const a = findAcct(field.dataset.a), v = field.value.trim();
  const err = v && v !== a.name ? nameError(provOf(a), a, v) : null;
  if (err && enter) { ui.renameErr = err; ui.renameDraft = v; refused("rename"); render(); return; }
  if (v && !err && v !== a.name) {
    // a name splice would take but the save fails: the field stays with the reason, even when he leaves it
    // a login with a ref keeps its name in splice's names, so its sign-in files are never touched (labels only, Oct 12)
    const res = a.row?.ref ? await API.put(namePath(a.row.ref), { name: v }) : await API.patch(editPath(a), { label: v });
    if (!res.ok) { ui.renameErr = mutationError(res, "Not renamed"); ui.renameDraft = v; refused("rename"); render(); return; }
    Object.assign(ui, { rename: null, renameErr: null, renameDraft: null });
    await refresh(); return;
  }
  Object.assign(ui, { rename: null, renameErr: null, renameDraft: null }); render();
}
// Where an account is renamed or removed: under its command, naming exactly this login (AccountEditRoutes.kt:78, :95).
function editPath(a) {
  const t = a.row.edit_target, label = t ? t.id : a.row.label || "primary";
  const q = t ? `?target_kind=${encodeURIComponent(t.kind)}&target_id=${encodeURIComponent(t.id)}` : "";
  return `/api/auth/${encodeURIComponent(a.row.heads[0])}/accounts/${encodeURIComponent(label)}${q}`;
}

// ---------- every other act, each one splice's own route ----------
const headPath = (c, tail) => `/api/auth/${encodeURIComponent(c.head)}/${tail}`;
document.addEventListener("click", async (e) => {
  const t = e.target.closest("[data-act]");
  if (ui.menu && !e.target.closest(".menu, [data-act=menu]")) { ui.menu = null; ui.armed = null; ui.removeErr = null; if (!t) { render(); return; } }
  if (!t) return;
  const act = t.dataset.act, p = t.dataset.p && findProv(t.dataset.p), a = t.dataset.a && findAcct(t.dataset.a);
  const k = t.dataset.c && findCmd(t.dataset.c).c;
  // a refused switch or order is said until the next press; opening its reason is no new press
  if (act !== "reason") { ui.slotErr = null; ui.orderErr = null; ui.keyErr = null; }
  switch (act) {
    // a refusal's word opens splice's own reason under it, and folds it back
    case "reason":
      if (!ui.reasons.delete(t.dataset.k)) { ui.reasons.add(t.dataset.k); ui.enter = `reason:${t.dataset.k}`; }
      render(); break;
    // why a move happened and which sessions it carried open under its line, and fold back
    case "move-why":
      if (!ui.moves.delete(t.dataset.k)) { ui.moves.add(t.dataset.k); ui.enter = `move:${t.dataset.k}`; }
      render(); break;
    // a move names the other login as a door to its card on this board, which comes into view and pulses once
    case "goto": {
      const el = board.querySelector(`.card[data-a="${CSS.escape(t.dataset.a)}"]`);
      if (el) { el.scrollIntoView({ block: "nearest", behavior: window.matchMedia("(prefers-reduced-motion: reduce)").matches ? "instant" : "smooth" }); el.classList.remove("pulse-once"); void el.offsetWidth; el.classList.add("pulse-once"); }
      break;
    }
    case "theme": toggleTheme(); break;
    case "probe": probe(); break;
    // a server that does not answer is asked again on its own card: the act spins while splice reads the servers again, and
    // says so once beside itself when the server still does not answer
    case "recheck": {
      if (ui.checking || !a) break;
      ui.checking = a.id; ui.stillDown = null; ui.shook = null; render();
      await load();
      ui.checking = null;
      if (findAcct(a.id)?.down) { ui.stillDown = a.id; ui.shook = `recheck:${a.id}`; }
      render({ flip: true });
      break;
    }
    // Soonest reset is no order of his own (AccountPool.kt:101, :259); My order keeps the order shown, as his.
    case "mode": {
      const order = t.dataset.v === "mine" ? ruled(p, k) : [];
      await saveOrder(k, order, t.dataset.v); break;
    }
    // Use now pins the account on that command: it serves from the next request, the rule stays, and the pin undoes it
    // (SwitchRoute.kt). A switch splice refused says so beside the act, and the order it holds comes back.
    case "keep-use": ui.moving = null; render(); break;
    case "use": {
      const armed = ui.moving?.a === a.id && ui.moving?.c === k.cmd;
      ui.moving = null;
      const cut = armed ? [] : await midRequest([k.head]);
      if (cut.length) { ui.moving = { a: a.id, c: k.cmd, cut }; ui.enter = "arm"; render(); break; }
      k.pin = a.id; render({ flip: true });
      const res = await API.post(headPath(k, "switch"), { label: labelOn(a, k.head) });
      await refresh(null, res.ok ? null : () => { ui.slotErr = { a: a.id, c: k.cmd, ...mutationError(res, "Not switched") }; refused(slotKey(a, k)); });
      break;
    }
    case "unpin": {
      const on = findCmdPin(k);
      k.pin = null; render({ flip: true });
      const res = await API.del(headPath(k, "switch"));
      await refresh(null, res.ok || !on ? null : () => { ui.slotErr = { a: on, c: k.cmd, ...mutationError(res, "Not unpinned") }; refused(slotKey({ id: on }, k)); });
      break;
    }
    case "lane": ui.lane[p.id] = k.cmd; closeAll(); render({ flip: true }); break; // the rail shows that command's order
    case "add": closeAll(); ui.naming = { cmd: k.cmd, draft: "" }; ui.enter = "naming"; render(); board.querySelector(".add-name")?.focus(); break;
    case "add-go": { const f = board.querySelector(".add-name"); if (f) startAdd(p, k.cmd, f); break; }
    case "cancel-add": ui.naming = null; render(); break;
    case "signin": ui.enter = "sign"; startSignin(`out:${a.id}`, howFor(p, a, null)); break;
    case "open-signin": openSignin(t.dataset.t); break;
    case "copy-code":
      navigator.clipboard?.writeText(ui.signin[t.dataset.t].code).catch(() => {});
      t.classList.add("copied"); setTimeout(() => t.classList.remove("copied"), 1200); break;
    case "retry-signin": { const s = ui.signin[t.dataset.t]; if (s.how.again) s.how.again(); else waitSignin(t.dataset.t); break; }
    case "cancel-signin": cancelSignin(t.dataset.t); break;
    case "menu": {
      const opening = ui.menu !== t.dataset.s;
      ui.menu = opening ? t.dataset.s : null; ui.armed = null; ui.removeErr = null; ui.enter = opening ? "menu" : null;
      render(); board.querySelector(".menu button")?.focus(); break;
    }
    case "rename": ui.menu = null; ui.rename = t.dataset.s; ui.renameDraft = t.classList.contains("name-it") ? "" : null; ui.enter = "rename"; render(); break;
    case "finish-rename": { const f = board.querySelector(".field.rename"); if (f) saveRename(f, true); break; } // Enter's path
    case "cancel-rename": Object.assign(ui, { rename: null, renameErr: null, renameDraft: null }); render(); break;
    case "resign": ui.menu = null; ui.enter = "sign"; startSignin(`re:${a.id}`, howFor(p, a, null), t.dataset.s); break;
    case "replace": ui.menu = null; ui.replace = t.dataset.s; ui.enter = "key"; render(); break;
    case "cancel-replace": ui.replace = null; render(); break;
    // Remove takes two presses on the same item; removal deletes the sign-in, so nothing comes back from it.
    case "remove": {
      if (ui.removing || ui.orderSaving) break;
      ui.removeErr = null;
      if (ui.armed !== t.dataset.s) { ui.armed = t.dataset.s; render(); board.querySelector(".menu .armed")?.focus(); break; }
      accountRevision++; ui.removing = t.dataset.s; render();
      const res = p.kind === "key" ? await API.del(`/api/keys/${encodeURIComponent(a.env)}`) : await API.del(editPath(a));
      ui.removing = null;
      if (!res.ok) {
        ui.removeErr = { spot: t.dataset.s, ...mutationError(res, p.kind === "key" ? "Key not removed" : "Not removed") };
        ui.armed = null; refused(`remove:${t.dataset.s}`); render(); break;
      }
      ui.menu = null; ui.armed = null;
      board.querySelectorAll(`.card[data-a="${CSS.escape(a.id)}"]`).forEach((el) => el.classList.add("leaving"));
      await refresh();
      break;
    }
    case "save-replace": {
      if (!pasted(t)) break;
      const res = await API.put(`/api/keys/${encodeURIComponent(a.env)}`, { value: pasted(t) });
      if (!res.ok) {
        // splice's reason, where it gave one, opens under the word; without one the word stands beside the field in place
        const err = mutationError(res, "Not saved");
        if (!err.why) { keyState(keyIn(t), "Not saved"); break; }
        ui.keyErr = { spot: `${p.id}/${a.id}`, ...err }; refused(`key:${ui.keyErr.spot}`); render(); break;
      }
      ui.replace = null; await refresh(); break;
    }
    // a new budget starts from Settings' "A new budget, when it's spent" (budgetDefaultAction), so that row governs it;
    // the person can still pick Warn or Block here, and a config that does not read starts at Warn, the knob's default
    case "edit-budget": {
      const block = k.budget ? k.budget.block : (await API.get("/api/config")).body?.knobs?.budgetDefaultAction?.value === "block";
      ui.editor = { c: k.cmd, cap: k.budget ? k.budget.cap : 10, block }; ui.enter = "editor"; render(); document.getElementById("cap-in")?.focus(); break;
    }
    // a preset sets the amount and moves the cap's line along the track where it stands; a refusal on show is cleared by a redraw
    case "preset": {
      const refused = !!ui.editor.err, field = document.getElementById("cap-in");
      ui.editor.cap = Number(t.dataset.v); ui.editor.err = null;
      if (refused || !field) { render(); break; }
      field.value = String(ui.editor.cap);
      t.parentElement.querySelectorAll('[data-act="preset"]').forEach((b) => b.setAttribute("aria-pressed", String(b === t)));
      previewCap(field);
      break;
    }
    case "bmode": ui.editor.cap = Number(document.getElementById("cap-in").value) || ui.editor.cap; ui.editor.block = t.dataset.v === "block"; ui.editor.err = null; render(); break;
    // splice keeps the whole set of budgets (BudgetRoutes.kt:71): every other command's goes back unchanged. A set splice
    // refused keeps the editor open as he left it, says so beside its acts, and shakes once.
    case "save-budget": {
      const v = Number(document.getElementById("cap-in").value), cap = v > 0 ? v : ui.editor.cap, block = ui.editor.block;
      const set = data.flatMap((x) => x.cmds).filter((c) => c.budget && c.cmd !== k.cmd).map((c) => ({ head: c.head, daily_usd: c.budget.cap, action: c.budget.block ? "block" : "warn" }));
      set.push({ head: k.head, daily_usd: cap, action: block ? "block" : "warn" });
      ui.editor.cap = cap; ui.editor.err = null;
      const res = await API.put("/api/budgets", { budgets: set });
      if (res.ok) { ui.editor = null; await refresh(k.cmd); } else { if (ui.editor) { ui.editor.err = mutationError(res, "Not saved"); refused("editor"); } render(); }
      break;
    }
    // a budget goes by sending the set without it, as saving one sends the set with it (BudgetRoutes.kt:71)
    case "remove-budget": {
      const set = data.flatMap((x) => x.cmds).filter((c) => c.budget && c.cmd !== k.cmd).map((c) => ({ head: c.head, daily_usd: c.budget.cap, action: c.budget.block ? "block" : "warn" }));
      ui.editor.err = null;
      const res = await API.put("/api/budgets", { budgets: set });
      if (res.ok) { ui.editor = null; await refresh(k.cmd); } else { if (ui.editor) { ui.editor.err = mutationError(res, "Not removed"); refused("editor"); } render(); }
      break;
    }
    case "cancel-budget": ui.editor = null; render(); break;
    // a Day's Not read opens the reason splice kept for it, under the word, and folds it back
    case "why": if (ui.why.has(k.cmd)) ui.why.delete(k.cmd); else { ui.why.add(k.cmd); ui.enter = "why"; } render(); break;
    // the rest of a login's sessions open in place under its first ones, and Fewer folds them back
    case "doors": if (ui.doors.has(t.dataset.a)) ui.doors.delete(t.dataset.a); else ui.doors.add(t.dataset.a); render({ flip: true }); break;
    case "add-provider": ui.addProv = { stage: "pick" }; ui.enter = "pick"; render(); break;
    case "cancel-prov": dropAdd(); render(); break;
    case "pick": pickProvider(CATALOG.find((x) => x.id === t.dataset.id)); break;
    case "save-key": if (pasted(t)) saveKey(t); break;
  }
});
// the login a command is pinned to, by its card's id, for a refused Unpin said on that card
const findCmdPin = (c) => c.pin ?? data.flatMap((p) => p.accounts).find((a) => (a.row?.pinned_heads || []).includes(c.head))?.id ?? null;

// a door from another page lands on one account: accounts.html?card=<provider>/<account>, or ?head=<command's head>,
// which lands on what that command needs fixed (a signed-out login, a missing key) and else its first account: the
// way back from a session Sessions or Teams shows as Signed out (Marlin, Oct 10)
const ADDR = new URLSearchParams(location.search);
// A POOL'S DOOR LANDS ON THE LOGIN THAT COMMAND SENDS AS NEXT (Oct 26): what needs fixing comes first, as it did, but
// where nothing does, this landed on the provider's FIRST card, so Compare's Name it — which shows only where the
// login a command sends as next has no name — opened a named card beside the one to name. The next login is the
// pool's own pick (c.serving, from GET /api/auth/{head}/order's next_target).
const doorOf = (head) => {
  const p = data.find((x) => x.cmds.some((c) => c.head === head));
  if (!p) return null;
  const c = p.cmds.find((x) => x.head === head);
  const mine = (x) => (x.row?.heads || []).includes(head) || (x.heads || []).includes(head) || x.id === head;
  const a = p.accounts.find((x) => x.out || x.has === false)
    || (c && serving(p, c))
    || p.accounts.find(mine)
    || p.accounts[0];
  return a ? `${p.id}/${a.id}` : null;
};
probe().then(() => {
  // a door from Compare models: ?budget=<command's head> opens that command's budget, the editor "Set budget" opens
  const over = data.flatMap((p) => p.cmds).find((c) => c.head === ADDR.get("budget"));
  if (over) {
    ui.editor = { c: over.cmd, cap: over.budget ? over.budget.cap : 10, block: over.budget ? over.budget.block : false };
    ui.enter = "editor"; render();
    document.getElementById("cap-in")?.focus();
    return;
  }
  const card = ADDR.get("card") || (ADDR.get("head") && doorOf(ADDR.get("head")));
  if (!card) return;
  const el = board.querySelector(`[data-key^="card:${CSS.escape(card)}"]`);
  // the card pulses once on the draw that lands on it, so a later draw (the sessions answering) never cuts the pulse short
  if (el?.dataset.a) {
    ui.pulse = el.dataset.a; render();
    board.querySelector(`.card[data-a="${CSS.escape(el.dataset.a)}"]`)?.scrollIntoView({ block: "center", behavior: "instant" });
  }
});

});
