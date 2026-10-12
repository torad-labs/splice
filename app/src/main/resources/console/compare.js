// Compare models: one prompt to any model he has, Claude-plan models included, and each answer to read beside the others.
// Each pane is one POST /api/playground {head, prompt, model}: the command's own provider builds the one-message turn a real
// turn would send, as the login a real turn would use next, and nothing is recorded. The answer comes back whole, as the
// provider's own stream in {raw: text}, and this page reads its frames. A pane's Request opens the send in Requests' own open
// request (reqview.js). The picker lists only the commands that can send now (a login added to splice, a key, a local
// runtime), never the client's own sign-in, which splice has no credential for.
// Reads: GET /api/heads, /api/models, /api/auth, /api/accounts.
"use strict";

const MAX_PANES = 4;
const KEPT = "splice.compare.prompt";
const WAIT = '<svg class="wait" viewBox="0 0 40 40" aria-hidden="true"><circle cx="20" cy="20" r="15"/></svg>';
const TICK = '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="3.2" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><path d="M5 12.5l4.5 4.5L19 7"/></svg>';
const PLUS = G('<path d="M12 5v14M5 12h14"/>', 'aria-hidden="true"');
const root = document.getElementById("pg");

// cmds: the commands that can send, each { key, command, family, models: [{id, label}], acct, plan }
const ui = { prompt: "", panes: [], cmds: null, error: null, raw: null, add: false, rv: { tab: "sent", nodes: new Set(), q: "" } };
try { ui.prompt = sessionStorage.getItem(KEPT) ?? ""; } catch { /* no storage: the field starts empty */ }
const keep = () => { try { sessionStorage.setItem(KEPT, ui.prompt); } catch { /* nothing to keep it in */ } };
const RV = REQVIEW.view(() => ui.rv);

// ---------- reading what can send ----------
const poolSends = (a) => a.account_pool.accounts.some((x) => x.credential_present && x.available !== false);
const canSend = (a) => Boolean(a) && (a.account_pool ? poolSends(a) : a.present === true && !a.refusal && a.verdict?.state !== "rejected");
async function read() {
  tick();
  const [heads, models, auth, accts] = await Promise.all(["/api/heads", "/api/models", "/api/auth", "/api/accounts"].map((p) => API.get(p)));
  if (!heads.ok || !models.ok) { console.warn(heads.ok ? `compare: /api/models answered ${models.status}` : `compare: /api/heads answered ${heads.status}`); ui.error = "Not read"; ui.cmds = []; return; }
  const label = Object.fromEntries((heads.body?.heads || []).map((h) => [h.key, h.label || h.key]));
  const rows = accts.body?.accounts || [];
  ui.cmds = (models.body?.heads || []).flatMap((row) => {
    const a = auth.body?.[row.head], list = (row.models || []).filter((m) => m.resolved !== false && m.id);
    if (!canSend(a) || !list.length) return [];
    const pool = a.account_pool, name = pool ? (pool.next_target ?? pool.selected_label) : (a.display_name ?? null);
    const known = rows.find((r) => (r.heads || []).includes(row.head) && (!name || r.label === name || `native:${r.label}` === name || r.display_name === name));
    return [{
      key: row.head, command: label[row.head] ?? row.head, family: PROVIDER_FAMILY[row.provider] ?? row.provider,
      models: list.map((m) => ({ id: m.id, label: modelName({ [m.id]: m.label }, m.id) })), acct: known?.display_name ?? name ?? "", plan: known?.plan ? known.plan[0].toUpperCase() + known.plan.slice(1) : "",
    }];
  });
}
const cmdOf = (key) => ui.cmds?.find((c) => c.key === key);

