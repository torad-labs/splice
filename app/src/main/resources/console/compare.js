// Compare models: one prompt to up to four of his models, their figures compared on one table and their answers read
// side by side. Each model is one POST /api/playground {head, prompt, model}: the command's own provider builds the
// one-message turn a real turn would send, as the login a real turn would use next, and it is recorded like a request (a
// Requests row marked Compare, its price, the command's budget); an over-budget command refuses it, as it refuses a turn.
// The answer comes back whole, as the provider's own stream in {raw: text}, and this page reads its frames. A pane's
// Request opens the send inside that pane, in Requests' own open request (reqview.js). The board lists only the commands
// that can send now (a login added to splice, a key, a local runtime), never the client's own sign-in, which splice has
// no credential for.
// Reads: GET /api/heads, /api/models, /api/auth, /api/accounts, /api/status, and each pooled command's order.
//
// THE COMPARISON IS ONE TABLE (Oct 27; Marcos: "show don't tell"). Every chosen model is a row of the models plate beside
// the prompt, docked on a numbered rail: its time on one log axis that grows while the send is out, then its cost, its
// tokens each way, its context window and its prices as bars on scales the rows share, the best of each column starred.
// The rail numbers the order the answers landed in, the rows move to that order as they land, and each answer's pane
// carries the same number. The round's own figure leads the table: the time until the last answer, a running clock while
// any is out, with how many answered of how many were asked. The board of models opens across the whole width under the
// first row, its providers packed into columns that end near one height and each command's models under the context
// window they share; the answers stand side by side under it, each opening its own request in place.
CONSOLE.page("compare.html", (scope) => {
  "use strict";
  const { document, window, API, setInterval, clearInterval, setTimeout, requestAnimationFrame, ResizeObserver } = scope;

const MAX_PANES = 4;
const KEPT = "splice.compare.prompt";
const KEPT_MODELS = "splice.compare.models";
// a command's list shows up to WHOLE models; a longer one shows its first FIRST and the rest behind +N
const LIST = { whole: 10, first: 8 };
// a list grouped by vendor (OpenRouter) shows its largest vendors, each opening its models in place, kept the same way
const VENDORS = 6, VENDOR_LIST = { whole: 8, first: 6 };
// a board column and an answer's narrowest lane, in rem: six columns and four lanes at 3822, two of each at 1440
const COL_REM = 38, LANE_REM = 40;
const TICK = '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="3.2" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><path d="M5 12.5l4.5 4.5L19 7"/></svg>';
// the best figure of a column: a star in the text's own ink, never the key light (Send's alone)
const STAR = '<svg class="pg-star" viewBox="0 0 24 24" aria-hidden="true"><path d="M12 2.8l2.75 5.9 6.45.75-4.8 4.4 1.3 6.35L12 17l-5.7 3.2 1.3-6.35-4.8-4.4 6.45-.75z"/></svg>';
const FIND = G('<circle cx="11" cy="11" r="6.5"/><path d="M20 20l-4.3-4.3"/>', 'aria-hidden="true"');
const root = document.getElementById("pg");
// one name per provider, written once (kit PROVIDER_NAME, vast.ai among them); a family the kit has no word for reads
// as the command that sends to it
const titleOf = (family, command) => PROVIDER_NAME[family] ?? command;
const enc = encodeURIComponent;
const still = () => window.matchMedia("(prefers-reduced-motion: reduce)").matches;

// cmds: the commands that can send, each { key, command, family, title, acct, models: [{id, label, short, vendor, window,
// rates}], held, down }; blocked: the ones that can't, each with why and the page that fixes it
const ui = {
  editing: false, prompt: "", panes: [], cmds: null, blocked: [], error: null, reading: false, restored: false, add: null,
  q: "", open: new Set(), unfold: null, finished: 0, back: 0, uid: 0, opened: false, swap: false, done: false, hi: 0, fit: null,
  marks: "",
};
try { ui.prompt = sessionStorage.getItem(KEPT) ?? ""; } catch { /* no storage: the field starts empty */ }
const keep = () => { try { sessionStorage.setItem(KEPT, ui.prompt); } catch { /* nothing to keep it in */ } };
// the models he chose come back with the prompt, so a reload finds the comparison he was building
const keptModels = () => {
  try { const v = JSON.parse(sessionStorage.getItem(KEPT_MODELS) ?? "[]"); return Array.isArray(v) ? v.filter((x) => x?.key && x?.id).slice(0, MAX_PANES) : []; } catch { return []; }
};
const keepModels = () => { try { sessionStorage.setItem(KEPT_MODELS, JSON.stringify(ui.panes.map((p) => ({ key: p.key, id: p.id })))); } catch { /* nothing to keep it in */ } };

// ---------- reading what can send ----------
// The playground sends as the login a real turn would use next: the pool's next target and nothing else
// (PlaygroundProviders.login). /api/auth lists a pool's accounts but never says which is next; GET /api/auth/{head}/order
// publishes it as next_target. It can send only when that is an account added to splice; the client's own sign-in (auth kind
// "client") is forwarded by the client itself, so there is nothing of it to send.
const nextIn = (a, next) => a.account_pool.accounts.find((x) => x.label === next && x.credential_present && x.available !== false && x.auth?.kind !== "client");
const canSend = (a, next) => Boolean(a) && (a.account_pool ? Boolean(nextIn(a, next)) : a.present === true && !a.refusal && a.verdict?.state !== "rejected");
const cap = (s) => (s ? s[0].toUpperCase() + s.slice(1) : "");
// why a command can't send now, in the console's words, and the page where that is fixed
function whyNot(a, next, listed) {
  if (!listed) return { word: "No models", door: "models" };
  if (!a) return { word: "Not set up", door: "accounts" };
  if (a.account_pool) {
    const t = a.account_pool.accounts.find((x) => x.label === next);
    return { word: t?.auth?.kind === "client" ? "Client's own sign-in" : "No login ready", door: "accounts" };
  }
  if (a.refusal || a.verdict?.state === "rejected") return { word: OUTCOME_WORD["failure:authentication_error"], door: "accounts", bad: true };
  return { word: OUTCOME_WORD["error:auth-missing"], door: "accounts" };
}
// The account a command sends as, in the words Accounts gives it: its name and plan; "Account" with a door to name it
// where the login has no name of its own yet; a key by its variable's name.
// A key shows its masked tail beside its variable, as Accounts draws it ("…9c21" from api_key_masked), so commands whose
// keys share one variable can be told apart; a key too short to mask is masked "set" and shows no tail.
const tailOf = (masked) => (typeof masked === "string" && masked.includes("…") ? `…${masked.split("…").pop()}` : null);
function acctOf(a, known, next) {
  if (a?.env_var) return { kind: "key", var: a.env_var, tail: tailOf(a.api_key_masked) };
  if (known) return { kind: known.label_default && known.can_rename ? "unnamed" : "named", name: known.display_name ?? known.label ?? "", plan: cap(known.plan) };
  return next ? { kind: "named", name: next, plan: "" } : { kind: "none" };
}
// OpenRouter spells its vendor into every label ("OpenAI: GPT-6 Sol"); the vendor is said once over its models instead
function vendorsOf(models) {
  const slugs = {};
  for (const m of models) { const v = /^([^:]{1,30}):\s+\S/.exec(m.label ?? ""), slug = String(m.id).split("/")[0]; if (v && m.id.includes("/")) slugs[slug] ??= v[1]; }
  return (m) => {
    const v = /^([^:]{1,30}):\s+(.+)$/.exec(m.label);
    if (v) return [v[1], v[2]];
    const slug = m.id.includes("/") ? m.id.split("/")[0] : null;
    return [slug ? slugs[slug] ?? cap(slug) : null, m.label];
  };
}
async function read() {
  ui.reading = true; tick();
  const [heads, models, auth, accts, status] = await Promise.all(["/api/heads", "/api/models", "/api/auth", "/api/accounts", "/api/status"].map((p) => API.get(p)));
  if (!heads.ok || !models.ok || !auth.ok) {
    console.warn(`compare: ${!heads.ok ? `/api/heads answered ${heads.status}` : !models.ok ? `/api/models answered ${models.status}` : `/api/auth answered ${auth.status}`}`);
    ui.error = auth.ok ? "Models not read" : "Sign-ins not read"; ui.reading = false;
    return;
  }
  const live = Object.fromEntries((heads.body?.heads || []).map((h) => [h.key, h]));
  const fam = Object.fromEntries((status.ok ? status.body?.registry || [] : []).map((r) => [r.key, r.family]));
  const rows = accts.ok ? accts.body?.accounts || [] : [];
  const pooled = Object.keys(auth.body || {}).filter((h) => auth.body[h]?.account_pool);
  const orders = await Promise.all(pooled.map((h) => API.get(`/api/auth/${enc(h)}/order`)));
  const next = Object.fromEntries(pooled.map((h, i) => [h, orders[i].body?.next_target ?? null]));
  const blocked = [];
  ui.cmds = (models.body?.heads || []).flatMap((row) => {
    const a = auth.body?.[row.head], list = (row.models || []).filter((m) => m.resolved !== false && m.id);
    const h = live[row.head] ?? {}, command = h.label || row.head;
    const family = PROVIDER_FAMILY[fam[row.head]] ?? PROVIDER_FAMILY[row.provider] ?? fam[row.head] ?? row.provider;
    if (!canSend(a, next[row.head]) || !list.length) {
      blocked.push({ key: row.head, command, family, ...whyNot(a, next[row.head], list.length) });
      return [];
    }
    const name = a.account_pool ? nextIn(a, next[row.head])?.label : null;
    const known = rows.find((r) => (r.heads || []).includes(row.head) && (!name || r.label === name || `native:${r.label}` === name || r.display_name === name));
    const labelled = list.map((m) => ({ id: m.id, label: modelName({ [m.id]: m.label }, m.id) }));
    const vendor = vendorsOf(labelled);
    const reset = Number(h.quotaFull?.resetsAtEpochSeconds);
    return [{
      key: row.head, command, family, title: titleOf(family, command), acct: { ...acctOf(a, known, name), head: row.head },
      models: list.map((m, i) => {
        const [v, short] = vendor(labelled[i]);
        return { id: m.id, label: labelled[i].label, short, vendor: v, window: m.context_window ?? null, rates: m.rates ?? null };
      }),
      held: h.quotaFull ? { until: reset ? new Date(reset * 1000) : null } : null,
      down: Boolean(h.runtimeNotAnswering),
    }];
  });
  ui.blocked = blocked; ui.error = null; ui.reading = false;
}
const cmdOf = (key) => ui.cmds?.find((c) => c.key === key);
const modelOf = (p) => cmdOf(p.key)?.models.find((m) => m.id === p.id) ?? null;
const offered = () => (ui.cmds || []).reduce((a, c) => a + c.models.length, 0);
// after a read: the models he had chosen come back once; later reads drop a pane whose model is no longer offered
function restore() {
  if (!ui.cmds || ui.error) return;
  if (!ui.restored) {
    ui.restored = true;
    for (const k of keptModels()) {
      const c = cmdOf(k.key), m = c?.models.find((x) => x.id === k.id);
      if (c && m && !paneOf(k.key, k.id) && ui.panes.length < MAX_PANES) ui.panes.push(pane(c, m));
    }
    return;
  }
  ui.panes = ui.panes.filter((p) => p.st === "sending" || modelOf(p));
}

// ---------- the panes ----------
// uid: the pane's own name for the page, so its row and its pane find each other in any order
const pane = (c, m) => ({
  uid: ++ui.uid, key: c.key, id: m.id, label: m.short, vendor: m.vendor, st: "idle", asked: null, fail: null, ms: null, at: null, text: "",
  tin: null, tout: null, cost: null, trace: null, tries: 0, t0: 0, order: null, seq: null, req: false, reqNew: false, said: false,
  rv: { tab: "attempts", nodes: new Set(), q: "" }, RV: null, rvSwap: false, painted: {}, fresh: false, arm: false, pop: false,
});
const paneOf = (key, id) => ui.panes.find((p) => p.key === key && p.id === id);
const byUid = (u) => ui.panes.find((p) => p.uid === Number(u)) ?? null;
const busy = () => ui.panes.some((p) => p.st === "sending");
const sendable = () => Boolean(ui.prompt.trim()) && ui.panes.length > 0 && !busy();
const stale = (p) => p.asked != null && p.st !== "sending" && p.asked !== ui.prompt;
const askedOnes = () => ui.panes.filter((p) => p.st !== "idle");
// the board of models stands open under the first row while a lane is free, until he closes it or sends
const pickerOpen = () => (ui.cmds?.length ?? 0) > 0 && !ui.error && (ui.add ?? ui.panes.length < MAX_PANES);

// ---------- reading the answer: the provider's stream, frame by frame ----------
function framesOf(raw) {
  return String(raw ?? "").split(/\r?\n\r?\n/).map((chunk) => {
    let event = null; const data = [];
    for (const line of chunk.split(/\r?\n/)) {
      if (line.startsWith("event:")) event = line.slice(6).trim();
      else if (line.startsWith("data:")) data.push(line.slice(5).replace(/^ /, ""));
    }
    if (!data.length) return null;
    const text = data.join("\n");
    let parsed = text;
    try { parsed = JSON.parse(text); } catch { /* [DONE] and the like stay text */ }
    return { event: event ?? (typeof parsed === "object" && parsed?.type) ?? "message", data: parsed };
  }).filter(Boolean);
}
// the answer's text and the tokens the stream reported, in whichever dialect the frames came
function readFrames(frames) {
  let text = "", tin = null, tout = null, error = null;
  for (const { data: d } of frames) {
    if (!d || typeof d !== "object") continue;
    if (d.type === "content_block_delta" && d.delta?.text != null) text += d.delta.text;
    else if (d.type === "response.output_text.delta") text += d.delta ?? "";
    else if (d.object === "chat.completion.chunk" && d.choices?.[0]?.delta?.content != null) text += d.choices[0].delta.content;
    if (d.type === "message_start") tin = d.message?.usage?.input_tokens ?? tin;
    if (d.type === "message_delta") tout = d.usage?.output_tokens ?? tout;
    if (d.type === "response.completed") { tin = d.response?.usage?.input_tokens ?? tin; tout = d.response?.usage?.output_tokens ?? tout; }
    if (d.usage && d.object === "chat.completion.chunk") { tin = d.usage.prompt_tokens ?? tin; tout = d.usage.completion_tokens ?? tout; }
    if (d.type === "error" || d.type === "response.failed") error = d;
  }
  return { text, tin, tout, error };
}
const DOWN = "Not answering"; // a command whose runtime is not up, as Accounts and the board's card say it
const LIMIT = "Limit reached"; // a spent plan window, the lock's own word, on the pane and in its request alike
// What went wrong, in Fin's word for its kind: the provider's status or error type, else the sentence splice answered.
function failWord(status, body, said) {
  const type = body?.error?.type ?? body?.error?.code ?? body?.type ?? "";
  if (status === 429 || /rate_limit|usage_limit/.test(type)) return OUTCOME_WORD["error:rate-limited"];
  if (status === 529 || /overloaded/.test(type)) return OUTCOME_WORD["failure:overloaded_error"];
  if (status === 401 || status === 403) return OUTCOME_WORD["failure:authentication_error"];
  if (status >= 500 && status < 600) return OUTCOME_WORD["error:upstream-failed"];
  if (status === 404 || /not_found/.test(type)) return OUTCOME_WORD["failure:not_found_error"];
  if (status >= 400 && status < 500) return OUTCOME_WORD["failure:invalid_request_error"];
  // Compare's own word: its send runs under a connect and a whole-request timeout, and a model still answering is cut
  // with no silence at all, so the kit's "Given up after a silence" is not its fact (fin, Oct 12)
  if (/timed? ?out|HttpRequestTimeout/i.test(said)) return "Timed out";
  if (/no credential|forwards the caller/.test(said)) return OUTCOME_WORD["error:auth-missing"];
  // a command whose runtime is not up: Accounts' word for the same fact, so one thing keeps one name
  if (/no running provider/.test(said)) return DOWN;
  // a command splice no longer has (its 400 "unknown head"): the kit's word for a name the far end does not know, and
  // the pane's Read again takes the list again, which drops it
  if (/unknown head/.test(said)) return OUTCOME_WORD["failure:not_found_error"];
  if (/failed:/.test(said)) return OUTCOME_WORD["error:conn-reset"];
  return OUTCOME_WORD["error:unexpected"];
}
const SIGNED = new Set([OUTCOME_WORD["error:auth-missing"], OUTCOME_WORD["failure:authentication_error"]]);
// splice's own refusals whose word and act already say all its sentence would ("head 'x' has no running provider"), so
// the sentence is not kept on the pane; every other failure keeps what was said, folded to one line
const TOLD = new Set([DOWN, "Timed out", OUTCOME_WORD["failure:not_found_error"], OUTCOME_WORD["error:auth-missing"]]);
// what the provider or splice said, cut at 400 characters, and marked where it was cut
const cut = (s) => (s.length > 400 ? `${s.slice(0, 399).trimEnd()}…` : s);

// A provider that says a plan's window is spent gives its reset (ChatGPT's usage_limit_reached carries resets_at and the window's
// minutes): the pane shows the lock, when it frees and how far along the window is, and no Try again, because pressing
// it cannot fix a spent window.
const WINDOW = { 18000: "5 hours", 86400: "Day", 604800: "Week", 2592000: "Month" };
function spentWindow(body) {
  const e = body?.error;
  const at = Number(e?.resets_at) || (Number(e?.resets_in_seconds) ? Date.now() / 1000 + Number(e.resets_in_seconds) : 0);
  if (!(/usage_limit/.test(e?.type ?? "") && at)) return null;
  const secs = Number(e.limit_window_minutes) * 60 || null;
  return { until: new Date(at * 1000), word: WINDOW[secs] ?? "", secs };
}
// a reply with no price, in Requests' words for why (requests.js REASON), said once beside the table's legend
const NO_COST = { plan: "On your plan", uncounted: "Uncounted" };
// a cost in the console's one money form (kit dollars), so a reply reads the same here and on its Requests row; the
// bars on the cost column's scale tell two close costs apart
const money = dollars;
// a rate card's price per million tokens: "$15", "$2.50", "$0.15"
const rate = (v) => (v >= 1 ? `$${Number.isInteger(v) ? v : v.toFixed(2)}` : `$${v.toFixed(v < 0.01 ? 3 : 2)}`);
// the daemon prices the reply the way it prices a recorded request, from the tokens the answer reported
async function priceOf(p, reply) {
  const q = new URLSearchParams({ head: p.key, model: p.id, in_tokens: p.tin, out_tokens: p.tout });
  if (reply?.request?.account) q.set("account", reply.request.account);
  const res = await API.get(`/api/perf/price?${q}`);
  if (!res.ok) { console.warn(`compare: /api/perf/price answered ${res.status}`); return null; }
  return { usd: res.body.cost_usd ?? null, reason: res.body.cost_reason ?? null, planValue: res.body.plan_value_usd ?? null };
}

// ---------- sending: one POST per pane, all at once, each answering on its own clock ----------
async function ask(p) {
  const t0 = Date.now(), began = performance.now();
  Object.assign(p, { st: "sending", t0, at: new Date(t0), asked: ui.prompt, fail: null, cost: null, text: "", tin: null, tout: null, trace: null, ms: null, order: null, seq: null, req: false, said: false, arm: false, tries: p.fail ? p.tries + 1 : 1 });
  p.rv = { tab: "attempts", nodes: new Set(), q: "" }; p.RV = null;
  const sent = { head: p.key, prompt: ui.prompt, model: p.id };
  const res = await API.post("/api/playground", sent);
  // a model taken off the comparison while its answer was out: the answer is dropped where it lands, and it takes no
  // place in the order
  if (!ui.panes.includes(p)) return;
  p.ms = Math.round(performance.now() - began);
  p.seq = ++ui.back;
  const reply = res.body;
  if (res.status === 429 && reply?.blocked === "budget") { // the command's budget refused it as it refuses a turn, leaving an Over budget row
    // the daemon's sentence names its files, so the pane says Over budget with the door to that command's budget instead
    console.warn(`compare: ${reply.error ?? "the budget refused the send"}`);
    Object.assign(p, { st: "failed", fail: { word: OUTCOME_WORD[reply.outcome] ?? OUTCOME_WORD["error:budget-blocked"], door: reply.head || p.key, splice: true } });
    return;
  }
  if (!res.ok || !reply?.response) { // splice's own refusal, or a call that never reached the provider: splice said it, not the provider
    if (res.status === 0) { Object.assign(p, { st: "failed", fail: { word: "splice not answering", retry: true } }); return; }
    const said = typeof reply?.error === "string" ? reply.error : "";
    if (!said) console.warn(`compare: /api/playground answered ${res.status}`);
    const word = failWord(0, null, said);
    Object.assign(p, { st: "failed", fail: { word, retry: res.status !== 400, reread: res.status === 400, splice: true, code: said ? null : res.status, said: TOLD.has(word) ? "" : cut(said) } });
    return;
  }
  const status = reply.response.status, body = reply.response.body;
  const frames = typeof body?.raw === "string" ? framesOf(body.raw) : null;
  const got = frames ? readFrames(frames) : { text: "", tin: null, tout: null, error: null };
  const attempt = { url: reply.request.url, start: 0, ms: p.ms, headers: reply.request.headers, body: reply.request.body, status, ...(frames ? { frames } : { raw: body }) };
  p.trace = { sent: { headers: { "content-type": "application/json" }, body: sent }, attempts: [attempt], returned: { status, ...(frames ? { frames } : { body }) } };
  const errBody = got.error?.error ?? got.error?.response?.error ? { error: got.error.error ?? got.error.response.error } : body;
  if (status >= 400 || got.error) {
    const type = errBody?.error?.type ?? errBody?.error?.code ?? "";
    const told = errBody?.error?.message ?? errBody?.message, spent = spentWindow(errBody);
    const word = spent ? LIMIT : /overloaded/.test(type) ? OUTCOME_WORD["failure:overloaded_error"] : failWord(got.error ? 0 : status, errBody, "");
    // a spent window's sentence says what its lock already draws ("try again after the window resets"); it stays in the
    // request's own record
    Object.assign(p, { st: "failed", tin: got.tin, tout: got.tout, fail: { word, retry: !spent, spent, said: !spent && typeof told === "string" ? cut(told) : "" } });
    attempt.failure = word;
    if (spent) attempt.lock = true;
  } else if (!got.text.trim() && !(body && !frames)) {
    Object.assign(p, { st: "failed", fail: { word: OUTCOME_WORD.empty_model, retry: true }, tin: got.tin, tout: 0 });
    attempt.failure = p.fail.word;
  } else {
    const plain = !frames && body ? (body.choices?.[0]?.message?.content ?? body.content?.[0]?.text ?? JSON.stringify(body)) : "";
    // its place in the order is taken as the answer comes, so a price looked up after it can't swap two close finishes
    Object.assign(p, { st: "done", text: got.text || plain, tin: got.tin, tout: got.tout, order: ++ui.finished });
    if (p.tin != null && p.tout != null) p.cost = await priceOf(p, reply);
  }
}
let ticker = null;
const stopTicker = () => { if (ticker && !busy()) { clearInterval(ticker); ticker = null; } };
async function run(panes) {
  for (const p of panes) measure(p);
  ui.add = false; ui.editing = false;
  if (panes.length === ui.panes.length) { ui.finished = 0; ui.back = 0; } // a new round: the order starts again
  const all = panes.map((p) => ask(p).then(() => land(p)));
  render();
  ticker ??= setInterval(tickClock, 250);
  document.getElementById("cmp")?.scrollIntoView({ block: "nearest", behavior: still() ? "auto" : "smooth" });
  await Promise.all(all);
}
const send = () => { if (sendable()) run([...ui.panes]); };
// an answer landed: its row takes its place on the rail and every bar moves to the new scales, its pane's socket takes
// its number and the answer rises in; the round's last answer pulses the round's figure once
function land(p) {
  stopTicker();
  if (!ui.panes.includes(p)) return;
  ui.done = !busy();
  p.pop = true;
  paintCmp();
  paintPane(p);
  p.pop = false; ui.done = false;
  paintSend();
}

// ---------- the comparison: every row on scales the rows share ----------
// One log axis for every row's time, from 100 ms to 5 min (10 min once a send runs past that), so 0.9 s and 40 s read
// as lengths on one line while the sends race. A time under the axis's start keeps a sliver, so an answer is never
// drawn as no time at all.
const AXIS_LO = 100;
const axisHi = () => (ui.panes.some((p) => (p.st === "sending" ? Date.now() - p.t0 : p.ms ?? 0) > 3e5) ? 6e5 : 3e5);
const axisAt = (ms, hi = axisHi()) => (ms > 0 ? Math.max(1.2, Math.min(100, (Math.log(Math.max(ms, AXIS_LO) / AXIS_LO) / Math.log(hi / AXIS_LO)) * 100)) : 0);
// (written 1m, 5m: the last decades of the log axis sit close, and "1 min" beside "5 min" would touch in a narrow column)
const ticksOf = (hi) => [[AXIS_LO, "100ms"], [1000, "1s"], [10000, "10s"], [60000, "1m"], [hi, hi > 3e5 ? "10m" : "5m"]];
const top = (xs) => Math.max(0, ...xs.filter((x) => typeof x === "number" && Number.isFinite(x)));
// a share of the largest, with a sliver kept for anything above nothing
const share = (v, max) => (v > 0 && max > 0 ? Math.max(1.5, (v / max) * 100) : 0);
// the round's figure: the time until its last answer, still running while any is out
const roundMs = () => Math.max(0, ...askedOnes().map((p) => (p.st === "sending" ? Date.now() - p.t0 : p.ms ?? 0)));
// what the send brought, then what the model is; the best of a column is the least, or the most for a window
const COLS = [
  { f: "cost", word: "Cost", best: "low", sent: true, text: money },
  { f: "tin", word: "Tokens in", sent: true, text: DRAW.n },
  { f: "tout", word: "Tokens out", sent: true, text: DRAW.n },
  { f: "win", word: "Context", best: "high", text: DRAW.big },
  { f: "pin", word: "Price in", best: "low", text: rate },
  { f: "pout", word: "Price out", best: "low", text: rate },
];
const RAIL = { done: 0, failed: 0, sending: 1, idle: 2 };
// the rail's order: the rows in the order their sends came back, answered or failed, then the ones still out, then the
// ones not asked yet, each of those in the order he chose them
const railOrder = () => ui.panes.map((p, i) => ({ p, i })).sort((a, b) => RAIL[a.p.st] - RAIL[b.p.st] || (a.p.seq ?? 0) - (b.p.seq ?? 0) || a.i - b.i).map((x) => x.p);
function figuresOf(p) {
  const m = modelOf(p), back = p.st === "done" || p.st === "failed", c = p.st === "done" ? p.cost : null;
  return {
    ms: p.st === "sending" ? Date.now() - p.t0 : back ? p.ms : null,
    cost: c?.usd ?? c?.planValue ?? null, plan: c != null && c.usd == null && c.planValue != null, reason: c?.reason ?? null,
    tin: back ? p.tin : null, tout: back ? p.tout : null,
    win: m?.window ?? null, pin: m?.rates?.input ?? null, pout: m?.rates?.output ?? null,
  };
}
function comparison() {
  const rows = railOrder(), hi = (ui.hi = axisHi()), figs = new Map(rows.map((p) => [p, figuresOf(p)])), all = [...figs.values()];
  const sc = Object.fromEntries(COLS.map(({ f }) => [f, top(all.map((x) => x[f]))]));
  // a column's best is marked only where two rows or more have it and they read differently; it is the figure as the
  // table writes it, so two windows that both read 1M are starred alike or not at all. A sent figure counts once answered.
  const pick = (vals, dir, text) => {
    const v = vals.filter((x) => typeof x === "number");
    if (v.length < 2) return null;
    const b = text(dir === "low" ? Math.min(...v) : Math.max(...v));
    return v.every((x) => text(x) === b) ? null : b;
  };
  const answered = rows.filter((p) => p.st === "done");
  const best = { ms: pick(answered.map((p) => p.ms), "low", DRAW.ms) };
  for (const c of COLS) if (c.best) best[c.f] = pick((c.sent ? answered.map((p) => figs.get(p)) : all).map((x) => x[c.f]), c.best, c.text);
  // each column's figures end on one line: the cell keeps room for the widest figure the column holds
  const room = {};
  for (const c of COLS) room[c.f] = Math.max(3, ...all.map((x) => (x[c.f] == null ? 1 : c.text(x[c.f]).length))) + (best[c.f] != null ? 2 : 0);
  return { rows, figs, sc, best, hi, room, dash: new Set(), paid: false, plan: false };
}
// a bar drawn at the width it was last painted, then grown to its new one, so a change moves instead of jumping
function barI(p, f, w, tag = "b") {
  const html = `<${tag} style="width:${(p.painted[f] ?? 0).toFixed(2)}%" data-w="${w.toFixed(2)}"></${tag}>`;
  p.painted[f] = w;
  return html;
}
const dashHtml = `<i class="nr">—</i>`;
function cellHtml(p, col, x, t) {
  const { f } = col, v = x[f], st = p.st;
  let kind = "val", text = "";
  if (col.sent && st === "idle") kind = "idle"; // what the send will bring: an empty track before it
  else if (col.sent && st === "sending") kind = "wait"; // and the stripes while it is out
  else if (v == null) {
    kind = "none"; text = dashHtml;
    // why it is a dash, said once beside the legend; a failed row's own red says why its sent figures are missing
    if (!(col.sent && st === "failed")) t.dash.add(f === "cost" ? NO_COST[x.reason] ?? "No price" : f === "win" ? "Not reported" : "No price");
  } else {
    text = col.text(v);
    if (f === "cost") { kind = x.plan ? "plan" : "paid"; if (x.plan) t.plan = true; else t.paid = true; }
  }
  const best = v != null && t.best[f] != null && col.text(v) === t.best[f] && (!col.sent || st === "done");
  const w = kind === "val" || kind === "plan" || kind === "paid" ? share(v, t.sc[f]) : 0;
  return `<span class="pg-c barcell is-${kind}${best ? " best" : ""}" role="cell" data-f="${f}" style="--v-w:${t.room[f]}ch">`
    + `<span class="vbar" aria-hidden="true">${barI(p, f, w)}</span><b class="v">${best ? STAR : ""}${text}</b></span>`;
}
// the socket: empty before a send, the working lamp while it is out, then its place in the order, or the failure mark
function sockHtml(p) {
  const pop = p.pop ? " pop" : "";
  if (p.st === "sending") return `<span class="pg-sock tlamp working" aria-hidden="true">${ICON.trace}</span>`;
  if (p.st === "failed") return `<span class="pg-sock tlamp stalled${pop}" role="img" aria-label="${esc(p.fail?.word ?? "")}">${REQVIEW.GLYPH.bang}</span>`;
  if (p.st === "done" && p.order) return `<span class="pg-sock tlamp n${pop}" role="img" aria-label="Answered ${p.order}${["st", "nd", "rd"][p.order - 1] ?? "th"}"><b>${p.order}</b></span>`;
  return `<span class="pg-sock tlamp" aria-hidden="true"></span>`;
}
function rowHtml(p, at, t, edge) {
  const c = cmdOf(p.key), x = t.figs.get(p);
  const fast = t.best.ms != null && p.st === "done" && DRAW.ms(p.ms) === t.best.ms;
  const time = `<span class="pg-c pg-time" role="cell"><span class="pg-trk" aria-hidden="true">${barI(p, "ms", x.ms == null ? 0 : axisAt(x.ms, t.hi), "i")}</span></span>`
    + `<span class="pg-c pg-tv${fast ? " best" : ""}" role="cell" data-f="msv">${x.ms == null ? "" : `${fast ? STAR : ""}${DRAW.ms(x.ms)}`}</span>`;
  return `<div class="pg-tr is-${p.st}${edge}${stale(p) ? " stale" : ""}${p.fresh ? " landed" : ""}" role="row" data-u="${p.uid}" style="grid-row:${at};--c:${colorOf(c?.family)}">`
    + `<span class="pg-c pg-railc" role="cell">${sockHtml(p)}</span>`
    + `<span class="pg-c pg-rwho" role="cell"><button class="pg-rname" type="button" data-act="goto" data-u="${p.uid}"><i class="blot" aria-hidden="true"></i><span>${esc(p.label)}</span></button>${c ? DRAW.chip({ text: c.command }) : ""}</span>`
    + time + COLS.map((col) => cellHtml(p, col, x, t)).join("") + `</div>`;
}
// the column names, said once; over the time the axis's own marks, which the rows' tracks are read against
// (the axis's ends and the rail's are named pg-a and pg-z: Sessions' stylesheet, loaded on this page, owns .last)
function headHtml(hi) {
  const ticks = ticksOf(hi).map(([ms, w], i, a) => `<span class="${i === 0 ? "pg-a" : i === a.length - 1 ? "pg-z" : ""}" style="left:${axisAt(ms, hi).toFixed(2)}%">${w}</span>`).join("");
  return `<div class="pg-tr pg-th" role="row" style="grid-row:1"><span class="pg-c" role="columnheader"></span><span class="pg-c pg-h" role="columnheader">Model</span>`
    + `<span class="pg-c pg-axis" aria-hidden="true">${ticks}</span><span class="pg-c pg-h pg-timeh" role="columnheader">Time</span>`
    + COLS.map((c) => `<span class="pg-c pg-h" role="columnheader">${c.word}</span>`).join("") + `</div>`;
}
// the dashed row where the next model lands, its socket the next one on the rail; with none chosen it is the whole plate
const addHtml = () => `<button class="add pg-add" type="button" data-act="add" aria-expanded="${pickerOpen()}" aria-controls="pick">`
  + `<span class="plus" aria-hidden="true">+</span><span class="pg-addw">Add model</span><span class="pg-offer"><b>${DRAW.n(offered())}</b> models</span>${DRAW.chev}</button>`;
// four chosen: no place is left on the rail, so the row under the last one opens the same board to swap a model, a
// solid row where the dashed one stood (pressing a chosen model there takes it off, and its place comes back)
const SWAP = '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.2" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><path d="M7 7h11l-3-3M17 17H6l3 3"/></svg>';
const swapHtml = () => `<button class="add pg-add pg-swap" type="button" data-act="add" aria-expanded="${pickerOpen()}" aria-controls="pick">`
  + `<span class="plus" aria-hidden="true">${SWAP}</span><span class="pg-addw">Change models</span><span class="pg-offer"><b>${DRAW.n(offered())}</b> models</span>${DRAW.chev}</button>`;
function tableHtml(t) {
  const n = t.rows.length;
  if (!n) return `<div class="pg-first">${addHtml()}</div>`;
  const more = n < MAX_PANES;
  const rows = t.rows.map((p, k) => rowHtml(p, k + 2, t, `${k === 0 ? " pg-a" : ""}${k === n - 1 && !more ? " pg-z" : ""}`)).join("");
  const grid = `<span class="pg-grid" aria-hidden="true" style="grid-row:2 / span ${n}">${ticksOf(t.hi).slice(1, -1).map(([ms]) => `<i style="left:${axisAt(ms, t.hi).toFixed(2)}%"></i>`).join("")}</span>`;
  const add = more ? `<div class="pg-tr pg-addrow pg-z" style="grid-row:${n + 2}"><span class="pg-c pg-railc"><span class="pg-sock tlamp open" aria-hidden="true"></span></span>${addHtml()}</div>`
    : `<div class="pg-tr pg-addrow pg-swaprow" style="grid-row:${n + 2}"><span class="pg-c"></span>${swapHtml()}</div>`;
  return `<div class="pg-table" role="table" aria-label="The models compared">${headHtml(t.hi)}${rows}${grid}${add}</div>`;
}
// the round's figure leads the table: the time until the last answer with how many answered of how many asked, and
// how many failed
function heroHtml() {
  const a = askedOnes();
  if (!a.length) return "";
  const done = a.filter((p) => p.st === "done").length, failed = a.filter((p) => p.st === "failed").length;
  return `<div class="pg-hero${a.every(stale) ? " stale" : ""}${ui.done ? " landed" : ""}"><div class="pd-top"><b class="pd-rate">${DRAW.ms(roundMs())}</b>`
    + `<span class="pd-failed"><b>${DRAW.n(done)}</b>of ${DRAW.n(a.length)} answered</span>`
    + `${failed ? `<span class="pd-failed pg-failn"><b>${DRAW.n(failed)}</b> failed</span>` : ""}</div></div>`;
}
// the kicker's line: the four places, each chosen one in its provider's colour, then the keys to what the table draws,
// each said once
function asideHtml(t) {
  const n = ui.panes.length;
  const pips = Array.from({ length: MAX_PANES }, (_, k) => { const p = ui.panes[k]; return p ? `<i class="on" style="--c:${colorOf(cmdOf(p.key)?.family)}"></i>` : "<i></i>"; }).join("");
  const parts = [`<span class="pg-slots" role="img" aria-label="${n} of ${MAX_PANES} models chosen">${pips}</span>`];
  if (!t || !n) return parts.join("");
  if (ui.panes.some((p) => p.order)) parts.push(`<span class="pg-legi"><span class="pg-sock tlamp n mini" aria-hidden="true"><b>1</b></span>Answered first</span>`);
  if (Object.values(t.best).some((v) => v != null)) parts.push(`<span class="pg-legi pg-bestk">${STAR}Best</span>`);
  if (t.paid || t.plan) parts.push(`<span class="pg-costleg">${t.paid ? `<span><i class="sw paid"></i>Charged</span>` : ""}${t.plan ? `<span><i class="sw plan"></i>On your plan, at API prices</span>` : ""}</span>`);
  parts.push(`<span class="pg-note">Prices per M tokens</span>`);
  if (t.dash.size) parts.push(`<span class="pg-legi pg-note">${dashHtml}${[...t.dash].map(esc).join(" · ")}</span>`);
  return parts.join("");
}
function cmpHtml() {
  let body = "", t = null;
  if (ui.cmds === null) body = ui.error ? unreadHtml() : ghostTableHtml();
  else if (!ui.cmds.length && !ui.error) body = noneHtml();
  else {
    t = comparison();
    const table = tableHtml(t); // drawn first: it gathers what the legend says
    body = `${ui.error ? unreadHtml() : ""}${heroHtml()}${table}`;
  }
  return `<div class="plate pg-cmp" id="cmp">${DRAW.section("Models", body, { aside: `<span class="pg-aside">${asideHtml(t)}</span>`, cls: "pg-cmps" })}</div>`;
}
// the stopwatch: each row still out grows along the shared axis and the round's figure runs, written in place
function tickClock() {
  const hi = axisHi();
  if (hi !== ui.hi) { paintCmp(); return; } // a send ran past the axis: every row moves to the longer one
  for (const p of ui.panes) {
    if (p.st !== "sending") continue;
    const row = root.querySelector(`.pg-tr[data-u="${p.uid}"]`);
    if (!row) continue;
    const ms = Date.now() - p.t0, w = axisAt(ms, hi), i = row.querySelector(".pg-trk > i");
    if (i) { i.style.width = `${w.toFixed(2)}%`; i.dataset.w = w.toFixed(2); }
    p.painted.ms = w;
    const v = row.querySelector('[data-f="msv"]');
    if (v) v.textContent = DRAW.ms(ms);
  }
  const fig = root.querySelector(".pg-hero .pd-rate");
  if (fig) fig.textContent = DRAW.ms(roundMs());
}

// ---------- drawing: the prompt ----------
// The markdown he writes a plan in and a model answers in: headings, paragraphs, bullets, numbered steps, checkboxes,
// tables, rules, code and bold. A heading is never smaller than the text under it.
function mdPlan(text) {
  const out = [], code = [];
  let list = null, fence = false, table = null;
  const flush = () => { if (list) { out.push(`<${list.tag}${list.cls}>${list.items.join("")}</${list.tag}>`); list = null; } };
  const item = (tag, cls, html) => { if (list?.tag !== tag || list.cls !== cls) { flush(); list = { tag, cls, items: [] }; } list.items.push(`<li>${html}</li>`); };
  const cellsOf = (l) => l.trim().replace(/^\|/, "").replace(/\|$/, "").split("|").map((c) => c.trim());
  const endTable = () => {
    if (!table) return;
    const [head, rule, ...rows] = table;
    if (rule && /^\s*\|?\s*:?-{3,}/.test(rule)) {
      out.push(`<table><thead><tr>${cellsOf(head).map((c) => `<th>${inline(c)}</th>`).join("")}</tr></thead>`
        + `<tbody>${rows.map((r) => `<tr>${cellsOf(r).map((c) => `<td>${inline(c)}</td>`).join("")}</tr>`).join("")}</tbody></table>`);
    } else for (const l of table) out.push(`<p>${inline(l)}</p>`);
    table = null;
  };
  for (const line of text.split("\n")) {
    if (line.trim().startsWith("```")) { // a code fence: its lines are kept as written
      endTable();
      if (fence) { out.push(`<pre><code>${esc(code.join("\n"))}</code></pre>`); code.length = 0; fence = false; } else { flush(); fence = true; }
      continue;
    }
    if (fence) { code.push(line); continue; }
    if (/^\s*\|.*\|\s*$/.test(line)) { flush(); (table ??= []).push(line); continue; }
    endTable();
    let m;
    if ((m = /^\s*[-*]\s+\[([ xX])\]\s+(.*)$/.exec(line))) item("ul", ' class="tasks"', `<span class="pg-box${/[xX]/.test(m[1]) ? " on" : ""}" aria-hidden="true"></span><span>${inline(m[2])}</span>`);
    else if (/^\s*([-*_])(\s*\1){2,}\s*$/.test(line)) { flush(); out.push("<hr>"); }
    else if ((m = /^\s*[-*]\s+(.*)$/.exec(line))) item("ul", "", inline(m[1]));
    else if ((m = /^\s*\d+[.)]\s+(.*)$/.exec(line))) item("ol", "", inline(m[1]));
    else {
      flush();
      if ((m = /^(#{1,6})\s+(.*)$/.exec(line))) { const h = Math.min(m[1].length, 4) + 2; out.push(`<h${h}>${inline(m[2])}</h${h}>`); }
      else if (line.trim()) out.push(`<p>${inline(line)}</p>`);
    }
  }
  flush(); endTable();
  if (fence) out.push(`<pre><code>${esc(code.join("\n"))}</code></pre>`);
  return out.join("");
}
// THE CHANGE, NOT A SECOND COPY: the prompt the dimmed answers were asked is marked in the one he has now, word by word
// over the plan's own lines, the words he added underlined and the ones he took out struck where they stood. A line's
// own mark (a bullet, a number, a heading, a box) and a line of code each count as one word.
const MARK = { ins: ["", ""], del: ["", ""] };
function wordsOf(text) {
  const out = [];
  let fence = false;
  for (const line of text.split("\n")) {
    const edge = line.trim().startsWith("```");
    if (edge) fence = !fence;
    if (fence || edge) out.push({ t: line, line: true });
    else {
      const m = /^(\s*(?:[-*]\s+(?:\[[ xX]\]\s+)?|\d+[.)]\s+|#{1,6}\s+|>\s*)?)(.*)$/.exec(line);
      if (m[1]) out.push({ t: m[1], line: true });
      for (const w of m[2].split(/\s+/).filter(Boolean)) out.push({ t: w });
    }
    out.push({ t: "\n", line: true });
  }
  out.pop();
  return out;
}
function marked(was, now) {
  const a = wordsOf(was), b = wordsOf(now);
  let s = 0;
  while (s < a.length && s < b.length && a[s].t === b[s].t) s++;
  let e = 0;
  while (e < a.length - s && e < b.length - s && a[a.length - 1 - e].t === b[b.length - 1 - e].t) e++;
  const A = a.slice(s, a.length - e), B = b.slice(s, b.length - e);
  if (A.length * B.length > 1.5e6) return mdPlan(now); // a rewrite too long to walk: the earlier one stays in each request
  // the longest run of words the two share, then the walk through it
  const w = B.length + 1, L = new Uint16Array((A.length + 1) * w);
  for (let i = A.length - 1; i >= 0; i--) {
    for (let j = B.length - 1; j >= 0; j--) L[i * w + j] = A[i].t === B[j].t ? L[(i + 1) * w + j + 1] + 1 : Math.max(L[(i + 1) * w + j], L[i * w + j + 1]);
  }
  const ops = b.slice(0, s).map((x) => ["eq", x]);
  let i = 0, j = 0;
  while (i < A.length || j < B.length) {
    if (i < A.length && j < B.length && A[i].t === B[j].t) { ops.push(["eq", B[j]]); i++; j++; }
    else if (j < B.length && (i === A.length || L[i * w + j + 1] >= L[(i + 1) * w + j])) { ops.push(["ins", B[j]]); j++; }
    else { ops.push(["del", A[i]]); i++; }
  }
  ops.push(...b.slice(b.length - e).map((x) => ["eq", x]));
  // the new text's own lines and marks, each run of changed words wrapped in its mark; an old line's mark is not drawn
  let txt = "", fresh = true, run = null;
  const close = () => { if (run) { txt += MARK[run][1]; run = null; } };
  for (const [op, x] of ops) {
    if (x.line) {
      if (op === "del") continue;
      close(); txt += x.t; fresh = x.t === "\n" || /\s$/.test(x.t);
      continue;
    }
    if (op !== "eq" && run === op) { txt += ` ${x.t}`; continue; }
    close();
    if (!fresh) txt += " ";
    if (op !== "eq") { txt += MARK[op][0]; run = op; }
    txt += x.t; fresh = false;
  }
  close();
  return mdPlan(txt).replace(//g, "<ins>").replace(//g, "</ins>").replace(//g, "<del>").replace(//g, "</del>");
}
// The prompt is one surface (fin, Oct 12): his plan reads as it renders, the way the answers do; a click or Enter on it
// turns it into the field, caret at the end, and leaving the field (a click elsewhere, Escape, a send) renders it again.
// Both are set in one face at one size, so the words stay where they were. An empty prompt is the field.
const writing = () => ui.editing || !ui.prompt.trim();
// the prompt the dimmed answers were asked: the latest of them
const earlier = () => ui.panes.filter(stale).sort((a, b) => b.at - a.at)[0]?.asked ?? null;
function fieldHtml() {
  ui.marks = "";
  if (writing()) return `<textarea class="field" id="prompt" rows="5" placeholder="Write a question or a plan" aria-label="Prompt" spellcheck="false">${esc(ui.prompt)}</textarea>`;
  const was = earlier(), html = was != null ? marked(was, ui.prompt) : mdPlan(ui.prompt);
  // the legend keys only the marks the plan carries: words added, words taken out, or both
  if (was != null) ui.marks = `${html.includes("<ins>") ? "<ins>Added</ins>" : ""}${html.includes("<del>") ? "<del>Removed</del>" : ""}`;
  return `<div class="pg-md-out pg-mdx" role="button" tabindex="0" data-act="write" aria-label="Prompt, edit">${html}</div>`;
}
// pg-read, not the console's own .read (a muted line of data, centred), which would set his plan in its box's middle
const mdHtml = () => `<div class="pg-md${writing() ? "" : " pg-read"}${ui.swap ? " swap" : ""}">${fieldHtml()}</div>`;
const diffLegend = () => (ui.marks ? `<span class="pg-dleg">${ui.marks}</span>` : "");
// Send is the page's one warm control: the key light's one use in the view
const sendHtml = () => `<button class="act primary pg-send" type="button" data-act="send" aria-keyshortcuts="Control+Enter Meta+Enter"${sendable() ? "" : " disabled"}${busy() ? ' aria-busy="true"' : ""}>${ICON.send}<span>${busy() ? "Sending" : "Send"}</span></button>`;
function askHtml() {
  const go = `<div class="pg-go"><span class="pg-keys" aria-hidden="true"><kbd>Ctrl</kbd><kbd>Enter</kbd></span>${sendHtml()}</div>`;
  return `<div class="plate pg-ask">${DRAW.section("Prompt", mdHtml() + go, { aside: `<span class="pg-paside">${diffLegend()}</span>`, cls: "pg-prompt" })}</div>`;
}
// only the prompt is swapped, never the page: Send under the pointer must stay where it was pressed
function paintPrompt() {
  const md = root.querySelector(".pg-md"), aside = root.querySelector(".pg-paside");
  if (md) md.outerHTML = mdHtml();
  if (aside) aside.innerHTML = diffLegend();
  ui.swap = false;
  fadeMore();
}
// a plan longer than the box it reads in: the box's foot fades while more of the plan lies below, until he scrolls to
// its end
function fadeMore() {
  const out = root.querySelector(".pg-md-out");
  if (out) out.parentElement.classList.toggle("pg-more", out.scrollHeight - out.scrollTop - out.clientHeight > 2);
}
// the prompt changed under answers: the answers to the earlier one dim, with their rows and the round's figure
const oldLegend = `<span class="pg-oldleg"><i class="sw" aria-hidden="true"></i>Earlier prompt</span>`;
function paintStale() {
  for (const p of ui.panes) {
    root.querySelector(`.pgp[data-u="${p.uid}"]`)?.classList.toggle("stale", stale(p));
    root.querySelector(`.pg-tr[data-u="${p.uid}"]`)?.classList.toggle("stale", stale(p));
  }
  const a = askedOnes();
  root.querySelector(".pg-hero")?.classList.toggle("stale", a.length > 0 && a.every(stale));
  const head = root.querySelector(".pg-ahead");
  head?.querySelector(".pg-oldleg")?.remove();
  if (ui.panes.some(stale)) head?.insertAdjacentHTML("beforeend", oldLegend);
}
// the field opens as tall as the plan read, so the plate doesn't jump under his click, and grows with what he writes;
// its height is offered as --fit, which the stylesheet takes where the prompt stands alone and leaves where the prompt
// shares its row with the models plate and takes that row's height (compare.css)
function openField(h) {
  ui.editing = true; ui.swap = true; paintPrompt();
  const f = document.getElementById("prompt");
  if (!f) return;
  f.style.setProperty("--fit", `${Math.max(h, f.scrollHeight + 4)}px`);
  f.focus({ preventScroll: true }); f.setSelectionRange(f.value.length, f.value.length);
}
function closeField() {
  if (!ui.editing) return;
  ui.editing = false;
  if (writing()) return; // emptied: the field stays
  ui.swap = true; paintPrompt();
}
const fitField = (f) => { f.style.setProperty("--fit", "auto"); f.style.setProperty("--fit", `${f.scrollHeight + 4}px`); };
// Send is written in place, so a press or a focus on it survives an answer landing
function paintSend() {
  const b = root.querySelector(".pg-send");
  if (!b) return;
  b.disabled = !sendable();
  if (busy()) b.setAttribute("aria-busy", "true"); else b.removeAttribute("aria-busy");
  b.querySelector("span").textContent = busy() ? "Sending" : "Send";
}

// ---------- drawing: the answers ----------
// the account as Accounts heads its card: its name and plan, "Account" until it has a name, a key by its variable
function acctHtml(a, { door = false } = {}) {
  if (a.kind === "key") return `<span class="pg-key"><span class="pg-an var">${esc(a.var)}</span>${a.tail ? `<span class="pg-tail">${esc(a.tail)}</span>` : ""}</span>`;
  if (a.kind === "none" || !a.name) return "";
  const name = `<span class="pg-an">${esc(a.name)}</span>${a.plan ? `<span class="pg-plan">${esc(a.plan)}</span>` : ""}`;
  // the door names the command whose next login it is, as Set budget does: Accounts' ?head= lands on that command's
  // login and pulses it once (accounts.js ADDR), where its own Name it is
  const to = a.head ? `accounts.html?head=${enc(a.head)}` : "accounts.html";
  return a.kind === "unnamed" && door ? `${name}<a class="act small pg-nameit" href="${to}">Name it${DRAW.go}</a>` : name;
}
const SEP = `<i class="sep" aria-hidden="true">·</i>`;
function metaHtml(p, c) {
  if (!c) return "";
  const parts = [`<span class="pv"><i class="blot" aria-hidden="true"></i>${esc(c.title)}</span>`];
  if (p.vendor) parts.push(`<span>${esc(p.vendor)}</span>`);
  const a = acctHtml(c.acct, { door: true }); // a login with no name yet keeps its door to Accounts here too
  if (a) parts.push(`<span class="who">${a}</span>`);
  return `${parts.join(SEP)}${DRAW.chip({ text: c.command })}`;
}
// the request this pane sent opens under its head; Remove at the pane's right edge. A pane that holds an answer arms
// first: its acts turn into Remove and Keep in place, and only the second press takes it away. One whose answer is still
// out goes at once, and its answer is dropped when it lands.
const holds = (p) => p.st === "done" || p.st === "failed";
function actsHtml(p) {
  if (p.arm) return `<div class="pg-acts armed"><button class="act small" type="button" data-act="drop">Remove</button>`
    + `<button class="act quiet small" type="button" data-act="keep">Keep</button></div>`;
  const rq = p.trace && p.st !== "sending" ? DRAW.disc({ open: p.req, controls: `rq-${p.uid}`, act: "req", cls: "pg-reqd", inner: "<span>Request</span>" }) : "";
  return `<div class="pg-acts">${rq}<button class="icon pg-x" type="button" data-act="close" aria-label="Remove ${esc(p.label)}">${ICON.close}</button></div>`;
}
// a failure's own acts: the one that fixes it, beside its word
function fixActs(p) {
  const f = p.fail;
  if (f.door) return `<a class="act small" href="accounts.html?budget=${enc(f.door)}">Set budget${DRAW.go}</a>`;
  // a refused or missing sign-in opens Accounts on what this command needs fixed (its ?head= door)
  if (SIGNED.has(f.word)) return `<a class="act small" href="accounts.html?head=${enc(p.key)}">Accounts${DRAW.go}</a>`;
  // a runtime that is not up starts from its command on Models (models.html?cmd= takes the head's key); once it runs,
  // Try again asks this pane alone
  if (f.word === DOWN) return `<a class="act small" href="models.html?cmd=${enc(p.key)}">Models${DRAW.go}</a><button class="act quiet small" type="button" data-act="retry">Try again</button>`;
  if (f.reread) return DRAW.act({ word: "Read again", act: "reread", cls: "small", busy: ui.reading });
  if (f.retry) return `<button class="act small" type="button" data-act="retry">Try again</button>`;
  return "";
}
// a failure: its word and the act that fixes it, then what the provider, or splice, said, folded to one line until he
// opens it. A spent plan window is its lock, when it frees and how far along its window is, in place of any act.
function fixHtml(p) {
  const f = p.fail, c = cmdOf(p.key);
  const tries = p.tries > 1 ? DRAW.statTile(DRAW.glyph.retry, DRAW.n(p.tries), "tries") : "";
  let row;
  if (f.spent) {
    const along = f.spent.secs ? Math.max(0, Math.min(1, 1 - (f.spent.until - Date.now()) / 1000 / f.spent.secs)) : null;
    row = `${along != null ? `<span class="pg-reset">${DRAW.ring(along)}</span>` : ""}${f.spent.word ? `<span class="pg-win">${esc(f.spent.word)}</span>` : ""}`
      + DRAW.lock(`${LIMIT} until ${backWord(f.spent.until)}`, { pop: true });
  } else row = `<span class="state limit pg-word">${esc(f.word)}</span>${fixActs(p)}`;
  const text = f.said || (f.code != null ? `Answered ${f.code} with no reason` : "");
  const who = f.splice ? "splice" : titleOf(c?.family, c?.command ?? "");
  const said = text ? `<div class="pg-said${p.said ? " open" : ""}">${DRAW.disc({ open: p.said, act: "said", cls: "pg-saidd", inner: `<span class="who">${esc(who)}</span><q>${esc(text)}</q>` })}</div>` : "";
  return `<div class="pg-fixrow${p.tries > 1 ? " again" : ""}">${row}${tries}</div>${said}`;
}
// ---------- the request, in Requests' own open request (reqview.js), inside its own pane ----------
// The pane's head already names the model, its command and its account, and its row draws the request's time, tokens
// and cost on the shared scales, so reqview is asked for its record alone: no head (head: false), no figures (cells: []),
// and no time rail (rail: false), which would be one bar, the playground's answer carrying no first-byte time.
function reqHtml(p) {
  const c = cmdOf(p.key), ts = new Date(+p.at + p.ms);
  const r = { id: `${p.key}:${p.id}:${p.uid}`, cmd: c?.command ?? p.key, provider: c?.family, model: "", ts, total: p.ms, in: p.tin, out: p.tout, cached: 0, attempts: 1 };
  p.RV ??= REQVIEW.view(() => p.rv);
  return `<div class="pg-req${p.reqNew ? " unfold" : ""}${p.rvSwap ? " swap" : ""}" id="rq-${p.uid}"><p class="pg-reqat">Asked<time>${REQVIEW.dayOf(p.at)}${REQVIEW.clockS(p.at)}</time></p>`
    // a finished answer's words stand under this record in the same pane, so the Returned tab keeps what only the record
    // knows (its status, how the stream ended, its tool calls and its frames) and the words are set once
    + `${p.RV.paneHtml(r, { head: false, st: "kept", tr: () => p.trace, cells: [], rail: false, reply: p.st !== "done", names: { head: r.cmd, model: modelOf(p)?.label } })}</div>`;
}
// the answer's shape while it is out: as many lines as the last answer here ran to, else a short answer's
function waitHtml(p) {
  const n = Math.max(6, Math.min(24, p.lines ?? 9));
  return `<div class="ghost-lines" aria-hidden="true">${Array.from({ length: n }, (_, k) => `<i class="ghost" style="width:${k === n - 1 ? 38 : 72 + ((k * 13) % 26)}%"></i>`).join("")}</div>`;
}
function bodyHtml(p) {
  const rec = p.req && p.trace ? reqHtml(p) : "";
  if (p.st === "sending") return waitHtml(p);
  if (p.st === "failed") return rec + fixHtml(p);
  if (p.st === "done") return `${rec}<div class="msg agent"><div class="body pg-mdx pg-ans">${mdPlan(p.text)}</div></div>`;
  // a pane chosen after the others were asked says so once, where its answer will land
  return ui.panes.some((o) => o.st !== "idle") ? `<p class="pg-idle">${DRAW.nothing("Not asked yet")}</p>` : "";
}
// how many of the answers stand across: as many as fit at LANE_REM, never more than there are; the last of an
// uneven row takes the rest of its row, so no row ends in an empty lane
const lanesOf = () => Math.min(ui.panes.length || 1, ui.fit?.lanes ?? lanesFit());
function spanOf(i) {
  const n = ui.panes.length, lanes = lanesOf();
  return i === n - 1 && n % lanes ? lanes - ((n - 1) % lanes) : 1;
}
function paneHtml(p, i) {
  const c = cmdOf(p.key), span = spanOf(i);
  // a pane that stands alone in its row (one model, or the last of an uneven row) has a whole row to itself; an answer
  // fills it with columns, a failure's one line would not, so a failure keeps to a reading width there
  const alone = span > 1 || lanesOf() === 1;
  return `<article class="pgp is-${p.st}${alone ? " alone" : ""}${stale(p) ? " stale" : ""}${p.fresh ? " landed" : ""}" id="ans-${p.uid}" data-u="${p.uid}" style="--c:${colorOf(c?.family)}${span > 1 ? `;grid-column:span ${span}` : ""}" aria-label="${esc(p.label)}">`
    + `<header class="pg-head">${sockHtml(p)}<div class="pg-who"><h3 class="pg-model">${esc(p.label)}</h3><div class="pg-meta">${metaHtml(p, c)}</div></div>${actsHtml(p)}</header>`
    + `<div class="pg-body${p.pop ? " rise" : ""}">${bodyHtml(p)}</div></article>`;
}
function answersHtml() {
  if (ui.cmds === null) return ui.error ? "" : ghostAnswersHtml();
  if (!ui.panes.length) return "";
  return `<section class="pg-answers" aria-label="Answers"><div class="sechead pg-ahead"><h2 class="kicker">Answers</h2>${ui.panes.some(stale) ? oldLegend : ""}</div>`
    + `<div class="pg-lanes" style="--lanes:${lanesOf()}">${ui.panes.map((p, i) => paneHtml(p, i)).join("")}</div></section>`;
}

// ---------- drawing: the board, providers as Accounts draws them, packed into columns ----------
const norm = (s) => String(s ?? "").toLowerCase();
const terms = () => norm(ui.q).split(/\s+/).filter(Boolean);
const hits = (c, m, t) => { const hay = norm(`${m.label} ${m.id} ${c.command} ${c.title} ${c.acct.name ?? ""} ${c.acct.var ?? ""}`); return t.every((w) => hay.includes(w)); };
// a model: its box and its name; four chosen leaves the rest off, the board's head saying why
function mrowHtml(c, m, name) {
  const on = Boolean(paneOf(c.key, m.id)), off = !on && ui.panes.length >= MAX_PANES;
  return `<button class="pg-mrow" type="button" aria-pressed="${on}" data-act="toggle" data-key="${esc(c.key)}" data-model="${esc(m.id)}"${off ? " disabled" : ""}>`
    + `<span class="pg-tick" aria-hidden="true">${TICK}</span><span class="pg-mn">${esc(name(m))}</span></button>`;
}
// A list's models under the context window they share, the largest first, the window said once over them as a bar on
// the board's one scale; a long list shows its first rows and the rest behind "+N", opening in place.
function listHtml(c, list, k, maxWin, { whole, first }, name) {
  const long = list.length > whole, open = ui.open.has(k);
  const sorted = list.map((m, i) => ({ m, i })).sort((a, b) => (b.m.window ?? -1) - (a.m.window ?? -1) || a.i - b.i).map((x) => x.m);
  const shown = long && !open ? sorted.slice(0, first) : sorted;
  const groups = [];
  for (const m of shown) { const g = groups.at(-1), win = m.window ?? null; if (g && g.win === win) g.ms.push(m); else groups.push({ win, ms: [m] }); }
  const data = ` data-k="${esc(k)}"`;
  const more = !long ? "" : open ? DRAW.more(0, { pill: true, open: true, less: "Fewer", act: "more", data }) : DRAW.more(list.length - shown.length, { pill: true, act: "more", data });
  const html = groups.map((g) => `<div class="pg-wg"><div class="pg-wh">${g.win ? DRAW.vbar(share(g.win, maxWin)) : `<span class="vbar" aria-hidden="true"></span>`}<b>${g.win ? DRAW.big(g.win) : dashHtml}</b></div>`
    + `<div class="pg-rows">${g.ms.map((m) => mrowHtml(c, m, name)).join("")}</div></div>`).join("") + more;
  return { html, w: shown.length + 0.9 * groups.length + (more ? 1 : 0) };
}
// a command whose models come from three vendors or more (OpenRouter) is grouped by vendor, found or not
const vendorsIn = (models) => new Set(models.map((m) => m.vendor ?? "")).size;
function vendorsHtml(c, list, maxWin, t) {
  const by = new Map();
  for (const m of list) { const v = m.vendor ?? ""; if (!by.has(v)) by.set(v, []); by.get(v).push(m); }
  // each vendor a row that opens its models under itself, the largest first, its count a bar on the largest's scale
  const vendors = [...by].sort((a, b) => b[1].length - a[1].length || a[0].localeCompare(b[0]));
  const all = ui.open.has(c.key) || t.length, shown = all ? vendors : vendors.slice(0, VENDORS);
  const most = Math.max(...vendors.map(([, ms]) => ms.length));
  let w = 0;
  const rows = shown.map(([v, ms]) => {
    const k = `${c.key}|${v}`, open = ui.open.has(k) || t.length > 0;
    const head = DRAW.disc({ open, act: "vendor", data: ` data-k="${esc(k)}"`, cls: "pg-vrow", inner: `<span class="pg-vn">${esc(v || "Other")}</span>${DRAW.barCell({ w: share(ms.length, most), text: DRAW.n(ms.length), cls: "pg-vc" })}` });
    w += 1;
    if (!open) return `<div class="pg-vend">${head}</div>`;
    const l = listHtml(c, ms, `${k}|more`, maxWin, VENDOR_LIST, (m) => m.short);
    w += l.w;
    return `<div class="pg-vend open">${head}<div class="pg-vlist${ui.unfold === k ? " unfold" : ""}">${l.html}</div></div>`;
  }).join("");
  const more = t.length || vendors.length <= VENDORS ? ""
    : DRAW.more(ui.open.has(c.key) ? 0 : vendors.length - VENDORS, { word: "more vendors", act: "more", data: ` data-k="${esc(c.key)}"`, open: ui.open.has(c.key), less: "Fewer vendors" });
  return { html: `${rows}${more}`, w: w + (more ? 1 : 0) };
}
function cardOf(c, list, maxWin, t) {
  // a command out of quota or down says so once on its card, with the door to where it is fixed
  const state = c.held ? `<div class="pg-st">${DRAW.lock(c.held.until ? `${LIMIT} until ${backWord(c.held.until)}` : LIMIT)}<a class="act small quiet" href="accounts.html?head=${enc(c.key)}">Accounts${DRAW.go}</a></div>`
    : c.down ? `<div class="pg-st">${DRAW.nothing(DOWN)}<a class="act small quiet" href="models.html?cmd=${enc(c.key)}">Models${DRAW.go}</a></div>` : "";
  // a vendor's name is said over its models, so a grouped list names each model by the rest of its label
  const grouped = vendorsIn(c.models) >= 3;
  const body = grouped ? vendorsHtml(c, list, maxWin, t) : listHtml(c, list, c.key, maxWin, LIST, (m) => m.label);
  return {
    html: `<article class="card pg-cmd${c.held ? " held" : ""}${c.down ? " down" : ""}" style="--c:${colorOf(c.family)}"><div class="pg-ch">${acctHtml(c.acct, { door: true })}${DRAW.chip({ text: c.command })}</div>${state}<div class="pg-list${grouped ? " pg-vends" : ""}">${body.html}</div></article>`,
    w: 2.6 + (state ? 1.2 : 0) + body.w,
  };
}
// how many of [rem] fit across [px], the board's column gap between them
const remPx = () => parseFloat(getComputedStyle(document.documentElement).fontSize) || 16;
const fit = (px, rem) => { const r = remPx(), gap = 2 * r; return Math.max(1, Math.floor((px + gap) / (rem * r + gap))); };
const boardFit = () => fit(root.clientWidth - 8 * 0.667 * remPx(), COL_REM);
const lanesFit = () => fit(root.clientWidth, LANE_REM);
// The board's tallest column is as short as it can be (Accounts' rule): each provider into the shortest column so far,
// and the heaviest may take k columns, its cards or its one card's list flowing down them, when that makes the tallest
// column shorter by 15% or more and leaves a column for the rest.
function boardCols(provs, n) {
  const group = (pv, k) => DRAW.group({ name: pv.title, c: colorOf(pv.family), cls: `titled pg-prov${k > 1 ? ` wide${pv.cards.length === 1 ? " one" : ""}` : ""}`, style: k > 1 ? `--k:${k}` : "", body: DRAW.cards(pv.cards.map((x) => x.html)) });
  const tall = (cols) => Math.max(0, ...cols.map((c) => c.w));
  const base = DRAW.packCols(provs, Math.min(n, provs.length), (pv) => pv.w);
  let best = { k: 1, cols: base, tall: tall(base) };
  const big = provs.reduce((m, pv) => (pv.w > m.w ? pv : m), provs[0]), rest = provs.filter((pv) => pv !== big);
  for (let k = 2; k <= n; k++) {
    const m = Math.min(n - k, rest.length);
    if (rest.length && m < 1) break;
    if ((big.w - 2.6) / k < 8) break; // a list too short to fill k columns would leave all but its first empty
    const cols = rest.length ? DRAW.packCols(rest, m, (pv) => pv.w) : [];
    const h = Math.max(2.6 + (big.w - 2.6) / k, tall(cols));
    if (h < best.tall && h < 0.85 * tall(base)) best = { k, cols, tall: h };
  }
  const out = best.k > 1 ? [{ body: group(big, best.k), span: best.k }] : [];
  for (const col of best.cols) out.push({ body: col.items.map((pv) => group(pv, 1)).join("") });
  return DRAW.boardCols(out);
}
function boardHtml() {
  const t = terms(), maxWin = top(ui.cmds.flatMap((c) => c.models.map((m) => m.window)));
  // ready commands first, then the ones out of quota, then the ones that are down; each provider under its own title
  const rank = (c) => (c.down ? 2 : c.held ? 1 : 0);
  const provs = [];
  for (const c of [...ui.cmds].sort((a, b) => rank(a) - rank(b))) {
    const list = t.length ? c.models.filter((m) => hits(c, m, t)) : c.models;
    if (!list.length) continue;
    let pv = provs.find((x) => x.title === c.title);
    if (!pv) provs.push((pv = { title: c.title, family: c.family, cards: [], w: 2.6 }));
    const card = cardOf(c, list, maxWin, t);
    pv.cards.push(card); pv.w += card.w + 0.7;
  }
  ui.unfold = null;
  if (!provs.length) return `<p class="pg-nomatch">${DRAW.nothing("No model matches")}</p>`;
  return boardCols(provs, ui.fit?.board ?? boardFit());
}
const matched = () => { const t = terms(); return (ui.cmds || []).reduce((a, c) => a + c.models.filter((m) => !t.length || hits(c, m, t)).length, 0); };
const countHtml = () => (terms().length ? `<b>${DRAW.n(matched())}</b> of ${DRAW.n(offered())}` : `<b>${DRAW.n(offered())}</b> models`);
function pickerHtml() {
  // four chosen: the lock stands where the rows went off, in the board's own head
  const full = ui.panes.length >= MAX_PANES ? DRAW.lock(`${MAX_PANES} of ${MAX_PANES} chosen`, { pop: true }) : "";
  const head = `<div class="pg-find"><label class="search pg-search">${FIND}<input class="field" id="find" type="search" placeholder="Find a model" value="${esc(ui.q)}" autocomplete="off" spellcheck="false" aria-label="Find a model"></label>`
    + `<span class="pg-count" aria-live="polite">${countHtml()}</span><span class="pg-leg">${DRAW.vbar(100)}Context window</span>${full}`
    + `<button class="icon pg-shut" type="button" data-act="shut" aria-label="Close the models">${ICON.close}</button></div>`;
  return `<div class="plate pg-pick${ui.opened ? " opening" : ""}" id="pick" role="group" aria-label="Models to compare">${head}<div class="board pg-board">${boardHtml()}</div></div>`;
}
function paintBoard() {
  const b = root.querySelector(".pg-board"), n = root.querySelector(".pg-count");
  if (b) b.innerHTML = boardHtml();
  if (n) n.innerHTML = countHtml();
}

// ---------- drawing: what stands in while the reads run, and when they fail ----------
// each shape the size of what lands there: the rows kept from last time, the board's columns, the panes' heads
function ghostTableHtml() {
  const kept = keptModels().length;
  if (!kept) return `<div class="pg-first"><div class="add pg-add ghosted" aria-hidden="true">${GHOST.bar(11)}</div></div>`;
  const rows = Array.from({ length: kept }, (_, k) => `<div class="pg-tr ghosted${k === 0 ? " pg-a" : ""}${k === kept - 1 && kept >= MAX_PANES ? " pg-z" : ""}" style="grid-row:${k + 2};--i:${k}" aria-hidden="true">`
    + `<span class="pg-c pg-railc"><span class="pg-sock tlamp"></span></span><span class="pg-c pg-rwho">${GHOST.bar(10)}${GHOST.chip(5.5)}</span>`
    + `<span class="pg-c pg-time">${GHOST.track()}</span><span class="pg-c pg-tv">${GHOST.fig(3)}</span>${COLS.map(() => `<span class="pg-c">${GHOST.cell()}</span>`).join("")}</div>`).join("");
  const add = kept < MAX_PANES ? `<div class="pg-tr pg-addrow pg-z" style="grid-row:${kept + 2}"><span class="pg-c pg-railc"><span class="pg-sock tlamp open"></span></span><div class="add pg-add ghosted" aria-hidden="true">${GHOST.bar(11)}</div></div>`
    : `<div class="pg-tr pg-addrow pg-swaprow" style="grid-row:${kept + 2}"><span class="pg-c"></span><div class="add pg-add pg-swap ghosted" aria-hidden="true">${GHOST.bar(11)}</div></div>`;
  return `<div class="pg-table">${headHtml(axisHi())}${rows}${add}</div>`;
}
function ghostPickHtml() {
  if (keptModels().length >= MAX_PANES) return "";
  const provs = [8, 6, 7, 9, 2, 9, 3, 1, 1].map((rows, i) => ({ i, rows, w: 5.2 + rows }));
  const cols = DRAW.packCols(provs, Math.min(boardFit(), provs.length), (x) => x.w);
  const prov = (x) => `<section class="titled pg-prov ghosted" style="--i:${x.i % 3}" aria-hidden="true"><header>${GHOST.bar(9, "big")}</header>`
    + `${GHOST.card([15, ...Array.from({ length: x.rows }, (_, k) => 17 + ((k * 7 + x.i * 3) % 6))], 0, "pg-cmd")}</section>`;
  return `<div class="plate pg-pick" aria-hidden="true"><div class="pg-find">${GHOST.bar(24, "chip")}${GHOST.bar(6)}</div><div class="board pg-board">${DRAW.boardCols(cols.map((c) => ({ body: c.items.map(prov).join("") })))}</div></div>`;
}
function ghostAnswersHtml() {
  const kept = keptModels().length;
  if (!kept) return "";
  const pane = (i) => `<article class="pgp ghosted" style="--i:${i}" aria-hidden="true"><header class="pg-head"><span class="pg-sock tlamp"></span>`
    + `<div class="pg-who">${GHOST.bar(13, "big")}<div class="pg-meta">${GHOST.bar(12)}${GHOST.bar(7.5, "chip")}</div></div></header><div class="pg-body"></div></article>`;
  return `<section class="pg-answers" aria-hidden="true"><div class="sechead pg-ahead"><h2 class="kicker">Answers</h2></div>`
    + `<div class="pg-lanes" style="--lanes:${Math.min(kept, lanesFit())}">${Array.from({ length: kept }, (_, i) => pane(i)).join("")}</div></section>`;
}
const unreadHtml = () => `<div class="pg-unread"><span class="state limit">${esc(ui.error)}</span>${DRAW.act({ word: "Read again", act: "reread", busy: ui.reading })}`
  + `${ui.error.startsWith("Sign") ? `<a class="act quiet" href="accounts.html">Accounts${DRAW.go}</a>` : ""}</div>`;
// nothing can send: said once, then each command that can't, why, and the door that fixes it
function noneHtml() {
  const rows = ui.blocked.map((b) => `<li style="--c:${colorOf(b.family)}">${DRAW.chip({ text: b.command })}<span class="pg-why${b.bad ? " bad" : ""}">${esc(b.word)}</span>`
    + `<a class="act small quiet" href="${b.door === "models" ? `models.html?cmd=${enc(b.key)}` : `accounts.html?head=${enc(b.key)}`}">${b.door === "models" ? "Models" : "Accounts"}${DRAW.go}</a></li>`).join("");
  return `<div class="pg-none">${DRAW.nothing("No model can send now")}${rows ? `<ul class="pg-blocked">${rows}</ul>` : `<a class="act small" href="models.html">Models${DRAW.go}</a>`}</div>`;
}

// ---------- the page ----------
// what has focus, so a redraw can give it back: the field and its caret, or the row or act he pressed, in its own pane
function focused() {
  const a = document.activeElement;
  if (!a || !root.contains(a)) return null;
  const d = a.dataset ?? {}, q = (k, v) => (v == null ? "" : `[data-${k}="${CSS.escape(v)}"]`);
  const key = a.id ? `#${CSS.escape(a.id)}` : d.act ? `[data-act="${CSS.escape(d.act)}"]${q("key", d.key)}${q("model", d.model)}${q("k", d.k)}${q("v", d.v)}${q("p", d.p)}${q("u", d.u)}` : null;
  const host = a.closest(".pgp");
  return key && { key, sel: typeof a.selectionStart === "number" ? [a.selectionStart, a.selectionEnd] : null, host: host?.dataset.u ? `.pgp[data-u="${host.dataset.u}"]` : null };
}
// focus goes back to the same control, inside the same pane; a closed request gives it to its pane's Request
function refocus(f) {
  if (!f) return;
  const el = f.host ? root.querySelector(f.host)?.querySelector(f.key) ?? root.querySelector(f.host)?.querySelector('[data-act="req"]') : root.querySelector(f.key);
  if (!el) return;
  el.focus({ preventScroll: true });
  if (f.sel && el.setSelectionRange) el.setSelectionRange(...f.sel);
}
// every bar drawn at its last width grows to its new one, together
function grow() {
  requestAnimationFrame(() => requestAnimationFrame(() => {
    for (const b of root.querySelectorAll("[data-w]")) b.style.width = `${b.dataset.w}%`;
  }));
}
// a row that changed places travels there from where it stood (the rail's order as answers land)
function travel(before) {
  if (still()) return;
  for (const r of root.querySelectorAll(".pg-tr[data-u]")) {
    const was = before.get(r.dataset.u);
    const dy = was == null ? 0 : was - r.getBoundingClientRect().top;
    if (Math.abs(dy) < 1) continue;
    r.style.transition = "none"; r.style.transform = `translateY(${dy}px)`;
    void r.offsetWidth;
    r.style.transition = ""; r.style.transform = "";
  }
}
function render() {
  const f = focused();
  ui.fit = { board: boardFit(), lanes: lanesFit() };
  const pick = pickerOpen() ? pickerHtml() : ui.cmds === null && !ui.error ? ghostPickHtml() : "";
  root.innerHTML = askHtml() + cmpHtml() + pick + answersHtml();
  ui.opened = false; ui.swap = false;
  for (const p of ui.panes) { p.fresh = false; p.reqNew = false; }
  refocus(f);
  grow();
  fadeMore();
}
// the models plate drawn again: its rows travel to their new places on the rail and their bars to the new scales
function paintCmp() {
  const el = document.getElementById("cmp");
  if (!el) { render(); return; }
  const f = focused();
  const before = new Map([...el.querySelectorAll(".pg-tr[data-u]")].map((r) => [r.dataset.u, r.getBoundingClientRect().top]));
  el.outerHTML = cmpHtml();
  refocus(f);
  travel(before);
  grow();
}
// the next send's ghost runs as many lines as this answer read
function measure(p) {
  const body = root.querySelector(`.pgp[data-u="${p.uid}"] .pg-ans`);
  if (body) p.lines = Math.round(body.getBoundingClientRect().height / (1.62 * 1.05 * remPx()));
}
function paintPane(p) {
  const el = root.querySelector(`.pgp[data-u="${p.uid}"]`);
  if (!el) { render(); return; }
  const f = focused();
  el.outerHTML = paneHtml(p, ui.panes.indexOf(p));
  p.reqNew = false;
  refocus(f);
}
// a tab or a fold inside a request: only that request is drawn again
function paintReq(p) {
  const was = root.querySelector(`#rq-${p.uid}`);
  if (!was) { paintPane(p); return; }
  const f = focused();
  was.outerHTML = reqHtml(p);
  refocus(f);
}
function paintAnswers() {
  const el = root.querySelector(".pg-answers");
  if (!el) { render(); return; }
  const f = focused();
  el.outerHTML = answersHtml();
  refocus(f);
}
function toggle(key, id) {
  const on = paneOf(key, id);
  ui.add ??= true; // the board he is choosing in stays open until he closes it or sends
  if (on) ui.panes.splice(ui.panes.indexOf(on), 1); // a model still answering comes off too; its answer is dropped
  else {
    const c = cmdOf(key), m = c?.models.find((x) => x.id === id);
    if (!m || ui.panes.length >= MAX_PANES) return;
    const p = pane(c, m);
    p.fresh = true;
    ui.panes.push(p);
  }
  keepModels(); stopTicker(); render(); paintSend();
}
// Remove armed or disarmed in place, focus staying on the pane's acts
function arm(p, on) {
  p.arm = on;
  const el = root.querySelector(`.pgp[data-u="${p.uid}"] .pg-acts`);
  if (!el) { render(); return; }
  el.outerHTML = actsHtml(p);
  root.querySelector(`.pgp[data-u="${p.uid}"] [data-act="${on ? "drop" : "close"}"]`)?.focus({ preventScroll: true });
}
// the pane goes, and focus moves to the pane that takes its place, else the one before it, else the add row
function removeAt(p) {
  const i = ui.panes.indexOf(p);
  if (i < 0) return;
  ui.panes.splice(i, 1); keepModels(); stopTicker(); render();
  const next = ui.panes[Math.min(i, ui.panes.length - 1)];
  ((next && root.querySelector(`.pgp[data-u="${next.uid}"] .pg-x`)) || root.querySelector(".pg-add"))?.focus({ preventScroll: true });
}
// Read again turns where it stands; a read that fails again shakes it once
async function reread() {
  if (ui.reading) return;
  ui.reading = true; render();
  await read(); restore(); render();
  if (ui.error) root.querySelector('[data-act="reread"]')?.classList.add("shook");
}

root.addEventListener("input", (e) => {
  if (e.target.id === "find") { ui.q = e.target.value; paintBoard(); return; }
  if (e.target.id !== "prompt") return;
  const was = ui.panes.map(stale).join();
  ui.prompt = e.target.value; keep();
  ui.editing = true; fitField(e.target);
  if (ui.panes.map(stale).join() !== was) paintStale();
  paintSend();
});
root.addEventListener("keydown", (e) => {
  const t = e.target;
  if (t.id === "prompt" && e.key === "Enter" && (e.ctrlKey || e.metaKey)) { e.preventDefault(); send(); return; }
  if (t.id === "prompt" && e.key === "Escape" && ui.prompt.trim()) { e.preventDefault(); closeField(); root.querySelector("[data-act=write]")?.focus(); return; }
  if (t.dataset?.act === "write" && (e.key === "Enter" || e.key === " ")) { e.preventDefault(); openField(t.offsetHeight); return; }
  if (e.key === "Escape" && t.closest?.(".pg-acts.armed")) { e.preventDefault(); const p = byUid(t.closest(".pgp").dataset.u); if (p) arm(p, false); return; }
  // Enter in the find field takes the first model it found
  if (t.id === "find" && e.key === "Enter") { e.preventDefault(); root.querySelector('.pg-board .pg-mrow[aria-pressed="false"]:not(:disabled)')?.click(); return; }
  if (t.id === "find" && e.key === "Escape" && ui.q) { e.preventDefault(); ui.q = ""; t.value = ""; paintBoard(); }
});
// leaving the field renders the plan, unless he is pressing Send, which sends and renders it itself
root.addEventListener("focusout", (e) => {
  if (e.target.id !== "prompt" || e.relatedTarget?.closest?.(".pg-go")) return;
  setTimeout(() => { if (document.activeElement?.id !== "prompt") closeField(); }, 0);
});
document.addEventListener("keydown", (e) => {
  if (e.key !== "Escape" || e.defaultPrevented || !pickerOpen() || e.target.closest?.(".cmp")) return;
  ui.add = false; render(); root.querySelector(".pg-add")?.focus();
});
const flip = (set, k) => { if (set.has(k)) set.delete(k); else set.add(k); };
document.addEventListener("click", (e) => {
  if (e.target.closest("a")) return;
  // the prompt's surface below its words is the field too
  if (e.target.classList?.contains("pg-md") && writing()) { document.getElementById("prompt")?.focus(); return; }
  const t = e.target.closest("[data-act]");
  if (!t || (t.dataset.act !== "theme" && !root.contains(t))) return;
  const host = t.closest(".pgp"), p = host ? byUid(host.dataset.u) : null;
  switch (t.dataset.act) {
    case "theme": toggleTheme(); return;
    case "write": openField(t.offsetHeight); return;
    case "send": send(); return;
    case "add": { // Add model always leads to adding one: it opens the board, or goes to its find field when it is open
      if (!pickerOpen()) { ui.opened = true; ui.add = true; render(); }
      ui.add = true;
      const f = document.getElementById("find"); f?.focus({ preventScroll: true }); f?.closest(".pg-pick")?.scrollIntoView({ block: "nearest", behavior: still() ? "auto" : "smooth" });
      return;
    }
    case "shut": ui.add = false; render(); root.querySelector(".pg-add")?.focus({ preventScroll: true }); return;
    case "toggle": toggle(t.dataset.key, t.dataset.model); return;
    // a row's name: its answer, scrolled to and pulsed once
    case "goto": {
      const el = document.getElementById(`ans-${t.dataset.u}`);
      if (!el) return;
      el.scrollIntoView({ block: "nearest", behavior: still() ? "auto" : "smooth" });
      el.classList.remove("landed"); void el.offsetWidth; el.classList.add("landed");
      return;
    }
    case "close": if (p) { if (holds(p)) arm(p, true); else removeAt(p); } return;
    case "drop": if (p) removeAt(p); return;
    case "keep": if (p) arm(p, false); return;
    case "req": {
      if (!p?.trace) return;
      p.req = !p.req; p.reqNew = p.req; paintPane(p);
      if (p.req) root.querySelector(`#rq-${p.uid}`)?.scrollIntoView({ block: "nearest", behavior: still() ? "auto" : "smooth" });
      return;
    }
    case "said": if (p) { p.said = !p.said; paintPane(p); } return;
    case "tab": case "node": case "frames": case "document": {
      if (!p?.RV?.click(t)) return;
      p.rvSwap = t.dataset.act === "tab"; // a new tab fades its body in; a fold opening in place moves on its own
      paintReq(p); p.rvSwap = false;
      return;
    }
    case "retry": if (p && ui.prompt.trim() && p.st !== "sending") run([p]); return;
    case "reread": reread(); return;
    case "more": case "vendor": {
      const k = t.dataset.k;
      flip(ui.open, k);
      ui.unfold = t.dataset.act === "vendor" && ui.open.has(k) ? k : null;
      paintBoard(); root.querySelector(`.pg-board [data-k="${CSS.escape(k)}"]`)?.focus({ preventScroll: true });
      return;
    }
    default: return;
  }
});
root.addEventListener("scroll", (e) => { if (e.target.classList?.contains("pg-md-out")) fadeMore(); }, { capture: true, passive: true });
// a new width: the board takes its new count of columns and the answers theirs
new ResizeObserver(() => {
  fadeMore();
  if (!ui.fit) return;
  const b = boardFit(), l = lanesFit();
  if (b !== ui.fit.board) { ui.fit.board = b; if (pickerOpen()) paintBoard(); }
  if (l !== ui.fit.lanes) { ui.fit.lanes = l; if (ui.panes.length) paintAnswers(); }
}).observe(root);

(async () => {
  render();
  await read();
  restore();
  render();
})();

});
