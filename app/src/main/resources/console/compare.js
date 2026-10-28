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
// THE COMPARISON IS ONE TABLE (Oct 27). Every chosen model is a row of the models plate beside
// the prompt, docked on a numbered rail: its time on one log axis that grows while the send is out, then its cost, its
// tokens each way, its context window and its prices as bars on scales the rows share, the best of each column starred.
// The rail numbers the order the answers landed in (a failure reads first, the row that needs a look), the rows move to
// that order as they land, and each answer's pane carries the same number and its own figures on the same scales. The
// round's own figure leads the plate: how many answered of how many were asked, with the split of the round as one bar
// and the time to the last answer beside it. The table always shows its four places, taken or open. The board of models
// opens across the whole width under the first row, its providers balanced into equal columns, each model a row with its
// window and its two prices drawn before it is chosen; an opened list takes as many columns as its length needs. The
// answers stand side by side under it, and a pane's request opens at the pane's foot, so the answers stay on one line.
CONSOLE.page("compare.html", (scope) => {
  "use strict";
  const { document, window, API, setInterval, clearInterval, setTimeout, requestAnimationFrame, ResizeObserver } = scope;

const MAX_PANES = 4;
const KEPT = "splice.compare.prompt";
const KEPT_MODELS = "splice.compare.models";
// a command's list shows up to WHOLE models; a longer one shows its first FIRST and the rest behind +N
const LIST = { whole: 10, first: 6 };
// a list grouped by vendor (OpenRouter) shows its largest vendors, each opening its models in place, kept the same way
const VENDORS = 6, VENDOR_LIST = { whole: 8, first: 6 };
// an opened list longer than OPEN_ROWS takes one more board column for every PER_COLUMN rows, so no list runs down one column
const OPEN_ROWS = 12, PER_COLUMN = 8;
// a board column and an answer's narrowest lane, in rem: the board's column is Accounts' (five at 3822, four at his 2548,
// two at 1440), and the answers stand four across at 3822, two at 1440
const COL_REM = 40, LANE_REM = 40;
const TICK = '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="3.2" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><path d="M5 12.5l4.5 4.5L19 7"/></svg>';
const CLOCK = G('<circle cx="12" cy="12" r="8.5"/><path d="M12 7.5V12l3 2"/>', 'aria-hidden="true"');
const PENCIL = G('<path d="M4.5 19.5l.9-4 10.6-10.6a1.8 1.8 0 0 1 2.6 0l.5.5a1.8 1.8 0 0 1 0 2.6L8.5 18.6z"/><path d="M13.8 6.7l3.5 3.5"/>', 'aria-hidden="true"');
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
  q: "", open: new Set(), unfold: null, finished: 0, back: 0, uid: 0, opened: false, swap: false, done: false, ax: null, t: null, fit: null,
  marks: "", heroAt: null, sort: "window",
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
// A login is named by the console's one name rule (kit DRAW.name): the name its owner gave, else its provider and plan,
// never its address or the bare word "Account"; two unnamed ones that would read alike are told apart by an ordinal.
// acctOf only gathers what the rule reads ({ kind: "login", given, plan }); read() names them all together.
function acctOf(a, known, next) {
  if (a?.env_var) return { kind: "key", var: a.env_var, tail: tailOf(a.api_key_masked) };
  if (known) return { kind: "login", given: known.label_default && known.can_rename ? "" : known.display_name ?? known.label ?? "", plan: cap(known.plan) };
  return next ? { kind: "login", given: next, plan: "" } : { kind: "none" };
}
function nameLogins(cmds) {
  const logins = cmds.filter((c) => c.acct.kind === "login");
  const named = DRAW.names("login", logins.map((c) => ({ name: c.acct.given, provider: c.family, plan: c.acct.plan })));
  logins.forEach((c, i) => { c.acct = { kind: named[i].own ? "named" : "unnamed", name: named[i].text, plan: named[i].own ? c.acct.plan : "", head: c.acct.head }; });
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
  nameLogins(ui.cmds);
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
  rv: { tab: "attempts", nodes: new Set(), q: "" }, RV: null, rvSwap: false, painted: {}, strip: {}, fresh: false, arm: false, pop: false,
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
// What went wrong, in the console's word for its kind: the provider's status or error type, else the sentence splice answered.
function failWord(status, body, said) {
  const type = body?.error?.type ?? body?.error?.code ?? body?.type ?? "";
  if (status === 429 || /rate_limit|usage_limit/.test(type)) return OUTCOME_WORD["error:rate-limited"];
  if (status === 529 || /overloaded/.test(type)) return OUTCOME_WORD["failure:overloaded_error"];
  if (status === 401 || status === 403) return OUTCOME_WORD["failure:authentication_error"];
  if (status >= 500 && status < 600) return OUTCOME_WORD["error:upstream-failed"];
  if (status === 404 || /not_found/.test(type)) return OUTCOME_WORD["failure:not_found_error"];
  if (status >= 400 && status < 500) return OUTCOME_WORD["failure:invalid_request_error"];
  // Compare's own word: its send runs under a connect and a whole-request timeout, and a model still answering is cut
  // with no silence at all, so the kit's "Given up after a silence" is not its fact
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
// What a budget refusal measured, spent and cap, read off the refusal's own sentence ("... $5.12 ... against its $5.00 daily
// budget ..."): the daemon's reply carries them nowhere else. The sentence itself stays off the page; only its two amounts
// are drawn. A refusal worded another way draws no meter (the word and its door stand alone) rather than a guess.
const AMOUNT = "\\$([\\d,]+(?:\\.\\d+)?)";
const budgetOf = (said) => {
  const m = new RegExp(`${AMOUNT}[^$]*?against its ${AMOUNT}`).exec(said ?? "");
  const n = (s) => Number(s.replace(/,/g, ""));
  return m && n(m[2]) > 0 ? { spent: n(m[1]), cap: n(m[2]) } : null;
};
// a reply with no price, in Requests' words for why (requests.js REASON), said once beside the table's legend
const NO_COST = { plan: "On your plan", uncounted: "Uncounted" };
// a cost in the console's one money form (kit dollars) without its "≈" on every cell: the column's header says
// "estimated" once. The bars on the cost column's scale tell two close costs apart.
const money = DRAW.usd;
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
    Object.assign(p, { st: "failed", fail: { word: OUTCOME_WORD[reply.outcome] ?? OUTCOME_WORD["error:budget-blocked"], door: reply.head || p.key, splice: true, budget: budgetOf(reply.error) } });
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
  if (!askedOnes().length) ui.heroAt = performance.now(); // the round's figure is about to open for the first time
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
  paintStrips(p); // the others' figures move to the round's new scales
  p.pop = false; ui.done = false;
  paintSend();
}

// ---------- the comparison: every row on scales the rows share ----------
// One log axis for every row's time. It starts at the round figure under the fastest answer that landed (100 ms until
// one has) and ends at 5 min (10 min once a send runs past that), so 0.9 s and 40 s, or 31 ms and 33 ms, read as lengths
// on one line while the sends race. A time under the axis's start keeps a sliver, so an answer is never drawn as no time.
const axisHi = () => (ui.panes.some((p) => (p.st === "sending" ? Date.now() - p.t0 : p.ms ?? 0) > 3e5) ? 6e5 : 3e5);
function axisLo() {
  const landed = (st) => ui.panes.filter((p) => p.st === st && p.ms > 0).map((p) => p.ms);
  const first = Math.min(...(landed("done").length ? landed("done") : landed("failed")));
  return Number.isFinite(first) ? Math.min(1000, Math.max(1, 10 ** Math.floor(Math.log10(first)))) : 100;
}
const axisNow = () => ({ lo: axisLo(), hi: axisHi() });
const sameAxis = (a, b) => Boolean(a) && a.lo === b.lo && a.hi === b.hi;
const axisAt = (ms, { lo, hi }) => (ms > 0 ? Math.max(1.2, Math.min(100, (Math.log(Math.max(ms, lo) / lo) / Math.log(hi / lo)) * 100)) : 0);
// the axis's marks, written compactly (1m, 5m: the last decades sit close, and "1 min" beside "5 min" would touch in a
// narrow column); never more than five, so no two touch
const LO_WORD = { 1: "1ms", 10: "10ms", 100: "100ms", 1000: "1s" };
function ticksOf({ lo, hi }) {
  const all = [[lo, LO_WORD[lo]], ...[[10, "10ms"], [100, "100ms"], [1000, "1s"], [1e4, "10s"], [6e4, "1m"]].filter(([ms]) => ms > lo), [hi, hi > 3e5 ? "10m" : "5m"]];
  while (all.length > 5) all.splice(1, 1);
  return all;
}
const top = (xs) => Math.max(0, ...xs.filter((x) => typeof x === "number" && Number.isFinite(x)));
// a share of the largest, with a sliver kept for anything above nothing
const share = (v, max) => (v > 0 && max > 0 ? Math.max(1.5, (v / max) * 100) : 0);
// the round's figure: the time until its last answer, still running while any is out
const roundMs = () => Math.max(0, ...askedOnes().map((p) => (p.st === "sending" ? Date.now() - p.t0 : p.ms ?? 0)));
// what the send brought, then what the model is; the best of a column is the least, or the most for a window. A column's
// unit or qualifier is said once, under its name (unit).
const COLS = [
  { f: "cost", word: "Cost", unit: "estimated", best: "low", sent: true, text: money },
  { f: "tin", word: "Tokens in", sent: true, text: DRAW.n },
  { f: "tout", word: "Tokens out", sent: true, text: DRAW.n },
  { f: "win", word: "Context", best: "high", text: DRAW.big },
  { f: "pin", word: "Price in", unit: "per M tokens", best: "low", text: rate },
  { f: "pout", word: "Price out", unit: "per M tokens", best: "low", text: rate },
];
const RAIL = { failed: 0, done: 1, sending: 2, idle: 3 };
// the rail's order: the sends that failed first (the row that needs a look reads first), then the answers in the order
// they came back, then the ones still out, then the ones not asked yet, each of those in the order he chose them
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
// THE SCALES, DRAWN ONCE FOR THE WHOLE ROUND: the table's rows and each answer's own figures read the same scales
function comparison() {
  const rows = railOrder(), ax = axisNow(), figs = new Map(rows.map((p) => [p, figuresOf(p)])), all = [...figs.values()];
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
  ui.ax = ax;
  return (ui.t = { rows, figs, sc, best, ax, room, dash: new Set(), paid: false, plan: false });
}
// a bar drawn at the width it was last painted, then grown to its new one, so a change moves instead of jumping
// ([store] is where a place keeps its last width: the table's row, or the answer's own strip)
function barI(p, f, w, tag = "b", store = "painted") {
  const html = `<${tag} style="width:${(p[store][f] ?? 0).toFixed(2)}%" data-w="${w.toFixed(2)}"></${tag}>`;
  p[store][f] = w;
  return html;
}
const dashHtml = `<i class="nr">—</i>`;
// What one figure of one row is: an empty track before the send, stripes while it is out, a value on its column's
// scale, or a dash. The table's cell and the answer's own strip both draw it, so a figure reads alike in both.
function figOf(p, col, x, t) {
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
  return { kind, text, best, w };
}
function cellHtml(p, col, x, t) {
  const { f } = col, g = figOf(p, col, x, t);
  return `<span class="pg-c barcell is-${g.kind}${g.best ? " best" : ""}" role="cell" data-f="${f}" style="--v-w:${t.room[f]}ch">`
    + `<span class="vbar" aria-hidden="true">${barI(p, f, g.w)}</span><b class="v">${g.best ? STAR : ""}${g.text}</b></span>`;
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
  const time = `<span class="pg-c pg-time" role="cell"><span class="pg-trk" aria-hidden="true">${barI(p, "ms", x.ms == null ? 0 : axisAt(x.ms, t.ax), "i")}</span></span>`
    + `<span class="pg-c pg-tv${fast ? " best" : ""}" role="cell" data-f="msv">${x.ms == null ? "" : `${fast ? STAR : ""}${DRAW.ms(x.ms)}`}</span>`;
  return `<div class="pg-tr is-${p.st}${edge}${stale(p) ? " stale" : ""}${p.fresh ? " landed" : ""}" role="row" data-u="${p.uid}" style="grid-row:${at};--c:${colorOf(c?.family)}">`
    + `<span class="pg-c pg-railc" role="cell">${sockHtml(p)}</span>`
    + `<span class="pg-c pg-rwho" role="cell"><button class="pg-rname" type="button" data-act="goto" data-u="${p.uid}"><i class="blot" aria-hidden="true"></i><span>${esc(p.label)}</span></button>${c ? DRAW.chip({ text: c.command }) : ""}</span>`
    + time + COLS.map((col) => cellHtml(p, col, x, t)).join("") + `</div>`;
}
// A column's name with its unit or qualifier under it, said once; the other columns carry no unit.
const colHead = (word, unit = "") => `${word}${unit ? `<small>${unit}</small>` : ""}`;
// the column names, said once; over the time the axis's own marks, which the rows' tracks are read against
// (the axis's ends and the rail's are named pg-a and pg-z: Sessions' stylesheet, loaded on this page, owns .last)
function headHtml(ax) {
  const ticks = ticksOf(ax).map(([ms, w], i, a) => `<span class="${i === 0 ? "pg-a" : i === a.length - 1 ? "pg-z" : ""}" style="left:${axisAt(ms, ax).toFixed(2)}%">${w}</span>`).join("");
  return `<div class="pg-tr pg-th" role="row" style="grid-row:1"><span class="pg-c" role="columnheader"></span><span class="pg-c pg-h" role="columnheader">Model</span>`
    + `<span class="pg-c pg-axis" aria-hidden="true">${ticks}</span><span class="pg-c pg-h pg-timeh" role="columnheader">Time</span>`
    + COLS.map((c) => `<span class="pg-c pg-h" role="columnheader">${colHead(c.word, c.unit)}</span>`).join("") + `</div>`;
}
// the dashed row where the next model lands, its socket the next one on the rail; its count of models sits beside its label
const addHtml = () => `<button class="add pg-add" type="button" data-act="add" aria-expanded="${pickerOpen()}" aria-controls="pick">`
  + `<span class="plus" aria-hidden="true">+</span><span class="pg-addw">Add model</span><span class="pg-offer"><b>${DRAW.n(offered())}</b> models</span>${DRAW.chev}</button>`;
// four chosen: no place is left on the rail, so the row under the last one opens the same board to swap a model, a
// solid row where the dashed one stood (pressing a chosen model there takes it off, and its place comes back)
const SWAP = '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.2" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><path d="M7 7h11l-3-3M17 17H6l3 3"/></svg>';
const swapHtml = () => `<button class="add pg-add pg-swap" type="button" data-act="add" aria-expanded="${pickerOpen()}" aria-controls="pick">`
  + `<span class="plus" aria-hidden="true">${SWAP}</span><span class="pg-addw">Change models</span><span class="pg-offer"><b>${DRAW.n(offered())}</b> models</span>${DRAW.chev}</button>`;
// an open place on the rail: its dashed socket and the empty tracks a model's figures will fill, so the table's four
// places are always drawn, taken or open, and the plate keeps one height as models are chosen
function slotHtml(at, edge) {
  return `<div class="pg-tr pg-slot${edge}" role="row" style="grid-row:${at}"><span class="pg-c pg-railc"><span class="pg-sock tlamp open" aria-hidden="true"></span></span><span class="pg-c"></span>`
    + `<span class="pg-c pg-time"><span class="pg-trk" aria-hidden="true"></span></span><span class="pg-c pg-tv"></span>`
    + COLS.map(() => `<span class="pg-c barcell"><span class="vbar" aria-hidden="true"></span></span>`).join("") + `</div>`;
}
// the places under the chosen rows: the add row at the next socket, then the rest as open places; the rail runs from the
// first place to the last, so with none chosen the add row begins it. [ghost]: the add row as a ghost, while the reads run.
function placesHtml(n, { ghost = false } = {}) {
  const out = [];
  for (let k = n; k < MAX_PANES; k++) {
    const at = k + 2, edge = `${k === 0 ? " pg-a" : ""}${k === MAX_PANES - 1 ? " pg-z" : ""}`;
    out.push(k === n
      ? `<div class="pg-tr pg-addrow${edge}" style="grid-row:${at}"><span class="pg-c pg-railc"><span class="pg-sock tlamp open" aria-hidden="true"></span></span>${ghost ? `<div class="add pg-add ghosted" aria-hidden="true">${GHOST.bar(11)}</div>` : addHtml()}</div>`
      : slotHtml(at, edge));
  }
  return out.join("");
}
// four chosen: the Change models row under the last one
const swapRow = (n, { ghost = false } = {}) => `<div class="pg-tr pg-addrow pg-swaprow" style="grid-row:${n + 2}"><span class="pg-c"></span>${ghost ? `<div class="add pg-add pg-swap ghosted" aria-hidden="true">${GHOST.bar(11)}</div>` : swapHtml()}</div>`;
function tableHtml(t) {
  const n = t.rows.length;
  const rows = t.rows.map((p, k) => rowHtml(p, k + 2, t, `${k === 0 ? " pg-a" : ""}${n === MAX_PANES && k === n - 1 ? " pg-z" : ""}`)).join("");
  // the axis's lines run behind the chosen rows and behind the open places, and stop at the add row, which stands over them
  const lines = ticksOf(t.ax).slice(1, -1).map(([ms]) => `<i style="left:${axisAt(ms, t.ax).toFixed(2)}%"></i>`).join("");
  const grid = (at, span) => (span > 0 ? `<span class="pg-grid" aria-hidden="true" style="grid-row:${at} / span ${span}">${lines}</span>` : "");
  const open = n < MAX_PANES ? MAX_PANES - n - 1 : 0;
  return `<div class="pg-table" role="table" aria-label="The models compared">${headHtml(t.ax)}${rows}${grid(2, n)}${grid(n + 3, open)}${n < MAX_PANES ? placesHtml(n) : swapRow(n)}</div>`;
}
// the round's figure leads the plate: how many answered of how many asked, as the hero with its denominator, the round's
// split as one bar (answered, failed, still out), and beside them the time to the last answer and how many failed
function heroHtml() {
  const a = askedOnes();
  if (!a.length) return "";
  const done = a.filter((p) => p.st === "done").length, failed = a.filter((p) => p.st === "failed").length, out = a.filter((p) => p.st === "sending").length;
  const level = done === 0 && failed > 0 ? "bad" : "ok";
  const bar = DRAW.stack([{ n: done, word: "Answered", cls: "ok" }, { n: failed, word: "Failed", cls: "f0" }, { n: out, word: "Still out", cls: "nt" }], a.length,
    { label: `${done} of ${a.length} answered, ${failed} failed, ${out} still out` });
  const clock = `<span class="pd-stat">${CLOCK}<span><b data-clock>${DRAW.ms(roundMs())}</b><small>to the last answer</small></span></span>`;
  const bad = failed ? `<span class="pg-failt">${DRAW.statTile(ICON.bang, DRAW.n(failed), "failed")}</span>` : "";
  // the round opens from nothing the first time it is drawn, so what stands under it moves down instead of jumping; a redraw
  // while it is still opening carries on from where the opening has got to
  const opening = ui.heroAt != null && performance.now() - ui.heroAt < 520;
  const phase = opening ? ` style="animation-delay:-${Math.round(performance.now() - ui.heroAt)}ms"` : "";
  return `<div class="pg-roundw${opening ? " unfold" : ""}"${phase}><div class="pg-roundin"><div class="pg-round${a.every(stale) ? " stale" : ""}${ui.done ? " landed" : ""}"><div class="pg-hero">${DRAW.hero({ figure: DRAW.n(done), level, of: `of ${DRAW.n(a.length)} answered`, key: "round" })}</div>`
    + `${bar}<div class="pd-stats pg-rtiles">${clock}${bad}</div></div></div></div>`;
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
  if (t.dash.size) parts.push(`<span class="pg-legi pg-note">${dashHtml}${[...t.dash].map(esc).join(" · ")}</span>`);
  return parts.join("");
}
function cmpHtml() {
  let body = "", t = null;
  if (ui.cmds === null) body = ui.error ? unreadTableHtml() : ghostTableHtml();
  else if (!ui.cmds.length && !ui.error) body = noneHtml();
  else {
    t = comparison();
    const table = tableHtml(t); // drawn first: it gathers what the legend says
    const lead = heroHtml();
    body = `${ui.error ? unreadHtml() : ""}${lead}${table}`;
  }
  return `<div class="plate pg-cmp" id="cmp">${DRAW.section("Models", body, { aside: `<span class="pg-aside">${asideHtml(t)}</span>`, cls: "pg-cmps" })}</div>`;
}
// the stopwatch: each row still out grows along the shared axis and the round's figure runs, written in place
function tickClock() {
  if (!sameAxis(ui.ax, axisNow())) { paintCmp(); paintStrips(); return; } // the axis moved: every row and strip moves to the new one
  const ax = ui.ax;
  for (const p of ui.panes) {
    if (p.st !== "sending") continue;
    const ms = Date.now() - p.t0, w = axisAt(ms, ax);
    const row = root.querySelector(`.pg-tr[data-u="${p.uid}"]`), i = row?.querySelector(".pg-trk > i");
    if (i) { i.style.width = `${w.toFixed(2)}%`; i.dataset.w = w.toFixed(2); }
    p.painted.ms = w;
    const v = row?.querySelector('[data-f="msv"]');
    if (v) v.textContent = DRAW.ms(ms);
    const sc = root.querySelector(`.pgp[data-u="${p.uid}"] .pg-sc[data-f="ms"]`);
    if (sc) {
      const bar = sc.querySelector(".vbar > b"), fig = sc.querySelector(".v");
      if (bar) { bar.style.width = `${w.toFixed(2)}%`; bar.dataset.w = w.toFixed(2); }
      if (fig) fig.textContent = DRAW.ms(ms);
      p.strip.ms = w;
    }
  }
  const fig = root.querySelector("[data-clock]");
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
// The prompt is one surface: his plan reads as it renders, the way the answers do; a click or Enter on it
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
// the pencil in a read plan's corner draws what a click does: it turns the plan into the field
const mdHtml = () => `<div class="pg-md${writing() ? "" : " pg-read"}${ui.swap ? " swap" : ""}">${fieldHtml()}${writing() ? "" : `<span class="pg-edit" aria-hidden="true">${PENCIL}</span>`}</div>`;
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
  root.querySelector(".pg-round")?.classList.toggle("stale", a.length > 0 && a.every(stale));
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
  const word = b.querySelector("span"), now = busy() ? "Sending" : "Send";
  if (word.textContent === now) return;
  word.textContent = now;
  // the word is replaced by the next, which rises into its place
  if (!still()) { word.classList.remove("rise"); void word.offsetWidth; word.classList.add("rise"); }
}

// ---------- drawing: the answers ----------
// the account as the board's card heads it: its name and plan, a key by its variable and masked tail. A login nobody named
// shows the name the console's rule made for it, and its "Name it" door stays on the board's cards ([door]), where
// Accounts' act belongs, never on every pane of a comparison.
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
// under the model's name: where it came from (the vendor, for a command that groups by vendor) and whose it is; the
// provider is the pane's colour edge and the command is its chip, so neither is said again in words
function metaHtml(p, c) {
  if (!c) return "";
  const parts = [];
  if (p.vendor) parts.push(`<span>${esc(p.vendor)}</span>`);
  const a = acctHtml(c.acct);
  if (a) parts.push(`<span class="who">${a}</span>`);
  return parts.join(SEP);
}
// THE ANSWER'S OWN FIGURES, in its head: its time, cost and tokens each way as bars on the same scales the table reads, so
// an answer is never read apart from its numbers. Empty tracks before its send, stripes while it is out, a dash where
// the daemon sent no figure. It draws once any answer has been asked for.
function stripHtml(p) {
  const t = ui.t;
  if (!t || !askedOnes().length) return "";
  const x = t.figs.get(p) ?? figuresOf(p), c = cmdOf(p.key);
  const fast = t.best.ms != null && p.st === "done" && DRAW.ms(p.ms) === t.best.ms;
  const cell = (f, word, kind, best, text, w) => `<span class="pg-sc is-${kind}${best ? " best" : ""}" data-f="${f}"><span class="pg-scl"><em>${word}</em><b class="v">${best ? STAR : ""}${text}</b></span>`
    + `<span class="vbar" aria-hidden="true">${barI(p, f, w, "b", "strip")}</span></span>`;
  const time = cell("ms", "Time", p.st === "sending" ? "run" : p.st === "idle" ? "idle" : "val", fast, x.ms == null ? "" : DRAW.ms(x.ms), x.ms == null ? 0 : axisAt(x.ms, t.ax));
  const sent = COLS.filter((col) => col.sent).map((col) => { const g = figOf(p, col, x, t); return cell(col.f, col.word, g.kind, g.best, g.text, g.w); });
  return `<div class="pg-strip is-${p.st}" style="--c:${colorOf(c?.family)}">${time}${sent.join("")}</div>`;
}
// every answer's strip drawn again on the round's new scales, each bar moving from where it stood ([skip]: the pane that
// was just drawn whole)
function paintStrips(skip = null) {
  if (!askedOnes().length) return;
  for (const p of ui.panes) {
    if (p === skip) continue;
    const el = root.querySelector(`.pgp[data-u="${p.uid}"] .pg-strip`);
    if (el) el.outerHTML = stripHtml(p);
  }
  grow();
}
// the request this pane sent opens at the pane's foot, under its answer, so the answers across a row stay on one line;
// Remove at the pane's right edge. A pane that holds an answer arms first: its acts turn into Remove and Keep in place,
// and only the second press takes it away. One whose answer is still out goes at once, and its answer is dropped when it lands.
const holds = (p) => p.st === "done" || p.st === "failed";
function actsHtml(p) {
  if (p.arm) return `<div class="pg-acts armed"><button class="act small" type="button" data-act="drop">Remove</button>`
    + `<button class="act quiet small" type="button" data-act="keep">Keep</button></div>`;
  const rq = p.trace && p.st !== "sending" ? DRAW.disc({ open: p.req, controls: `rq-${p.uid}`, act: "req", cls: "pg-reqd", inner: "<span>Request</span>" }) : "";
  return `<div class="pg-acts">${rq}<button class="icon pg-x" type="button" data-act="close" aria-label="Remove ${esc(p.label)}">${ICON.close}</button></div>`;
}
// a failure's own acts: the one that fixes it, beside its word. What a retry cannot fix also offers another model, in place.
const swapAct = `<button class="act quiet small" type="button" data-act="swap">Another model</button>`;
function fixActs(p) {
  const f = p.fail;
  if (f.spent) return `<a class="act small" href="accounts.html?head=${enc(p.key)}">Accounts${DRAW.go}</a>${swapAct}`;
  if (f.door) return `<a class="act small" href="accounts.html?budget=${enc(f.door)}">Set budget${DRAW.go}</a>${swapAct}`;
  // a refused or missing sign-in opens Accounts on what this command needs fixed (its ?head= door)
  if (SIGNED.has(f.word)) return `<a class="act small" href="accounts.html?head=${enc(p.key)}">Accounts${DRAW.go}</a>${swapAct}`;
  // a runtime that is not up starts from its command on Models (models.html?cmd= takes the head's key); once it runs,
  // Try again asks this pane alone
  if (f.word === DOWN) return `<a class="act small" href="models.html?cmd=${enc(p.key)}">Models${DRAW.go}</a><button class="act quiet small" type="button" data-act="retry">Try again</button>`;
  if (f.reread) return DRAW.act({ word: "Read again", act: "reread", cls: "small", busy: ui.reading });
  if (f.retry) return `<button class="act small" type="button" data-act="retry">Try again</button>`;
  return "";
}
// a window's row as Accounts draws one: its word, the meter, what is used and, where it refills, the reset ring
const winRow = (word, used, figure, tail = "") => `<div class="pg-wrow"><span class="pg-ww">${esc(word)}</span><div class="bar${used >= 100 ? " full" : ""}"><b data-v="${used.toFixed(1)}" style="width:${used.toFixed(1)}%"></b></div>`
  + `<span class="pct limit">${figure}</span>${tail}</div>`;
// a failure: its word, then what it is as a shape (a spent window's meter at its limit with the reset ring, a spent
// budget's meter with its two amounts), the act that fixes it, then what the provider, or splice, said, folded to one
// line until he opens it
function fixHtml(p) {
  const f = p.fail, c = cmdOf(p.key);
  const tries = p.tries > 1 ? DRAW.statTile(DRAW.glyph.retry, DRAW.n(p.tries), "tries") : "";
  let head, meter = "";
  if (f.spent) {
    const sp = f.spent, along = sp.secs ? Math.max(0, Math.min(1, 1 - (sp.until - Date.now()) / 1000 / sp.secs)) : null;
    head = DRAW.lock(LIMIT, { pop: true });
    meter = winRow(sp.word || "Window", 100, `100%<span class="used">used</span>`, `<span class="pg-reset">${along != null ? DRAW.ring(along) : ""}<span>Resets ${esc(backWord(sp.until))}</span></span>`);
  } else {
    // "Not answering" is the command's state, in the same neutral ink its board card says it in; every other word is a failure
    head = `<span class="state${f.word === DOWN ? "" : " limit"} pg-word">${esc(f.word)}</span>`;
    if (f.budget) meter = winRow("Today", Math.min(100, (f.budget.spent / f.budget.cap) * 100), `${DRAW.usd(f.budget.spent)}<span class="used">of ${DRAW.usd(f.budget.cap)}</span>`);
  }
  const acts = fixActs(p);
  const text = f.said || (f.code != null ? `Answered ${f.code} with no reason` : "");
  const who = f.splice ? "splice" : titleOf(c?.family, c?.command ?? "");
  const said = text ? `<div class="pg-said${p.said ? " open" : ""}">${DRAW.disc({ open: p.said, act: "said", cls: "pg-saidd", inner: `<span class="who">${esc(who)}</span><q>${esc(text)}</q>` })}</div>` : "";
  return `<div class="pg-fix${p.tries > 1 ? " again" : ""}"><div class="pg-fixrow">${head}${tries}</div>${meter}${acts ? `<div class="pg-fixacts">${acts}</div>` : ""}${said}</div>`;
}
// ---------- the request, in Requests' own open request (reqview.js), at the foot of its own pane ----------
// The pane's head already names the model, its command and its account, and its strip draws the request's time, tokens
// and cost on the shared scales, so reqview is asked for its record alone: no head (head: false), no figures (cells: []),
// and no time rail (rail: false), which would be one bar, the playground's answer carrying no first-byte time.
function reqHtml(p) {
  const c = cmdOf(p.key), ts = new Date(+p.at + p.ms);
  const r = { id: `${p.key}:${p.id}:${p.uid}`, cmd: c?.command ?? p.key, provider: c?.family, model: "", ts, total: p.ms, in: p.tin, out: p.tout, cached: 0, attempts: 1 };
  p.RV ??= REQVIEW.view(() => p.rv);
  return `<div class="pg-req${p.reqNew ? " unfold" : ""}${p.rvSwap ? " swap" : ""}" id="rq-${p.uid}"><div class="pg-reqin"><div class="pg-reqcard"><p class="pg-reqat">Asked<time>${REQVIEW.dayOf(p.at)}${REQVIEW.clockS(p.at)}</time></p>`
    // a finished answer's words stand above this record in the same pane, so the Returned tab keeps what only the record
    // knows (its status, how the stream ended, its tool calls and its frames) and the words are set once
    + `${p.RV.paneHtml(r, { head: false, st: "kept", tr: () => p.trace, cells: [], rail: false, reply: p.st !== "done", names: { head: r.cmd, model: modelOf(p)?.label } })}</div></div></div>`;
}
// the answer's shape while it is out: as many lines as the last answer here ran to, else a short answer's
function waitHtml(p) {
  const n = Math.max(6, Math.min(24, p.lines ?? 9));
  return `<div class="ghost-lines" aria-hidden="true">${Array.from({ length: n }, (_, k) => `<i class="ghost" style="width:${k === n - 1 ? 38 : 72 + ((k * 13) % 26)}%"></i>`).join("")}</div>`;
}
function bodyHtml(p) {
  if (p.st === "sending") return waitHtml(p);
  if (p.st === "failed") return fixHtml(p);
  // as many columns as the reply has text for: about 450 characters to a column, four at most
  if (p.st === "done") return `<div class="msg agent"><div class="body pg-mdx pg-ans" style="--cc:${Math.max(1, Math.min(4, Math.floor(p.text.length / 450)))}">${mdPlan(p.text)}</div></div>`;
  // a pane chosen after the others were asked is an empty slot the size of its neighbours, where its answer will land
  return ui.panes.some((o) => o.st !== "idle") ? `<div class="pg-idle" role="img" aria-label="Not asked yet"></div>` : "";
}
// how many of the answers stand across: as many as fit at LANE_REM, never more than there are; the last of an
// uneven row takes the rest of its row, so no row ends in an empty lane
const lanesOf = () => Math.min(ui.panes.length || 1, ui.fit?.lanes ?? lanesFit());
function spanOf(i) {
  const n = ui.panes.length, lanes = lanesOf();
  return i === n - 1 && n % lanes ? lanes - ((n - 1) % lanes) : 1;
}
function paneHtml(p, i) {
  const c = cmdOf(p.key), span = spanOf(i), meta = metaHtml(p, c);
  // a pane that stands alone in its row (one model, or the last of an uneven row) has a whole row to itself and fills it
  // with columns; the only pane there is stands as wide as one of two on a wide page (.solo), where it would otherwise be a
  // line of text across the whole screen
  const alone = span > 1 || lanesOf() === 1;
  return `<article class="pgp is-${p.st}${alone ? " alone" : ""}${ui.panes.length === 1 ? " solo" : ""}${stale(p) ? " stale" : ""}${p.fresh ? " landed" : ""}" id="ans-${p.uid}" data-u="${p.uid}" style="--c:${colorOf(c?.family)}${span > 1 ? `;grid-column:span ${span}` : ""}" aria-label="${esc(p.label)}">`
    + `<div class="pgc"><header class="pg-head">${sockHtml(p)}<div class="pg-nm"><h3 class="pg-model">${esc(p.label)}</h3>${c ? DRAW.chip({ text: c.command }) : ""}</div>${meta ? `<div class="pg-meta">${meta}</div>` : ""}${actsHtml(p)}${stripHtml(p)}</header>`
    + `<div class="pg-body${p.pop ? " rise" : ""}">${bodyHtml(p)}</div></div>${p.req && p.trace ? reqHtml(p) : ""}</article>`;
}
function answersHtml() {
  if (ui.cmds === null) return ui.error ? "" : ghostAnswersHtml();
  if (!ui.panes.length) return "";
  return `<section class="pg-answers" aria-label="Answers"><div class="sechead pg-ahead"><h2 class="kicker">Answers</h2>${ui.panes.some(stale) ? oldLegend : ""}</div>`
    + `<div class="pg-lanes" style="--lanes:${lanesOf()}">${ui.panes.map((p, i) => paneHtml(p, i)).join("")}</div></section>`;
}


// ---------- drawing: the board, providers balanced into equal columns, each command a card ----------
const norm = (s) => String(s ?? "").toLowerCase();
const terms = () => norm(ui.q).split(/\s+/).filter(Boolean);
const hits = (c, m, t) => { const hay = norm(`${m.label} ${m.id} ${c.command} ${c.title} ${c.acct.name ?? ""} ${c.acct.var ?? ""}`); return t.every((w) => hay.includes(w)); };
// a model: its tick and its name, then its own context window and its two prices as bars on the board's shared scales, so
// it is chosen with its figures in view; four chosen leaves the rest off, the board's head saying why. A figure the
// daemon did not send is a dash on a dashed track.
function mfig(v, max, text) {
  if (v == null) return `<span class="pg-mf"><span class="vbar unread" aria-hidden="true"></span><b class="v">${dashHtml}</b></span>`;
  return `<span class="pg-mf"><span class="vbar" aria-hidden="true"><b style="width:${share(v, max).toFixed(1)}%"></b></span><b class="v">${text(v)}</b></span>`;
}
function mrowHtml(c, m, name, sc) {
  const on = Boolean(paneOf(c.key, m.id)), off = !on && ui.panes.length >= MAX_PANES;
  return `<button class="pg-mrow" type="button" aria-pressed="${on}" data-act="toggle" data-key="${esc(c.key)}" data-model="${esc(m.id)}"${off ? " disabled" : ""}>`
    + `<span class="pg-tick" aria-hidden="true">${TICK}</span><span class="pg-mn">${esc(name(m))}</span>`
    + `${mfig(m.window, sc.win, DRAW.big)}${mfig(m.rates?.input ?? null, sc.price, rate)}${mfig(m.rates?.output ?? null, sc.price, rate)}</button>`;
}
// the column names of a model list, said once over its rows; the price unit is said once for the whole board, in its head
const MHEAD = `<div class="pg-mh" aria-hidden="true"><span></span><span></span><span>Context</span><span>Price in</span><span>Price out</span></div>`;
// an opened list longer than OPEN_ROWS takes one more board column for each PER_COLUMN rows, never more than the board has
const colsFor = (n, rows) => (n > 1 && rows > OPEN_ROWS ? Math.min(n, Math.ceil(rows / PER_COLUMN)) : 1);
// A list's models, the largest window first; a long list shows its first rows and the rest behind "+N", opening in place
// across as many of the board's columns as its length needs ({ need } tells the provider how wide to stand), each with
// its own column names.
function listHtml(c, list, k, sc, { whole, first }, name, n) {
  const long = list.length > whole, open = ui.open.has(k);
  // the order he chose to read them in: the largest window first, or the cheapest price in first (a model with no price last)
  const widest = (a, b) => (b.m.window ?? -1) - (a.m.window ?? -1);
  const order = ui.sort === "price" ? (a, b) => (a.m.rates?.input ?? Infinity) - (b.m.rates?.input ?? Infinity) || widest(a, b) : widest;
  const sorted = list.map((m, i) => ({ m, i })).sort((a, b) => order(a, b) || a.i - b.i).map((x) => x.m);
  const shown = long && !open ? sorted.slice(0, first) : sorted;
  const cols = open && long ? colsFor(n, shown.length) : 1, per = Math.ceil(shown.length / cols);
  const data = ` data-k="${esc(k)}"`;
  const more = !long ? "" : open ? DRAW.more(0, { pill: true, open: true, less: "Fewer", act: "more", data }) : DRAW.more(list.length - shown.length, { pill: true, act: "more", data });
  const chunks = Array.from({ length: cols }, (_, i) => shown.slice(i * per, (i + 1) * per)).filter((x) => x.length);
  const rc = chunks.map((ms) => `<div class="pg-rc">${MHEAD}<div class="pg-rows${open && long && ui.unfold === k ? " unfold" : ""}">${ms.map((m) => mrowHtml(c, m, name, sc)).join("")}</div></div>`).join("");
  return { html: `<div class="pg-rcols" style="--k:${chunks.length}">${rc}</div>${more}`, need: chunks.length };
}
// a command whose models come from three vendors or more (OpenRouter) is grouped by vendor, found or not
const vendorsIn = (models) => new Set(models.map((m) => m.vendor ?? "")).size;
function vendorsHtml(c, list, sc, t, n) {
  const by = new Map();
  for (const m of list) { const v = m.vendor ?? ""; if (!by.has(v)) by.set(v, []); by.get(v).push(m); }
  // each vendor a row that opens its models under itself, the largest first, its count a bar on the largest's scale
  const vendors = [...by].sort((a, b) => b[1].length - a[1].length || a[0].localeCompare(b[0]));
  const all = ui.open.has(c.key) || t.length, shown = all ? vendors : vendors.slice(0, VENDORS);
  const most = Math.max(...vendors.map(([, ms]) => ms.length));
  let need = 1;
  const rows = shown.map(([v, ms]) => {
    const k = `${c.key}|${v}`, open = ui.open.has(k) || t.length > 0;
    const count = `<span class="barcell pg-vc"><span class="vbar" aria-hidden="true"><b style="width:${share(ms.length, most).toFixed(1)}%"></b></span><b class="v">${DRAW.n(ms.length)}</b></span>`;
    const head = DRAW.disc({ open, act: "vendor", data: ` data-k="${esc(k)}"`, cls: "pg-vrow", inner: `<span class="pg-vn">${esc(v || "Other")}</span>${count}` });
    if (!open) return `<div class="pg-vend">${head}</div>`;
    const l = listHtml(c, ms, `${k}|more`, sc, VENDOR_LIST, (m) => m.short, n);
    need = Math.max(need, l.need);
    return `<div class="pg-vend open">${head}<div class="pg-vfold${ui.unfold === k ? " unfold" : ""}"><div class="pg-vin">${l.html}</div></div></div>`;
  }).join("");
  // the rest of the vendors behind the same "+N" every list uses
  const more = t.length || vendors.length <= VENDORS ? ""
    : DRAW.more(ui.open.has(c.key) ? 0 : vendors.length - VENDORS, { pill: true, act: "more", data: ` data-k="${esc(c.key)}"`, open: ui.open.has(c.key), less: "Fewer" });
  return { html: `${rows}${more}`, need };
}
function cardOf(c, list, sc, t, n) {
  // a command out of quota or down says so once on its card, with the door to where it is fixed
  const state = c.held ? `<div class="pg-st">${DRAW.lock(c.held.until ? `${LIMIT} until ${backWord(c.held.until)}` : LIMIT)}<a class="act small quiet" href="accounts.html?head=${enc(c.key)}">Accounts${DRAW.go}</a></div>`
    : c.down ? `<div class="pg-st">${DRAW.nothing(DOWN)}<a class="act small quiet" href="models.html?cmd=${enc(c.key)}">Models${DRAW.go}</a></div>` : "";
  // a vendor's name is said over its models, so a grouped list names each model by the rest of its label
  const grouped = vendorsIn(c.models) >= 3;
  const body = grouped ? vendorsHtml(c, list, sc, t, n) : listHtml(c, list, c.key, sc, LIST, (m) => m.label, n);
  // a key has no name of its own to lead with: its card leads with the command, so two runtimes on one key variable are two
  const chip = DRAW.chip({ text: c.command }), keyed = c.acct.kind === "key";
  return {
    html: `<article class="card pg-cmd${c.held ? " held" : ""}${c.down ? " down" : ""}" style="--c:${colorOf(c.family)}"><div class="pg-ch${keyed ? " keyed" : ""}">${keyed ? `${chip}${acctHtml(c.acct)}` : `${acctHtml(c.acct, { door: true })}${chip}`}</div>${state}<div class="pg-list${grouped ? " pg-vends" : ""}">${body.html}</div></article>`,
    need: body.need,
  };
}
// how many of [rem] fit across [px], the board's column gap between them
const remPx = () => parseFloat(getComputedStyle(document.documentElement).fontSize) || 16;
const fit = (px, rem) => { const r = remPx(), gap = 2 * r; return Math.max(1, Math.floor((px + gap) / (rem * r + gap))); };
const boardFit = () => fit(root.clientWidth - 8 * 0.667 * remPx(), COL_REM);
const lanesFit = () => fit(root.clientWidth, LANE_REM);
// A provider is a titled group on the balanced board (kit DRAW.masonry): each into the shortest column, measured, and a
// provider whose opened list needs more columns stands that many columns wide.
const providerBlock = (pv, k, body) => `<div class="pg-pb" data-cols="${k}">${DRAW.group({ name: pv.title, c: colorOf(pv.family), cls: `titled pg-prov${body ? "" : " void"}`, style: k > 1 ? `--k:${k}` : "", body: body ?? `<div class="pg-void" aria-hidden="true"></div>` })}</div>`;
function boardHtml() {
  const t = terms(), n = ui.fit?.board ?? boardFit();
  const models = ui.cmds.flatMap((c) => c.models);
  const sc = { win: top(models.map((m) => m.window)), price: top(models.flatMap((m) => [m.rates?.input, m.rates?.output])) };
  // ready commands first, then the ones out of quota, then the ones that are down; each provider under its own title
  const rank = (c) => (c.down ? 2 : c.held ? 1 : 0);
  const sorted = [...ui.cmds].sort((a, b) => rank(a) - rank(b));
  const provs = [], all = [];
  for (const c of sorted) {
    if (!all.some((x) => x.title === c.title)) all.push({ title: c.title, family: c.family });
    const list = t.length ? c.models.filter((m) => hits(c, m, t)) : c.models;
    if (!list.length) continue;
    let pv = provs.find((x) => x.title === c.title);
    if (!pv) provs.push((pv = { title: c.title, family: c.family, cards: [] }));
    pv.cards.push(cardOf(c, list, sc, t, n));
  }
  ui.unfold = null;
  // nothing matches: every provider stands dimmed over an empty slot, the count already reading zero
  if (!provs.length) return DRAW.masonry(all.map((pv) => providerBlock(pv, 1, null)), COL_REM, { n });
  return DRAW.masonry(provs.map((pv) => providerBlock(pv, Math.max(1, ...pv.cards.map((x) => x.need)), DRAW.cards(pv.cards.map((x) => x.html)))), COL_REM, { n });
}
const matched = () => { const t = terms(); return (ui.cmds || []).reduce((a, c) => a + c.models.filter((m) => !t.length || hits(c, m, t)).length, 0); };
const countHtml = () => (terms().length ? `<b>${DRAW.n(matched())}</b> of ${DRAW.n(offered())}` : `<b>${DRAW.n(offered())}</b> models`);
function pickerHtml() {
  // four chosen: the lock stands where the rows went off, in the board's own head
  const full = ui.panes.length >= MAX_PANES ? DRAW.lock(`${MAX_PANES} of ${MAX_PANES} chosen`, { pop: true }) : "";
  // the kicker carries what the board counts, what is full and the price unit, once, with the close at its end
  const aside = `<span class="pg-pa"><span class="pg-count" aria-live="polite">${countHtml()}</span>${full}`
    + `<span class="pg-sortsw">${DRAW.switcher([["window", "Most context"], ["price", "Cheapest"]], ui.sort, "sort", "Order the models by")}</span><span class="pg-unit">Prices per M tokens</span>`
    + `<button class="icon pg-shut" type="button" data-act="shut" aria-label="Close the models">${ICON.close}</button></span>`;
  const find = `<div class="pg-find"><label class="search pg-search">${FIND}<input class="field" id="find" type="search" placeholder="Find a model" value="${esc(ui.q)}" autocomplete="off" spellcheck="false" aria-label="Find a model"></label></div>`;
  return `<div class="plate pg-pick${ui.opened ? " opening" : ""}" id="pick" role="group" aria-label="Models to compare">`
    + `${DRAW.section("Choose models", `${find}<div class="pg-board">${boardHtml()}</div>`, { aside, head: "pg-pickhead", cls: "pg-picks" })}</div>`;
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
  const rows = Array.from({ length: kept }, (_, k) => `<div class="pg-tr ghosted${k === 0 ? " pg-a" : ""}${k === MAX_PANES - 1 ? " pg-z" : ""}" style="grid-row:${k + 2};--i:${k}" aria-hidden="true">`
    + `<span class="pg-c pg-railc"><span class="pg-sock tlamp"></span></span><span class="pg-c pg-rwho">${GHOST.bar(10)}${GHOST.chip(5.5)}</span>`
    + `<span class="pg-c pg-time">${GHOST.track()}</span><span class="pg-c pg-tv">${GHOST.fig(3)}</span>${COLS.map(() => `<span class="pg-c">${GHOST.cell()}</span>`).join("")}</div>`).join("");
  return `<div class="pg-table" aria-hidden="true">${headHtml(axisNow())}${rows}${kept < MAX_PANES ? placesHtml(kept, { ghost: true }) : swapRow(kept, { ghost: true })}</div>`;
}
function ghostPickHtml() {
  if (keptModels().length >= MAX_PANES) return "";
  const prov = (rows, i) => `<div class="pg-pb"><section class="titled pg-prov ghosted" style="--i:${i % 3}" aria-hidden="true"><header>${GHOST.bar(9, "big")}</header>`
    + `${GHOST.card([15, ...Array.from({ length: rows }, (_, k) => 17 + ((k * 7 + i * 3) % 6))], 0, "pg-cmd")}</section></div>`;
  return `<div class="plate pg-pick" aria-hidden="true">${DRAW.section("Choose models", `<div class="pg-find">${GHOST.bar(24, "chip")}</div>`
    + `<div class="pg-board">${DRAW.masonry([8, 6, 7, 9, 2, 9, 3, 1, 1].map(prov), COL_REM, { n: boardFit() })}</div>`,
  { aside: `<span class="pg-pa">${GHOST.bar(6)}</span>`, head: "pg-pickhead", cls: "pg-picks" })}</div>`;
}
function ghostAnswersHtml() {
  const kept = keptModels().length;
  if (!kept) return "";
  const pane = (i) => `<article class="pgp ghosted" style="--i:${i}" aria-hidden="true"><div class="pgc"><header class="pg-head"><span class="pg-sock tlamp"></span>`
    + `<div class="pg-nm">${GHOST.bar(13, "big")}${GHOST.chip(5.5)}</div><div class="pg-meta">${GHOST.bar(12)}</div></header><div class="pg-body"></div></div></article>`;
  return `<section class="pg-answers" aria-hidden="true"><div class="sechead pg-ahead"><h2 class="kicker">Answers</h2></div>`
    + `<div class="pg-lanes" style="--lanes:${Math.min(kept, lanesFit())}">${Array.from({ length: kept }, (_, i) => pane(i)).join("")}</div></section>`;
}
const unreadHtml = () => `<div class="pg-unread"><span class="state limit">${esc(ui.error)}</span>${DRAW.act({ word: "Read again", act: "reread", busy: ui.reading })}`
  + `${ui.error.startsWith("Sign") ? `<a class="act quiet" href="accounts.html">Accounts${DRAW.go}</a>` : ""}</div>`;
// the table's four places with the first one holding a line of its own: what stopped the page, and what to press
const placesWith = (inner) => Array.from({ length: MAX_PANES }, (_, k) => (k === 0
  ? `<div class="pg-tr pg-addrow pg-a" style="grid-row:2"><span class="pg-c pg-railc"><span class="pg-sock tlamp open" aria-hidden="true"></span></span><div class="add pg-add pg-nada" role="status">${inner}</div></div>`
  : slotHtml(k + 2, k === MAX_PANES - 1 ? " pg-z" : ""))).join("");
// nothing can send: the table's four places stand empty, the first one saying so, and under them each command that can't,
// why, and the door that fixes it
function noneHtml() {
  const rows = ui.blocked.map((b) => `<li style="--c:${colorOf(b.family)}">${DRAW.chip({ text: b.command })}<span class="pg-why${b.bad ? " bad" : ""}">${esc(b.word)}</span>`
    + `<a class="act small quiet" href="${b.door === "models" ? `models.html?cmd=${enc(b.key)}` : `accounts.html?head=${enc(b.key)}`}">${b.door === "models" ? "Models" : "Accounts"}${DRAW.go}</a></li>`).join("");
  return `<div class="pg-none"><div class="pg-table" role="table" aria-label="The models compared">${headHtml(axisNow())}${placesWith(`<span class="pg-addw">No model can send now</span>`)}</div>`
    + `${rows ? `<ul class="pg-blocked">${rows}</ul>` : `<a class="act small" href="models.html">Models${DRAW.go}</a>`}</div>`;
}
// the first read failed: the same four places, the first saying what was not read, with the way to read again
const unreadTableHtml = () => `<div class="pg-none"><div class="pg-table" role="table" aria-label="The models compared">${headHtml(axisNow())}${placesWith(
  `<span class="state limit">${esc(ui.error)}</span>${DRAW.act({ word: "Read again", act: "reread", busy: ui.reading })}`
  + `${ui.error.startsWith("Sign") ? `<a class="act quiet" href="accounts.html">Accounts${DRAW.go}</a>` : ""}`)}</div></div>`;


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
  grow();
}
// the pane's acts alone, so a request folding keeps its pane (and its answer) untouched
function paintActs(p) {
  const el = root.querySelector(`.pgp[data-u="${p.uid}"] .pg-acts`);
  if (!el) return;
  const f = focused();
  el.outerHTML = actsHtml(p);
  refocus(f);
}
// a request closes by folding up to nothing, and only then is the pane drawn without it; with motion reduced it goes at once
function foldRec(p) {
  const el = root.querySelector(`#rq-${p.uid}`);
  p.req = false; paintActs(p);
  if (!el || still()) { paintPane(p); return; }
  el.classList.remove("unfold"); el.classList.add("fold");
  let gone = false;
  const end = () => { if (gone) return; gone = true; if (!p.req) paintPane(p); };
  el.addEventListener("animationend", (e) => { if (e.target === el) end(); });
  setTimeout(end, 420);
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
      if (p.req) { foldRec(p); return; }
      p.req = true; p.reqNew = true; paintPane(p);
      // the record unfolds first; the page follows it down once there is something to follow
      setTimeout(() => root.querySelector(`#rq-${p.uid}`)?.scrollIntoView({ block: "nearest", behavior: still() ? "auto" : "smooth" }), still() ? 0 : 260);
      return;
    }
    // a failure that no retry fixes offers another model: this one comes off without asking, and the board opens to choose it
    case "swap": {
      if (!p) return;
      ui.panes.splice(ui.panes.indexOf(p), 1);
      keepModels(); stopTicker();
      ui.add = true; ui.opened = true;
      render(); paintSend();
      const f = document.getElementById("find");
      f?.focus({ preventScroll: true });
      f?.closest(".pg-pick")?.scrollIntoView({ block: "nearest", behavior: still() ? "auto" : "smooth" });
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
    // the models read in another order: the lists are drawn again in it and fade in where they stand
    case "sort": {
      if (ui.sort === t.dataset.v) return;
      ui.sort = t.dataset.v;
      for (const b of root.querySelectorAll('[data-act="sort"]')) b.setAttribute("aria-pressed", String(b.dataset.v === ui.sort));
      const board = root.querySelector(".pg-board");
      paintBoard();
      if (board && !still()) { board.classList.remove("swap"); void board.offsetWidth; board.classList.add("swap"); }
      return;
    }
    case "more": case "vendor": {
      const k = t.dataset.k;
      flip(ui.open, k);
      ui.unfold = ui.open.has(k) ? k : null;
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