// ---------- the panes ----------
const pane = (key, id, label) => ({ key, id, label, st: "idle", asked: null, fail: null, ms: null, at: null, text: "", tin: null, tout: null, trace: null, tries: 0, t0: 0 });
const paneOf = (key, id) => ui.panes.find((p) => p.key === key && p.id === id);
const busy = () => ui.panes.some((p) => p.st === "sending");
const sendCount = () => ui.panes.length;
const sendable = () => ui.prompt.trim() && ui.panes.length > 0 && !busy();
const stale = (p) => p.asked != null && p.st !== "sending" && p.asked !== ui.prompt;
const hasRaw = (p) => p.trace != null;
const secs = (ms) => (ms >= 60000 ? `${Math.round(ms / 60000)} min` : `${(ms / 1000).toFixed(1)} s`);

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
// What went wrong, in Fin's word for its kind: the provider's status or error type, else the sentence splice answered.
function failWord(status, body, said) {
  const type = body?.error?.type ?? body?.error?.code ?? body?.type ?? "";
  if (status === 429 || /rate_limit|usage_limit/.test(type)) return OUTCOME_WORD["error:rate-limited"];
  if (status === 529 || /overloaded/.test(type)) return OUTCOME_WORD["failure:overloaded_error"];
  if (status === 401 || status === 403) return OUTCOME_WORD["failure:authentication_error"];
  if (status >= 500 && status < 600) return OUTCOME_WORD["error:upstream-failed"];
  if (status >= 400 && status < 500) return OUTCOME_WORD["failure:invalid_request_error"];
  if (/timed? ?out|HttpRequestTimeout/i.test(said)) return OUTCOME_WORD["error:turn-cap"];
  if (/no credential|forwards the caller/.test(said)) return OUTCOME_WORD["error:auth-missing"];
  if (/no running provider/.test(said)) return "Not running";
  if (/failed:/.test(said)) return OUTCOME_WORD["error:conn-reset"];
  return OUTCOME_WORD["error:unexpected"];
}

// ---------- sending: one POST per pane, all at once, each answering on its own clock ----------
async function ask(p) {
  const t0 = Date.now(), began = performance.now();
  Object.assign(p, { st: "sending", t0, at: new Date(t0), asked: ui.prompt, fail: null, text: "", tin: null, tout: null, trace: null, tries: p.fail ? p.tries + 1 : 1 });
  const sent = { head: p.key, prompt: ui.prompt, model: p.id };
  const res = await API.post("/api/playground", sent);
  const ms = Math.round(performance.now() - began);
  p.ms = ms;
  const reply = res.body;
  if (!res.ok || !reply?.response) { // splice's own refusal, or a call that never reached the provider
    const said = reply?.error ?? (res.status === 0 ? "splice is not answering" : `splice answered ${res.status}`);
    Object.assign(p, { st: "failed", fail: { word: failWord(0, null, said), retry: res.status !== 400, said } });
    p.trace = null;
    return;
  }
  const status = reply.response.status, body = reply.response.body;
  const frames = typeof body?.raw === "string" ? framesOf(body.raw) : null;
  const read = frames ? readFrames(frames) : { text: "", tin: null, tout: null, error: null };
  const attempt = { url: reply.request.url, start: 0, ms, headers: reply.request.headers, body: reply.request.body, status, ...(frames ? { frames } : { raw: body }) };
  p.trace = { sent: { headers: { "content-type": "application/json" }, body: sent }, attempts: [attempt], returned: { status, ...(frames ? { frames } : { body }) } };
  const errBody = read.error?.error ?? read.error?.response?.error ? { error: read.error.error ?? read.error.response.error } : body;
  if (status >= 400 || read.error) {
    const word = failWord(read.error ? 0 : status, errBody, "");
    const type = errBody?.error?.type ?? errBody?.error?.code ?? "";
    Object.assign(p, { st: "failed", fail: { word: /overloaded/.test(type) ? OUTCOME_WORD["failure:overloaded_error"] : word, retry: true } });
    attempt.failure = p.fail.word;
  } else if (!read.text.trim() && !(body && !frames)) {
    Object.assign(p, { st: "failed", fail: { word: OUTCOME_WORD.empty_model, retry: true }, tin: read.tin, tout: 0 });
    attempt.failure = p.fail.word;
  } else {
    const plain = !frames && body ? (body.choices?.[0]?.message?.content ?? body.content?.[0]?.text ?? JSON.stringify(body)) : "";
    Object.assign(p, { st: "done", text: read.text || plain, tin: read.tin, tout: read.tout });
  }
}
let ticker = null;
function tickClock() { // the stopwatch in each waiting pane, written in place so the prompt keeps its caret
  for (const el of root.querySelectorAll("[data-t0]")) {
    const ms = Date.now() - Number(el.dataset.t0);
    el.innerHTML = `${ICON.watch(Math.floor(ms / 1000))}<span>${counter(ms)}</span>`;
  }
}
async function run(panes) {
  if (ui.raw != null && panes.includes(ui.panes[ui.raw])) ui.raw = null;
  ui.add = false;
  const all = panes.map((p) => ask(p).then(() => { render(); if (!busy()) { clearInterval(ticker); ticker = null; } }));
  render(); ticker ??= setInterval(tickClock, 250);
  root.querySelector(".panes")?.scrollIntoView({ block: "start" });
  await Promise.all(all);
}
const send = () => { if (sendable()) run([...ui.panes]); };

// ---------- drawing ----------
// the account as Accounts heads its card: its name, then its provider and plan dimmed; a command with no account name says its provider
const acctHtml = (c) => {
  const provider = PROVIDER_NAME[c.family] ?? c.command, dim = [c.acct ? provider : null, c.plan].filter(Boolean).join(" · ");
  return `<span class="acct"><span class="pg-plan">${esc(c.acct || provider)}</span>${dim ? `<span class="pg-pv">${esc(dim)}</span>` : ""}</span>`;
};
const cmdChip = (c) => `<span class="chip">${esc(c.command)}</span>`;
function chipHtml(p) {
  const c = cmdOf(p.key);
  return `<span class="pg-chip" style="--c:${colorOf(c?.family)}"><i class="pg-dot" aria-hidden="true"></i><span>${esc(p.label)}</span>`
    + `<button class="icon" type="button" data-act="toggle" data-key="${esc(p.key)}" data-model="${esc(p.id)}" aria-label="Remove ${esc(p.label)}"${p.st === "sending" ? " disabled" : ""}>${ICON.close}</button></span>`;
}
function groupHtml(c) {
  const full = ui.panes.length >= MAX_PANES;
  const pills = c.models.map((m) => {
    const p = paneOf(c.key, m.id), on = Boolean(p), lock = (!on && full) || (on && p.st === "sending");
    return `<button class="pg-pill" type="button" aria-pressed="${on}" data-act="toggle" data-key="${esc(c.key)}" data-model="${esc(m.id)}"${lock ? " disabled" : ""}><span class="pg-tick">${TICK}</span>${esc(m.label)}</button>`;
  }).join("");
  return `<section class="pg-group" style="--c:${colorOf(c.family)}"><div class="pg-id">${acctHtml(c)}${cmdChip(c)}</div><div class="pg-pills">${pills}</div></section>`;
}
const addHtml = () => `<div class="pg-menu" role="group" aria-label="Models">${ui.cmds.map(groupHtml).join("")}</div>`;
function closeAdd() {
  if (!ui.add) return;
  ui.add = false;
  root.querySelector(".pg-menu")?.remove();
  root.querySelector(".pg-addbtn")?.setAttribute("aria-expanded", "false");
}
function rowHtml() {
  const n = sendCount();
  return `<section class="card pg-heads"><div class="pg-row"><div class="pg-picks">${ui.panes.map(chipHtml).join("")}`
    + `<span class="pg-add"><button class="act quiet pg-addbtn" type="button" data-act="add" aria-expanded="${ui.add}">${PLUS}Add model</button>${ui.add ? addHtml() : ""}</span></div>`
    + `<button class="send pg-send${busy() ? " busy" : ""}" type="submit"${sendable() ? "" : " disabled"}${busy() ? ' aria-busy="true"' : ""}>${busy() ? WAIT : ICON.send}<span>${n ? `Send (${n})` : "Send"}</span></button></div></section>`;
}
// The markdown he writes a plan in: headings, paragraphs, bullets, numbered steps, checkboxes, code and bold. kit.js's md()
// keeps the agent's replies as they are; a plan needs headings and boxes too, so they are read here.
const inl = (s) => inline(s).replace(/\*\*([^*]+)\*\*/g, "<b>$1</b>");
function mdPlan(text) {
  const out = [], code = [];
  let list = null, fence = false;
  const flush = () => { if (list) { out.push(`<${list.tag}${list.cls}>${list.items.join("")}</${list.tag}>`); list = null; } };
  const item = (tag, cls, html) => { if (list?.tag !== tag || list.cls !== cls) { flush(); list = { tag, cls, items: [] }; } list.items.push(`<li>${html}</li>`); };
  for (const line of text.split("\n")) {
    if (line.trim().startsWith("```")) { // a code fence: its lines are kept as written
      if (fence) { out.push(`<pre><code>${esc(code.join("\n"))}</code></pre>`); code.length = 0; fence = false; } else { flush(); fence = true; }
      continue;
    }
    if (fence) { code.push(line); continue; }
    let m;
    if ((m = /^\s*[-*]\s+\[([ xX])\]\s+(.*)$/.exec(line))) item("ul", ' class="tasks"', `<span class="pg-box${/[xX]/.test(m[1]) ? " on" : ""}" aria-hidden="true"></span><span>${inl(m[2])}</span>`);
    else if ((m = /^\s*[-*]\s+(.*)$/.exec(line))) item("ul", "", inl(m[1]));
    else if ((m = /^\s*\d+[.)]\s+(.*)$/.exec(line))) item("ol", "", inl(m[1]));
    else {
      flush();
      if ((m = /^(#{1,3})\s+(.*)$/.exec(line))) out.push(`<h${m[1].length + 2}>${inl(m[2])}</h${m[1].length + 2}>`);
      else if (line.trim()) out.push(`<p>${inl(line)}</p>`);
    }
  }
  flush();
  if (fence) out.push(`<pre><code>${esc(code.join("\n"))}</code></pre>`);
  return out.join("");
}
function composeHtml() {
  return `<form class="pg-compose" id="ask"><section class="card pg-md${ui.prompt.trim() ? "" : " blank"}"><textarea class="field" id="prompt" rows="6" placeholder="Write a question or a plan" aria-label="Prompt" spellcheck="false">${esc(ui.prompt)}</textarea>`
    + `<div class="pg-md-out" aria-label="Preview">${mdPlan(ui.prompt)}</div></section>${rowHtml()}</form>`;
}
// the answer under an older prompt is dimmed, and that prompt sits above it as his message
const askedHtml = (p) => (stale(p) ? `<div class="msg his"><div class="body">${mdPlan(p.asked)}</div></div>` : "");
function fixHtml(p, i) { // the failure's word, then the one thing that fixes it
  const word = `<span class="state limit">${esc(p.fail.word)}</span>${p.tries > 1 ? `<span class="pg-try">Try <b>${p.tries}</b></span>` : ""}`;
  if (p.fail.word === OUTCOME_WORD["error:auth-missing"] || p.fail.word === OUTCOME_WORD["failure:authentication_error"]) {
    return `${word}<a class="act" href="accounts.html">Accounts</a>`;
  }
  return p.fail.retry ? `${word}<button class="act primary" data-act="retry" data-i="${i}">Try again</button>` : word;
}
function paneHtml(p, i) {
  const c = cmdOf(p.key), raw = ui.raw === i;
  const close = ui.panes.length > 1 ? `<button class="icon" data-act="close" data-i="${i}" aria-label="Remove ${esc(p.label)}">${ICON.close}</button>` : "";
  const head = `<header><div class="who"><span class="pg-model">${esc(p.label)}</span><div class="meta">${c ? acctHtml(c) + cmdChip(c) : ""}</div></div><div class="acts">${close}</div></header>`;
  let body = "";
  if (p.st === "sending") {
    body = `${askedHtml(p)}<div class="pg-wait"><span class="lamp working" aria-hidden="true">${ICON.trace}</span><span class="detail" data-t0="${p.t0}">${ICON.watch(Math.floor((Date.now() - p.t0) / 1000))}<span>${counter(Date.now() - p.t0)}</span></span></div>`;
  } else if (p.st === "failed") body = `${askedHtml(p)}<div class="pg-fix${p.tries > 1 ? " again" : ""}">${fixHtml(p, i)}</div>`;
  else if (p.st === "done") body = `${askedHtml(p)}<div class="msg agent"><div class="body">${mdPlan(p.text)}</div></div>`;
  const back = p.st === "done" || p.st === "failed";
  const toks = (a, b) => `<span class="toks"><b>${a}</b> tokens in<i class="sep">·</i><b>${b}</b> out</span>`;
  const figs = (p.tin != null && p.tout != null ? toks(p.tin, p.tout) : "") + (back ? `<span>${secs(p.ms)}</span>` : "");
  const foot = back ? `<footer>${figs}${hasRaw(p) ? `<button class="act quiet small" data-act="raw" data-i="${i}" aria-pressed="${raw}">Request</button>` : ""}</footer>` : "";
  return `<section class="pgp${stale(p) ? " stale" : ""}" style="--c:${colorOf(c?.family)}" data-key="pane:${i}">${head}${body ? `<div class="answer">${body}</div>` : ""}${foot}</section>`;
}
// ---------- the request, in Requests' own open request (reqview.js) ----------
function requestHtml(p, i) {
  const c = cmdOf(p.key), f = p.st === "failed" ? p.fail : null, a = p.trace.attempts[0];
  const r = { id: `${p.key}:${p.id}`, cmd: c?.command ?? p.key, provider: c?.family, model: p.id, ts: new Date(+p.at + p.ms), total: p.ms, in: p.tin, out: p.tout, cached: 0, attempts: 1 };
  const word = f ? `<span class="word fail">${esc(f.word)}</span>` : "";
  return RV.paneHtml(r, {
    lampCls: f ? "fail" : "clean", lamp: f ? REQVIEW.GLYPH.bang : "<i></i>", word, name: p.label, acct: c?.acct ? esc(c.acct) : "",
    acts: `<button class="icon" data-act="raw" data-i="${i}" aria-label="Close">${ICON.close}</button>`, st: "kept", tr: () => p.trace, cells: ["in", "out", "attempts"],
  });
}
function render() {
  const field = document.getElementById("prompt"), had = field && document.activeElement === field, sel = had && [field.selectionStart, field.selectionEnd];
  if (ui.cmds === null) { root.innerHTML = ""; return; }
  if (ui.error) { root.innerHTML = `<section class="card"><p class="state limit">${esc(ui.error)}</p></section>`; return; }
  const open = ui.raw != null && ui.panes[ui.raw] && hasRaw(ui.panes[ui.raw]) ? ui.raw : null;
  const cells = ui.panes.map((p, i) => paneHtml(p, i) + (i === open ? requestHtml(p, i) : "")).join("");
  root.innerHTML = `${composeHtml()}<div class="panes" style="--cols:${Math.max(ui.panes.length, 1)}">${cells}</div>`;
  const rq = root.querySelector(".panes > .rqpane");
  if (rq) { // the request opens under the row its pane is in, so the answers beside it stay side by side
    const mine = rq.previousElementSibling;
    rq.remove();
    const row = [...root.querySelectorAll(".panes > .pgp")].filter((x) => x.offsetTop === mine.offsetTop);
    row.at(-1).after(rq);
  }
  if (had) { const f = document.getElementById("prompt"); f.focus(); f.setSelectionRange(...sel); }
}

root.addEventListener("input", (e) => {
  if (e.target.id !== "prompt") return;
  const was = ui.panes.map(stale).join();
  ui.prompt = e.target.value; keep();
  root.querySelector(".pg-md-out").innerHTML = mdPlan(ui.prompt);
  root.querySelector(".pg-md").classList.toggle("blank", !ui.prompt.trim());
  if (ui.panes.map(stale).join() !== was) render(); else root.querySelector(".pg-send").disabled = !sendable();
});
root.addEventListener("submit", (e) => { e.preventDefault(); send(); });
root.addEventListener("keydown", (e) => { if (e.target.id === "prompt" && e.key === "Enter" && (e.ctrlKey || e.metaKey)) { e.preventDefault(); send(); } });
document.addEventListener("keydown", (e) => { if (e.key === "Escape" && ui.add) { closeAdd(); root.querySelector(".pg-addbtn")?.focus(); } });
document.addEventListener("click", (e) => {
  if (e.target.closest("a")) return;
  if (!e.target.closest(".pg-add")) closeAdd();
  const t = e.target.closest("[data-act]");
  if (!t) return;
  const i = Number(t.dataset.i), p = ui.panes[i];
  switch (t.dataset.act) {
    case "theme": toggleTheme(); return;
    case "add":
      ui.add = !ui.add; render();
      if (ui.add) root.querySelector(".pg-menu button:not(:disabled)")?.focus({ preventScroll: true });
      return;
    case "toggle": {
      const { key, model } = t.dataset, on = paneOf(key, model);
      if (!on) { const m = cmdOf(key)?.models.find((x) => x.id === model); if (m && ui.panes.length < MAX_PANES) ui.panes.push(pane(key, model, m.label)); break; }
      if (on.st === "sending") break;
      ui.panes.splice(ui.panes.indexOf(on), 1); ui.raw = null; break;
    }
    case "close": ui.panes.splice(i, 1); ui.raw = null; break;
    case "raw": ui.raw = ui.raw === i ? null : i; render(); root.querySelector(".rqpane")?.scrollIntoView({ block: "nearest" }); return;
    case "tab": case "node": case "frames": RV.click(t); break;
    case "retry": if (p && ui.prompt.trim() && p.st !== "sending") { run([p]); return; } break;
    default: return;
  }
  render();
});

(async () => {
  render();
  await read();
  render();
})();
