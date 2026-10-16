// The composer: the door to the splice manager, ONE resident Claude Code session that works in splice's global settings
// folder and helps run splice (Marcos, Oct 15: "the composer for splice is a session that runs inside a splice global
// settings folder and its goal is to help manage splice, not to switch between multiple sessions"). It never types into
// any other session: the manager reaches other sessions itself, the way any session can.
//
// WHERE EVERY VALUE COMES FROM. The composer carries no data of its own:
//   GET  /api/manager                        the manager's state: every 15 s, every 3 s while the sheet is open. Its `line` is what
//                                            the manager's status line last said: model, effort, context used of its window
//   POST /api/manager/start {head}           Start on the first-run card, and Resume (also a send while it is not running)
//   POST /api/manager/head {head}            the head chip's menu: Continue on, the same conversation on another head
//   GET  /api/sessions/{id}/transcript       while the sheet is open: its newest 50 messages, then only what is newer
//   GET  /api/sessions/{id}/screen           before a send, and while it waits on him: a menu takes buttons, never typing
//   POST /api/sessions/{id}/answer           the numbered choice he pressed
//   POST /api/sessions/{id}/say {text}       his message, typed into the manager's terminal (200 = typed, nothing more); the
//                                            model, effort and compact controls type Claude Code's own /model, /effort and
//                                            /compact the way he would, so the one conversation goes on in the same session
//   POST /api/sessions/{id}/stop             the Stop button while a turn runs (Sessions' own Stop)
//   GET  /api/models                         the model menu: this head's rows (id, label, context window)
//   GET  /api/accounts                       the limits strip: the head's account and its 5 h and 7 d windows, as Accounts draws
//   GET  /api/auth/{head}/order              which of several accounts the head sends as next (Sessions' choice of one)
//
// It mounts once, after console.js has taken the page's own nodes, outside main.page, so the router's swap of main.page
// never removes it.
"use strict";

(() => {
  const OPEN_MS = 3_000, CLOSED_MS = 15_000, SAID_MS = 4_000, PAGE = 50, FOLLOW_PX = 40, TYPED_MS = 120_000, EXIT_MS = 60_000;
  const LIMITS_MS = 30_000, MODELS_MS = 60_000, WANT_MS = 12_000, WANT_POLL_MS = 1_200, WARN_PCT = 60, CRITICAL_PCT = 85;
  // the levels Claude Code's /effort takes (code.claude.com/docs/en/model-config); a model that has fewer falls back to the highest below
  const EFFORTS = [["low", "Low"], ["medium", "Medium"], ["high", "High"], ["xhigh", "Extra high"], ["max", "Max"]];
  const MAC = /Mac|iPhone|iPad/.test(navigator.platform || navigator.userAgent || "");
  const KEYS = MAC ? "⌘K" : "Ctrl+K";
  const WORD = { working: "Working", waiting: "Waiting on you", idle: "Ready" };
  const X = `<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.4" aria-hidden="true"><path d="M6 6l12 12M18 6L6 18"/></svg>`;
  const CARET = `<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.4" aria-hidden="true"><path d="M6 15l6-6 6 6"/></svg>`;

  const h = (v) => String(v ?? "").replace(/[&<>"']/g, (c) => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" })[c]);
  const oneLine = (v) => String(v ?? "").replace(/\s+/g, " ").trim();
  const locked = () => document.body.classList.contains("locked");
  const route = (id, act) => `/api/sessions/${encodeURIComponent(id)}/${act}`;
  const home = (p) => String(p || "").replace(/^\/(?:home|Users)\/[^/]+/, "~");

  const st = {
    m: null, gone: null,        // the manager's state as last read; why it could not be read (absent, down, error)
    phase: null,                // { kind: start | resume | move, head } while that POST is out
    card: false, notNow: false, // the first-run card; Not now holds for this page load only, never stored as a refusal
    pick: null,                 // the head chosen on the card
    open: false, menu: null,    // the conversation sheet; the foot's open menu: head, model or effort
    models: null,               // this head's model rows { head, list, at } while the model menu has read them
    limits: null,               // the head's account windows { head, row, at } for the strip
    want: null,                 // a model or effort change typed and not yet seen on the status line { kind, value, label, at }
    stopping: false,            // Stop was pressed and the turn has not ended yet
    recall: -1,                 // how far back the Up arrow has recalled his own messages; -1 is not recalling
    sid: null, log: null,       // the session whose conversation is drawn, and what was read of it
    fresh: false,               // the drawn conversation is an older id's: the next read is from the end, and replaces it whole
    typed: [],                  // what he sent that the transcript does not hold yet
    ask: null, answering: null, // the manager's screen while it offers a menu; the choice he pressed
    busy: false, line: null, reading: false, timer: 0, opened: new Set(),
  };
  let dock, card, sheet, where, log, ask, meters, foot, line, lamp, door, field, send, lineTimer = 0;
  const drawn = { card: "", ask: "", meters: "", foot: "", rows: [] };

  // ---------- reading ----------
  const heads = () => st.m?.heads || [];
  const headOf = (key) => heads().find((x) => x.key === key) ?? null;
  const labelOf = (key) => headOf(key)?.label || key || "a head";
  /** A head's colour is its provider's (kit.js colorOf). The state names only a key, so the provider is read off the key
   *  ("claude-splice" is Claude's) when the head carries none; a key naming no provider draws in the muted text colour. */
  function tint(key) {
    try {
      const hd = headOf(key);
      let fam = hd?.provider ? PROVIDER_FAMILY[hd.provider] ?? hd.provider : null;
      for (const t of String(key || "").toLowerCase().split(/[^a-z0-9]+/)) {
        if (fam) break;
        fam = COLORS[t] ? t : PROVIDER_FAMILY[t] ?? null;
      }
      return colorOf(fam);
    } catch { return "var(--text-mute)"; }
  }
  function lampOf() {
    if (st.phase) return "busy";
    if (st.gone === "down") return "trouble";
    if (st.m?.state !== "live") return "off";
    return st.m.status === "working" ? "working" : st.m.status === "waiting" ? "waiting" : "idle";
  }
  /** Nothing runs, and either nothing exists to resume or he was never asked: the pill is the door to the card. */
  const unasked = (m) => m.state !== "live" && (m.state === "off" || !m.consented);
  function phaseWord() {
    const p = st.phase;
    return p.kind === "move" ? `Moving to ${labelOf(p.head)}` : p.kind === "resume" ? "Resuming your manager" : `Starting on ${labelOf(p.head)}`;
  }

  /** Every 3 s while the sheet is open and the tab in view, every 15 s while it is closed (the state alone, then). */
  async function tick() {
    if (!document.hidden && !locked() && !st.phase) await readAll();
    clearTimeout(st.timer); // after the read, so two ticks that overlap leave one clock, never two
    st.timer = setTimeout(tick, st.want ? WANT_POLL_MS : st.open ? OPEN_MS : CLOSED_MS);
  }
  async function readAll() {
    if (st.reading) return;
    st.reading = true;
    try {
      const res = await API.get("/api/manager");
      if (st.phase) return; // a start or a move is out: its own answer is the state
      if (res.ok && res.body?.state) { st.gone = null; apply(res.body); }
      else {
        st.gone = res.status === 404 ? "absent" : res.status === 0 ? "down" : "error";
        if (res.status === 404) st.m = null; // a splice without the manager: the pill says so and takes nothing
      }
      if (st.open && st.sid && st.m) {
        await readLog();
        if (st.m.state === "live") await readLimits();
        if (st.m.state === "live" && (st.m.status === "waiting" || st.want)) await readScreen(); else st.ask = null;
      } else if (st.want && st.sid && st.m?.state === "live") await readScreen();
    } finally { st.reading = false; draw(); }
  }
  /** The state the server answered, from a read or from a start or a move. */
  function apply(m) {
    const was = st.m;
    st.m = m;
    if (m.session_id && m.session_id !== st.sid) {
      // a move that copied the conversation under a new id: the view keeps what it shows until the new id reads
      if (st.sid && st.log) st.fresh = true;
      st.sid = m.session_id;
    }
    if (m.state !== "live" || m.status !== "waiting") { st.ask = null; st.answering = null; }
    if (m.state === "off") { st.open = false; st.menu = null; } // nothing left to show: the door to the card stands
    if (m.state === "live") st.card = false;
    else if (!m.consented && !st.notNow && !st.phase) st.card = true; // first run: ask before anything starts or resumes
    // a reply: the turn it was working on ended, for him to read or to answer
    if (was?.state === "live" && was.status === "working" && m.status !== "working" && !st.open && !st.card) openSheet();
    if (m.status !== "working") st.stopping = false;
    settleWant();
  }
  /** A model or effort he asked for is done when the status line names it. When it never does, he is told and pointed at the
   *  terminal, where Claude Code may be asking something the composer cannot draw. */
  function settleWant() {
    const w = st.want, ln = lineOf();
    if (!w) return;
    const named = ln && (w.kind === "effort" ? ln.effort === w.value : isModel({ id: w.value, label: w.label }, ln));
    if (named && ln.at >= w.at) { st.want = null; return; }
    if (st.m?.state !== "live") { st.want = null; return; }
    if (Date.now() - w.at < WANT_MS) return;
    st.want = null;
    say({ tone: "refused", text: `The manager did not switch ${w.kind === "effort" ? "its effort" : "its model"} to ${w.label}. Look at its terminal.`,
      acts: [{ word: "Open on Sessions", run: go }] });
  }
  async function readLog() {
    const id = st.sid, held = st.log;
    const last = held?.messages?.at(-1);
    if (st.fresh || !last || held.sid !== id) return readEnd(id);
    // read again from his first message since the last reply, the oldest one a poll can still change, else the last
    const replied = held.messages.findLastIndex((m) => m.role === "assistant");
    const from = held.messages.slice(replied + 1).find((m) => m.role === "user") ?? last;
    const res = await API.get(`${route(id, "transcript")}?cursor=${encodeURIComponent(`after:${from.index}`)}&limit=${PAGE}`);
    if (id !== st.sid) return;
    if (res.status === 400 || res.body?.more || res.body?.state === "off") return readEnd(id);
    if (!res.ok || !Array.isArray(res.body?.messages)) return;
    const first = res.body.messages[0]?.index ?? Infinity;
    st.log = { ...held, messages: [...held.messages.filter((m) => m.index < first), ...res.body.messages], moves: res.body.moves ?? held.moves };
    settleTyped();
  }
  async function readEnd(id) {
    const res = await API.get(`${route(id, "transcript")}?before=end&limit=${PAGE}`);
    if (id !== st.sid) return;
    const kept = st.log?.messages?.length ? st.log : null;
    if (res.ok && res.body?.state === "off") { if (!kept) st.log = { sid: id, off: true }; st.fresh = false; return; }
    // a moved conversation's new id that reads nothing yet leaves the old view standing; the next poll reads it again
    if (!res.ok || !Array.isArray(res.body?.messages) || (st.fresh && kept && !res.body.messages.length)) {
      if (!kept) st.log = { sid: id, messages: res.status === 404 ? [] : null, error: res.status === 404 ? null : "Not read" };
      return;
    }
    st.log = { sid: id, messages: res.body.messages, earlier: Boolean(res.body.earlier), moves: res.body.moves || [] };
    st.fresh = false;
    settleTyped();
  }
  async function readScreen() {
    const res = await API.get(route(st.sid, "screen"));
    if (!res.ok) return;
    const seen = res.body?.choices?.length || res.body?.asked ? res.body : null;
    const yes = confirming(seen);
    if (yes) { st.want.confirmed = true; await answer(yes.choice, yes.label); return; }
    st.ask = seen;
  }
  /** Claude Code asks "Switch model?" or "Change effort level?" before it applies either while its cache is warm, because the whole
   *  conversation is read again on the next message. He chose the change a moment ago, so that question is his own yes: the plain
   *  Yes is pressed, once, for exactly that question. Any other menu, or any other wording, stays his to answer. */
  function confirming(screen) {
    const w = st.want, first = screen?.choices?.[0];
    if (!w || w.confirmed || !first || !/^yes\b/i.test(first.label)) return null;
    const words = [screen.asked, ...(screen.panel || []).map((l) => l.text)].join(" ");
    return (w.kind === "model" ? /Switch model\?/ : /Change effort level\?/).test(words) ? first : null;
  }
  /** A typed message leaves the view's tail once the transcript holds it, or after two minutes. */
  function settleTyped() {
    const said = (st.log?.messages || []).filter((m) => m.role === "user").map((m) => ({ text: oneLine(m.text), ts: m.ts ?? Infinity }));
    st.typed = st.typed.filter((t) => Date.now() - t.at < TYPED_MS && !said.some((m) => m.text === oneLine(t.text) && m.ts >= t.at - 10_000));
  }

  // ---------- what the strip and the foot show: the manager's line, its head's models, its account's limits ----------
  const unit = (n) => (n >= 1_000_000 ? `${+(n / 1_000_000).toFixed(1)}M` : n >= 1_000 ? `${Math.round(n / 1_000)}k` : String(n));
  const bare = (v) => String(v ?? "").replace(/\[[^\]]*\]$/, "").toLowerCase();
  const lineOf = () => (st.m?.state === "live" ? st.m.line ?? null : null);
  /** A head's model row is the one the manager runs when its id or label is what its status line names (a [1m] hint aside). */
  const isModel = (row, ln) => Boolean(ln) && (bare(row.id) === bare(ln.model_id) || bare(row.label) === bare(ln.model));
  /** The status line's own colours: amber from 60%, red from 85%. */
  const toneOf = (pct) => (pct >= CRITICAL_PCT ? "hot" : pct >= WARN_PCT ? "warm" : "cool");
  /** He may steer it (a model, an effort, a compaction) only while it is idle and nothing is mid-flight. */
  const canSteer = () => st.m?.state === "live" && st.m.status === "idle" && !st.busy && !st.phase && !st.want && !st.ask;
  function resetWord(epochSeconds) {
    const mins = Math.round((epochSeconds * 1000 - Date.now()) / 60_000);
    if (!(mins > 0)) return "";
    if (mins < 60) return `in ${mins} min`;
    if (mins < 24 * 60) return `in ${Math.floor(mins / 60)} h${mins % 60 ? ` ${mins % 60} min` : ""}`;
    return `on ${new Date(epochSeconds * 1000).toLocaleString("en-US", { weekday: "short", hour: "numeric", minute: "2-digit" })}`;
  }
  /** The head's account and its windows, the one Sessions' choices read: of several accounts, the one the head sends as next. */
  async function readLimits() {
    const head = st.m?.head;
    if (!head || (st.limits?.head === head && Date.now() - st.limits.at < LIMITS_MS)) return;
    const res = await API.get("/api/accounts");
    if (head !== st.m?.head) return;
    const mine = res.ok ? (res.body?.accounts || []).filter((r) => (r.heads || []).includes(head)) : [];
    const next = mine.length > 1 ? (await API.get(`/api/auth/${encodeURIComponent(head)}/order`)).body?.next_target : null;
    const row = mine.find((r) => (r.account_labels?.[head] || r.label) === next) || mine.find((r) => r.credential_present) || mine[0] || null;
    st.limits = { head, row, at: Date.now() };
  }
  /** The model menu's rows: this head's, read when the menu opens and again after a minute. */
  async function readModels() {
    const head = st.m?.head;
    if (!head || (st.models?.head === head && Date.now() - st.models.at < MODELS_MS)) return;
    const res = await API.get("/api/models");
    if (head !== st.m?.head) return;
    st.models = { head, list: res.ok ? (res.body?.heads || []).find((x) => x.head === head)?.models || [] : null, at: Date.now() };
    if (st.menu === "model") drawFoot();
  }

  // ---------- drawing ----------
  function draw() {
    drawPill(); drawCard(); drawSheet();
    dock.classList.toggle("wide", st.open || st.card);
  }
  function drawPill() {
    const m = st.m, door1 = Boolean(m) && unasked(m) && !st.phase;
    door.hidden = !door1; field.hidden = door1;
    lamp.className = `cmp-lamp ${lampOf()}`;
    field.readOnly = st.busy || Boolean(st.phase) || !m;
    field.placeholder = st.phase ? phaseWord()
      : !m ? (st.gone === "absent" ? "This splice has no manager yet" : st.gone === "down" ? "splice is not answering" : "Reading the splice manager")
      : st.gone === "down" ? "splice is not answering"
      : m.state === "resumable" ? `Resume the splice manager. ${KEYS}` : `Message the splice manager. ${KEYS}`;
    const words = field.value.trim() !== "";
    send.hidden = door1;
    send.disabled = st.busy || Boolean(st.phase) || !m || (m.state === "live" && !words);
    send.textContent = st.busy ? "Sending" : m?.state === "resumable" && !words ? "Resume" : "Send";
    send.setAttribute("aria-busy", String(st.busy));
  }
  function headChip(x, on, role) {
    const why = x.ready ? "" : `<small>${h(x.why || "Not ready")}</small>`;
    return `<button type="button" class="cmp-hd" role="${role}" aria-checked="${on}" data-act="${role === "radio" ? "pick" : "head"}" data-head="${h(x.key)}"`
      + ` style="--c:${tint(x.key)}"${x.ready ? "" : " disabled"}><span class="cmp-blot" aria-hidden="true"></span><span>${h(x.label || x.key)}${why}</span></button>`;
  }
  function drawCard() {
    card.hidden = !st.card;
    if (!st.card) return;
    const list = heads(), ready = list.filter((x) => x.ready);
    if (!ready.some((x) => x.key === st.pick)) st.pick = ready.find((x) => x.key === st.m?.head)?.key ?? ready[0]?.key ?? null;
    const folder = st.m?.folder ? `<code>${h(home(st.m.folder))}</code>` : "splice's settings folder";
    const again = st.m?.state === "resumable";
    const html = `<h2 id="cmp-card-h">${again ? "Resume" : "Start"} the splice manager</h2>`
      + `<p>The splice manager is a Claude Code session that works in ${folder} and helps you run splice: heads, config, accounts and sessions. `
      + `It changes splice.toml only when you approve, and what you tell it goes to the head you pick here.</p>`
      + `<p class="cmp-cap" id="cmp-heads-l">${ready.length ? "Runs on" : "No head can run it right now"}</p>`
      + `<div class="cmp-heads" role="radiogroup" aria-labelledby="cmp-heads-l">${list.map((x) => headChip(x, x.key === st.pick, "radio")).join("")}</div>`
      + `<div class="cmp-go"><button type="button" class="act primary" data-act="start"${st.pick ? "" : " disabled"}>${again ? "Resume" : "Start"}</button>`
      + `<button type="button" class="act quiet" data-act="later">Not now</button></div>`;
    if (html !== drawn.card) { card.innerHTML = html; drawn.card = html; }
  }
  function drawSheet() {
    const shown = !sheet.hidden;
    sheet.hidden = !st.open;
    if (!st.open) return;
    // a sheet just opened starts at its newest words (a hidden log forgets its scroll), and one he was reading at the
    // foot stays there when a question opens under it and takes its room
    const stick = !shown || log.scrollHeight - log.scrollTop - log.clientHeight < FOLLOW_PX;
    where.textContent = st.m?.folder ? home(st.m.folder) : "";
    drawLog(); drawAsk(); drawMeters(); drawFoot();
    if (stick) log.scrollTop = log.scrollHeight;
  }
  /** One window as a bar and its figure: the status line's, in the same colours. */
  function meter(name, pct, tail, title) {
    const p = Math.min(100, Math.max(0, Math.round(pct)));
    return `<span class="cmp-meter ${toneOf(p)}" title="${h(title)}"><span class="cmp-mname">${h(name)}</span>`
      + `<span class="cmp-bar" aria-hidden="true"><b style="width:${p}%"></b></span><span class="cmp-mnum">${h(tail)}</span></span>`;
  }
  /** Over the foot: how full the manager's context is (with Compact), and how much of its account's 5 h and 7 d windows is spent. */
  function drawMeters() {
    const ln = lineOf(), ctx = ln?.context, parts = [];
    if (st.m?.state === "live") {
      if (ctx && ctx.window > 0) {
        parts.push(meter("Context", ctx.percent, `${unit(ctx.used)} / ${unit(ctx.window)} · ${ctx.percent}%`,
          "What the manager's conversation holds, of what its model can hold. It compacts itself near the top; Compact does it now."));
        parts.push(`<button type="button" class="act quiet small" data-act="compact"${canSteer() && ctx.used > 0 ? "" : " disabled"}
          title="Summarise the conversation so far to make room. It stays the same conversation.">Compact</button>`);
        if (ctx.cache != null) parts.push(`<span class="cmp-meter" title="How much of what the manager reads each turn the cache served."><span class="cmp-mname">Cache</span><span class="cmp-mnum">${ctx.cache}%</span></span>`);
      } else if (ln) parts.push(`<span class="cmp-meter" title="Nothing has been said yet."><span class="cmp-mname">Context</span><span class="cmp-mnum">empty</span></span>`);
      // splice holds the manager's last status-line reading in memory, and an idle session posts none until something happens in it
      else parts.push(`<span class="cmp-meter" title="splice reads this from the manager's status line, which reports when something happens in the session. Nothing has since splice started."><span class="cmp-mname">Context</span><span class="cmp-mnum">shows after its next turn</span></span>`);
      const row = st.limits?.row, who = [row?.display_name, row?.plan].filter(Boolean).join(" · ");
      const windows = [["5h", "5 hour", row?.five_hour_used_percent, row?.five_hour_reset_epoch_seconds],
        ["7d", "7 day", row?.seven_day_used_percent, row?.seven_day_reset_epoch_seconds]]
        // a window whose reset has gone by holds the ended window's figure, which Accounts drops too: it is not spent now
        .filter(([, , pct, reset]) => pct != null && !(reset && reset * 1000 <= Date.now()));
      if (windows.length) parts.push(`<span class="cmp-sep" aria-hidden="true"></span>`);
      for (const [name, long, pct, reset] of windows) {
        const back = reset ? resetWord(reset) : "";
        parts.push(meter(name, pct, `${Math.round(pct)}%`, `${who ? `${who}. ` : ""}The ${long} window is ${Math.round(pct)}% spent${back ? `; it resets ${back}` : ""}.`));
      }
    }
    const html = parts.join("");
    meters.hidden = !html;
    if (html !== drawn.meters) { meters.innerHTML = html; drawn.meters = html; }
  }
  /** One chip of the foot with the menu it opens above it. */
  const wrap = (kind, chip, items) => `<div class="cmp-menuwrap">${chip}<div class="cmp-menu" data-for="${kind}" role="menu"${st.menu === kind ? "" : " hidden"}>${items}</div></div>`;
  const chipOf = (kind, label, title, off, busy) => `<button type="button" class="cmp-hd cmp-ch" data-act="menu" data-menu="${kind}" aria-haspopup="menu" aria-expanded="${st.menu === kind}"`
    + ` title="${h(title)}"${off ? " disabled" : ""}${busy ? ' aria-busy="true"' : ""}><span>${h(label)}</span>${CARET}</button>`;
  const itemOf = (attrs, on, text, small) => `<button type="button" class="cmp-hd" role="menuitemradio" aria-checked="${on}" ${attrs}>`
    + `<span>${h(text)}${small ? `<small>${h(small)}</small>` : ""}</span></button>`;
  const REREAD = `<p class="cmp-menuline">The manager reads the whole conversation again after a change, once.</p>`;
  function modelItems(ln) {
    const read = st.models?.head === st.m?.head ? st.models : null;
    if (!read) return `<p class="cmp-menuline">Reading the models</p>`;
    if (!read.list) return `<p class="cmp-menuline">The models could not be read.</p>`;
    if (!read.list.length) return `<p class="cmp-menuline">This head lists no models.</p>`;
    return read.list.map((r) => itemOf(`data-act="model" data-id="${h(r.id)}" data-label="${h(r.label || r.id)}"`, isModel(r, ln), r.label || r.id,
      [r.context_window ? `${unit(r.context_window)} context` : "", r.pinned ? "default" : ""].filter(Boolean).join(" · "))).join("") + REREAD;
  }
  function drawFoot() {
    const m = st.m, key = m?.head, live = m?.state === "live", ln = lineOf(), w = st.want;
    const steer = canSteer();
    const head = key ? wrap("head", `<button type="button" class="cmp-hd" data-act="menu" data-menu="head" aria-haspopup="menu" aria-expanded="${st.menu === "head"}" style="--c:${tint(key)}"`
      + ` title="The head the manager runs on. Another one continues the same conversation there."${st.phase || !heads().length ? " disabled" : ""}>`
      + `<span class="cmp-blot" aria-hidden="true"></span><span>${h(labelOf(key))}</span>${CARET}</button>`,
      heads().map((x) => headChip(x, x.key === key, "menuitemradio")).join("")) : "";
    const why = steer ? "" : " Ready when the manager is idle.";
    const model = live ? wrap("model", chipOf("model", w?.kind === "model" ? w.label : ln?.model || "Model", `The model the manager answers with.${why}`, !steer, w?.kind === "model"), modelItems(ln)) : "";
    const effort = live ? wrap("effort", chipOf("effort", w?.kind === "effort" ? w.label : ln?.effort || "Effort", `How hard the manager thinks before it answers.${why}`, !steer, w?.kind === "effort"),
      EFFORTS.map(([v, l]) => itemOf(`data-act="effort" data-effort="${v}" data-label="${l}"`, ln?.effort === v, l, "")).join("") + REREAD) : "";
    const look = lampOf();
    const word = st.phase ? phaseWord() : live ? WORD[m.status] ?? "Ready"
      : m?.state === "resumable" ? "Not running. A message resumes it." : "";
    const state = `<span class="cmp-state ${look}"><span class="cmp-lamp ${look}" aria-hidden="true"><i></i></span><span>${h(word)}</span></span>`;
    const stop = live && m.status === "working"
      ? `<button type="button" class="act small" data-act="stop"${st.stopping ? ' disabled aria-busy="true"' : ""} title="End the turn it is working on. It keeps the conversation.">${st.stopping ? "Stopping" : "Stop"}</button>` : "";
    const open = st.sid ? `<a class="act quiet small cmp-open" href="sessions.html#${encodeURIComponent(st.sid)}" data-act="go">Open on Sessions</a>` : "";
    const html = head + model + effort + state + stop + open;
    if (html !== drawn.foot) { foot.innerHTML = html; drawn.foot = html; }
  }
  function drawAsk() {
    const a = st.ask;
    let html = "";
    if (a) {
      // what it will run, as its screen framed it, and the line above that says why, so nothing is approved blind
      const panel = a.panel || [], at = panel.findIndex((l) => l.framed);
      // The real screen's `asked` carries the menu's rule lines (a permission prompt reads "──── Bash command … ╌╌╌ cat ../x ╌╌╌ Do you want
      // to proceed?"), so the question is the panel's own line that ends in "?", and a screen with no framed command (the folder-trust
      // question) shows its panel lines, so what he is trusting is on screen when he presses.
      const ruled = (v) => oneLine(String(v ?? "").replace(/[─╌━┄┈═]+/g, " "));
      const asking = [...panel].reverse().find((l) => /\?\s*$/.test(l.text))?.text;
      const body = at >= 0 ? panel.filter((l) => l.framed) : panel.filter((l) => l.text !== asking).slice(0, 8);
      const run = body.length ? `<p class="cmp-run">${h(body.map((l) => l.text).join("\n"))}</p>` : "";
      const why = at > 0 ? panel[at - 1].text : "";
      const q = ruled(asking) || ruled(a.asked) || why || "The manager is asking you something.";
      const going = st.answering;
      // The press he is most likely to want is the first Yes: a menu whose first line is "No, exit" (the folder-trust
      // question) never paints the exit as its primary button.
      const yes = (a.choices || []).findIndex((c) => /^yes\b/i.test(c.label));
      // A Yes that also widens what the manager may do from now on (a whole folder, every edit, auto mode) reads quieter than
      // the plain Yes, so the plain one is the easy press.
      const broad = /auto mode|allow (all|reading|writing)|all edits|don.t ask again|this project|this session/i;
      const choices = (a.choices || []).map((c, i) => `<button type="button" class="act${i === yes ? " primary" : ""}${broad.test(c.label) ? " broad" : ""}" data-act="answer" data-choice="${c.choice}" data-label="${h(c.label)}"`
        + `${going ? " disabled" : ""}${going === c.choice ? ' aria-busy="true"' : ""}><kbd>${c.choice}</kbd>${h(c.label)}</button>`).join("");
      html = `<p class="cmp-q">${h(q)}</p>${run}`
        + (choices ? `<div class="cmp-choices">${choices}</div>` : `<p class="cmp-askline">Answer it on Sessions, where its terminal shows.</p>`);
    }
    ask.hidden = !html;
    if (html !== drawn.ask) { ask.innerHTML = html; drawn.ask = html; }
  }

  // ---------- the conversation ----------
  /** Light markdown, as the manager writes it: paragraphs, bullets, numbered lists, inline and fenced code, bold. */
  const inline = (s) => String(s).split(/(`[^`\n]+`)/).map((p, i) => (i % 2 ? `<code>${h(p.slice(1, -1))}</code>`
    : h(p).replace(/\*\*([^*\n]+)\*\*/g, "<strong>$1</strong>"))).join("");
  function md(text) {
    const out = [];
    let para = [], list = null, code = null;
    const endPara = () => { if (para.length) out.push(`<p>${para.map(inline).join("<br>")}</p>`); para = []; };
    const endList = () => { if (list) out.push(`<${list.tag}>${list.items.map((x) => `<li>${inline(x)}</li>`).join("")}</${list.tag}>`); list = null; };
    for (const ln of String(text).split("\n")) {
      if (code) {
        if (/^\s*```/.test(ln)) { out.push(`<pre><code>${h(code.join("\n"))}</code></pre>`); code = null; } else code.push(ln);
        continue;
      }
      if (/^\s*```/.test(ln)) { endPara(); endList(); code = []; continue; }
      const li = /^\s*(?:[-*•]|(\d+)[.)])\s+(.*)$/.exec(ln);
      if (li) {
        endPara();
        const tag = li[1] ? "ol" : "ul";
        if (list && list.tag !== tag) endList();
        (list ??= { tag, items: [] }).items.push(li[2]);
        continue;
      }
      if (!ln.trim()) { endPara(); endList(); continue; }
      if (list && /^\s{2,}\S/.test(ln)) { list.items[list.items.length - 1] += ` ${ln.trim()}`; continue; }
      endList();
      const hd = /^#{1,6}\s+(.*)$/.exec(ln);
      if (hd) { endPara(); out.push(`<p><strong>${inline(hd[1])}</strong></p>`); continue; }
      para.push(ln);
    }
    if (code) out.push(`<pre><code>${h(code.join("\n"))}</code></pre>`);
    endPara(); endList();
    return out.join("");
  }
  /** What a call was about, from its input as the transcript keeps it (Sessions' callSummary); a path in the manager's
   *  own folder reads from there. */
  function callSummary(m) {
    let input;
    try { input = JSON.parse(m.text); } catch { return oneLine(m.text); }
    if (!input || typeof input !== "object") return oneLine(m.text);
    const folder = st.m?.folder ? `${String(st.m.folder).replace(/\/$/, "")}/` : null;
    const file = (p) => (folder && p.startsWith(folder) ? p.slice(folder.length) : home(p));
    if (input.command) return oneLine(input.command);
    if (input.file_path || input.notebook_path) return file(input.file_path || input.notebook_path);
    if (input.pattern) return input.path ? `${input.pattern} in ${file(input.path)}` : input.pattern;
    if (input.questions?.[0]?.question) return input.questions[0].question;
    if (input.url) return input.url;
    if (input.to && input.message) return `to ${input.to}`;
    return oneLine(Object.values(input).find((v) => typeof v === "string" && v.trim()) ?? "");
  }
  /** The rows the sheet shows, each with a key, so a poll only adds what is new and never redraws what he is reading. */
  function rows() {
    const held = st.log, msgs = held?.messages || [], out = [];
    if (held?.earlier && st.sid) out.push({ key: "earlier", html: `<a class="cmp-older" href="sessions.html#${encodeURIComponent(st.sid)}" data-act="go">Earlier messages are on Sessions</a>` });
    const results = new Map(msgs.filter((m) => m.role === "tool" && m.tool_use_id).map((m) => [m.tool_use_id, m.text]));
    const moves = (held?.moves || []).slice().sort((a, b) => a.moved_at - b.moved_at);
    const exits = new Set(); // the /exit splice typed to end the client for a move: the move's mechanics, not his words
    for (const mv of moves) {
      const exit = msgs.find((m) => m.role === "user" && m.ts && m.ts >= mv.moved_at - 5_000 && m.ts - mv.moved_at <= EXIT_MS && oneLine(m.text) === "/exit");
      if (exit) exits.add(exit.index);
    }
    const joint = (mv) => ({ key: `mv${mv.moved_at}`, html: `<p class="cmp-moved"><span>Continued on</span><span class="cmp-blot" style="--c:${tint(mv.command)}" aria-hidden="true"></span>`
      + `<b>${h(headOf(mv.command)?.label || mv.command || "another head")}</b>${mv.thinking_dropped ? "<span>· Thinking not carried over</span>" : ""}</p>` });
    for (const m of msgs) {
      while (moves.length && m.ts && moves[0].moved_at <= m.ts) out.push(joint(moves.shift()));
      const key = `${m.index}`;
      if (m.role === "tool" || exits.has(m.index)) continue;
      if (m.tool) {
        const result = results.get(m.tool_use_id), sum = callSummary(m), open = st.opened.has(key) ? " open" : "";
        out.push({ key, html: `<details class="cmp-tool" data-k="${h(key)}"${open}><summary title="${h(`${m.tool} ${sum}`)}"><b>${h(m.tool)}</b><span>${h(sum)}</span></summary>`
          + `<pre>${h(result == null ? "No result yet" : result.trim() || "No output")}</pre></details>` });
        continue;
      }
      if (m.role === "system") { if (m.kind === "interrupted") out.push({ key, html: `<p class="cmp-moved"><span>Stopped</span></p>` }); continue; }
      if (!String(m.text || "").trim()) continue;
      if (m.role === "peer") { out.push({ key, html: `<div class="cmp-m peer"><span class="cmp-from">From ${h(m.from || "another session")}</span><div class="cmp-md">${md(m.text)}</div></div>` }); continue; }
      if (m.role === "user") { out.push({ key, html: `<div class="cmp-m you">${h(String(m.text).trim())}</div>` }); continue; }
      out.push({ key, html: `<div class="cmp-m ai"><div class="cmp-md">${md(m.text)}</div></div>` });
    }
    for (const mv of moves) out.push(joint(mv)); // a move after the newest message
    for (const t of st.typed) out.push({ key: `typed${t.at}`, html: `<div class="cmp-m you typed">${h(t.text)}</div>` });
    if (!out.length) {
      const words = !st.log ? (st.sid ? "Reading the conversation" : "")
        : st.log.off ? `Reading conversations is off, so the manager's words are not shown here. <a href="settings.html#data">Turn reading on</a>`
        : st.log.error ? `${h(st.log.error)}. It is read again in a moment.`
        : st.m?.state === "resumable" ? "The manager is not running. A message resumes it, and its conversation continues where it stopped."
        : "The manager is ready. Ask it about heads, config, accounts or sessions.";
      out.push({ key: "empty", html: `<p class="cmp-empty">${words}</p>` });
    }
    return out;
  }
  /** Only what changed is drawn again, from the first row that differs; the view follows only when he was at the foot. */
  function drawLog() {
    const next = rows(), old = drawn.rows;
    let i = 0;
    while (i < old.length && i < next.length && old[i].key === next[i].key && old[i].html === next[i].html) i++;
    if (i === old.length && i === next.length) return;
    const follow = log.scrollHeight - log.scrollTop - log.clientHeight < FOLLOW_PX || !old.length;
    const top = log.scrollTop;
    while (log.children.length > i) log.lastElementChild.remove();
    log.insertAdjacentHTML("beforeend", next.slice(i).map((r) => r.html).join(""));
    drawn.rows = next;
    if (follow) log.scrollTop = log.scrollHeight; else log.scrollTop = top;
  }

  // ---------- the line over the pill: what a start, a move or a send did, or why not, and the one way out ----------
  function say(next) {
    clearTimeout(lineTimer);
    st.line = next;
    if (next?.tone === "ok") lineTimer = setTimeout(() => { if (st.line === next) say(null); }, SAID_MS);
    line.hidden = !next;
    if (!next) { line.innerHTML = ""; return; }
    line.className = `cmp-line ${next.tone}${next.wrap ? " wrap" : ""}`;
    const words = next.html ?? h(next.text);
    line.innerHTML = `<span class="cmp-mark" aria-hidden="true"></span><span class="cmp-text" title="${h(next.title || next.text || "")}">${words}`
      + `${next.held != null ? ` <q>${h(next.held)}</q>` : ""}</span>`
      + (next.acts?.length ? `<span class="cmp-acts">${next.acts.map((x, i) => `<button type="button" class="act small${x.primary ? " primary" : " quiet"}" data-i="${i}">${h(x.word)}</button>`).join("")}</span>` : "");
  }

  // ---------- acts ----------
  function openSheet() {
    if (!st.m || st.m.state === "off") return;
    st.open = true; st.card = false;
    draw(); place();
    tick();
  }
  function closeSheet() {
    if (!st.open && !st.menu) return;
    st.open = false; st.menu = null;
    draw(); place();
  }
  function openCard() {
    if (!st.m) return;
    st.card = true; st.open = false; st.menu = null;
    draw(); place();
    (card.querySelector('.cmp-hd[aria-checked="true"]') || card.querySelector('[data-act="start"]'))?.focus();
  }
  function notNow() {
    st.notNow = true; st.card = false;
    draw(); place();
    door.focus();
  }
  async function start(head, kind) {
    if (st.phase) return false;
    st.phase = { kind, head }; st.card = false; draw(); place();
    // his words fill the field, so the field cannot say what is happening: the line over the pill does, unless the sheet does
    const progress = !st.open && field.value.trim() ? { tone: "busy", text: phaseWord() } : null;
    say(progress);
    const res = await API.post("/api/manager/start", head ? { head } : {});
    st.phase = null;
    if (progress && st.line === progress) say(null);
    const up = res.ok && res.body?.state;
    if (up) { st.gone = null; apply(res.body); } else startRefused(res, head, kind);
    draw(); place();
    if (up && kind === "start" && st.m.state === "live") { openSheet(); field.focus(); }
    tick();
    return up && st.m.state === "live";
  }
  function startRefused(res, head, kind) {
    const why = res.body?.error || "", label = labelOf(head);
    const again = { word: "Try again", primary: true, run: () => start(head, kind) };
    const other = { word: "Choose another head", run: openCard };
    const plain = (text, acts = [again]) => say({ tone: "refused", text, title: why || text, acts });
    switch (res.body?.reason) {
      case "not_installed":
        say({ tone: "refused", wrap: true, text: `${label} is not installed as a command.`, title: why,
          html: `${h(label)} is not installed as a command. Run <code>splice install --all</code> in a terminal, then try again.`, acts: [other, again] });
        return;
      case "head_unknown": plain(`splice has no head called ${head || "that"}. Choose another one.`, [{ ...other, primary: true }]); return;
      case "launch_failed": plain(`The manager did not start on ${label}${why ? `: ${why}` : ""}.`, [other, again]); return;
      case "folder_missing": plain(`splice could not open its settings folder${st.m?.folder ? ` ${home(st.m.folder)}` : ""}${why ? `: ${why}` : ""}.`); return;
      default:
        if (res.status === 503) plain("splice has no terminal to run the manager in. It runs sessions in tmux.");
        else if (res.status === 404) plain("This splice has no manager yet. It comes with a newer splice.", []);
        else if (res.status === 0) plain("splice is not answering. Nothing was started.");
        else plain(`The manager did not start${why ? `: ${why}` : `. splice answered ${res.status}`}.`);
    }
  }
  /** The head chip's menu: Continue on. The conversation stays on screen through the move and goes on where it was. */
  async function move(key) {
    st.menu = null;
    const m = st.m;
    if (!m || st.phase || key === m.head) { draw(); return; }
    const live = m.state === "live";
    if (live) { st.phase = { kind: "move", head: key }; say(null); }
    draw();
    const res = await API.post("/api/manager/head", { head: key });
    st.phase = null;
    if (res.ok && res.body?.state) {
      apply(res.body);
      if (!live) say({ tone: "ok", text: `The manager resumes on ${labelOf(key)} next time.` });
    } else moveRefused(res, key, m.head);
    draw();
    tick();
  }
  function moveRefused(res, key, from) {
    const why = res.body?.error || "";
    const plain = (text) => say({ tone: "refused", text, title: why || text });
    switch (res.body?.reason) {
      case "waiting": plain("The manager is waiting on an answer. Answer it, then move it."); return;
      case "same_head": plain(`The manager already runs on ${labelOf(key)}.`); return;
      case "draft": plain("The manager's prompt holds words that were not sent. Send or clear them, then move it."); return;
      case "not_installed":
        say({ tone: "refused", wrap: true, text: `${labelOf(key)} is not installed as a command.`, title: why,
          html: `${h(labelOf(key))} is not installed as a command. Run <code>splice install --all</code> in a terminal, then try again.` });
        return;
      default:
        plain(res.status === 0 ? `splice is not answering. The manager stays on ${labelOf(from)}.`
          : `The manager did not move${why ? `: ${why}` : ""}. It stays on ${labelOf(from)}.`);
    }
  }
  /** His message, whole, to the manager. A manager that is not running resumes first; words that did not go through stay
   *  in the field. */
  async function submit(clear = false) {
    if (st.busy || st.phase || !st.m) return;
    const m = st.m, text = field.value.trim();
    if (unasked(m)) { openCard(); return; }
    if (m.state === "resumable") {
      if (await start(m.head, "resume") && text) await submit(clear);
      return;
    }
    if (!text) { field.focus(); return; }
    if (st.ask?.choices?.length) { asking(); return; }
    if (!(await deliver(text, clear))) return;
    st.typed.push({ text, at: Date.now() });
    st.recall = -1;
    field.value = ""; fit();
    lamp.classList.remove("sent"); void lamp.offsetWidth; lamp.classList.add("sent");
    setTimeout(() => lamp.classList.remove("sent"), 900);
    openSheet();
    field.focus();
  }
  /** Types [text] into the manager's terminal as he would. False, with the reason on the line, when it did not go. */
  async function deliver(text, clear = false) {
    st.busy = true; say(null); draw();
    const id = st.sid;
    // a manager that began asking since the last read shows a menu, which typed words and their Enter would answer
    const screen = await API.get(route(id, "screen"));
    if (screen.ok && screen.body?.choices?.length) { st.busy = false; st.ask = screen.body; asking(); draw(); return false; }
    const res = await API.post(route(id, "say"), clear ? { text, clear: true } : { text });
    st.busy = false;
    if (res.ok) return true;
    refused(res);
    draw();
    return false;
  }
  /** A model or an effort is Claude Code's own /model or /effort, typed as he would: the session, its transcript and its cache
   *  stay, and the status line says when it took. */
  async function steer(kind, value, label) {
    st.menu = null;
    if (!canSteer()) { draw(); return; }
    if (!(await deliver(kind === "model" ? `/model ${value}` : `/effort ${value}`))) return;
    st.want = { kind, value, label, at: Date.now() };
    draw();
    setTimeout(tick, WANT_POLL_MS);
  }
  async function compact() {
    if (!canSteer() || !(await deliver("/compact"))) return;
    say({ tone: "ok", text: "Asked the manager to compact its conversation." });
    draw();
    setTimeout(tick, WANT_POLL_MS);
  }
  /** Sessions' own Stop: it ends the turn and leaves the conversation waiting for his next message. */
  async function stop() {
    if (!st.sid || st.stopping || st.m?.status !== "working") return;
    st.stopping = true; say(null); draw();
    const res = await API.post(route(st.sid, "stop"));
    if (!res.ok) {
      st.stopping = false;
      say({ tone: "refused", text: res.status === 0 ? "splice is not answering. Nothing was stopped." : `Not stopped: ${res.body?.error || `splice answered ${res.status}`}.`,
        acts: [{ word: "Open on Sessions", run: go }] });
    }
    draw();
    setTimeout(tick, 600);
  }
  /** Up and Down walk back through what he has said to the manager, as a terminal's history does; only from an empty field. */
  function recalled(step) {
    const said = [...(st.log?.messages || []).filter((m) => m.role === "user").map((m) => String(m.text || "").trim()), ...st.typed.map((t) => t.text)]
      .filter((t, i, all) => t && oneLine(t) !== "/exit" && t !== all[i - 1]);
    if (!said.length || (st.recall < 0 && (field.value !== "" || step < 0))) return false;
    st.recall = Math.min(Math.max(st.recall + step, -1), said.length - 1);
    field.value = st.recall < 0 ? "" : said[said.length - 1 - st.recall];
    fit(); drawPill();
    field.setSelectionRange(field.value.length, field.value.length);
    return true;
  }
  function asking() {
    openSheet();
    say({ tone: "held", text: "The manager is asking you something. Answer it above, then send." });
  }
  /** Why /say did not type, in plain words, with the way out it offers (SessionDrive's refusals, by their reason). */
  function refused(res) {
    const why = res.body?.error || "";
    const plain = (text, acts) => say({ tone: "refused", text, title: why || text, acts });
    const look = { word: "Open on Sessions", run: go };
    switch (res.body?.reason) {
      case "draft":
        say({ tone: "held", text: "The manager's prompt already holds", held: oneLine(res.body.draft),
          acts: [{ word: "Cancel", run: () => { say(null); field.focus(); } }, { word: "Clear and send", primary: true, run: () => submit(true) }] });
        return;
      case "closed":
      case "pane_gone":
        // its terminal is gone: the manager is resumable, and the next send resumes it before it types
        st.m = { ...st.m, state: "resumable", status: null };
        plain("The manager's terminal has closed. Nothing was typed. Send again to resume it.");
        setTimeout(tick, 400);
        return;
      case "not_ours": plain("splice did not open the manager's terminal, so it cannot type there. Nothing was typed.", [look]); return;
      case "pane_taken": plain("The manager's terminal runs something else now. Nothing was typed.", [look]); return;
      case "no_terminal": plain("splice has no terminal to type into yet. Nothing was typed."); return;
      case "refused": plain("The terminal did not take it. Look at the manager before sending again.", [look]); return;
      default:
        plain(res.status === 0 ? "splice is not answering. Nothing was typed."
          : why ? `Not typed: ${why}.` : `splice answered ${res.status}. Look at the manager before sending again.`, [look]);
    }
  }
  /** Press the numbered choice he picked, as Sessions' needs-you card does; the label guards against a menu that moved. */
  async function answer(choice, label) {
    if (st.answering || !st.sid) return;
    st.answering = choice; say(null); draw();
    const res = await API.post(route(st.sid, "answer"), { choice, label });
    st.answering = null;
    if (res.ok) st.ask = null;
    else if (res.body?.reason === "screen_moved") { say({ tone: "refused", text: "The menu changed before your press landed. Nothing was pressed." }); await readScreen(); }
    else say({ tone: "refused", text: res.status === 0 ? "splice is not answering. Nothing was pressed." : `Not pressed: ${res.body?.error || `splice answered ${res.status}`}.`, acts: [{ word: "Open on Sessions", run: go }] });
    draw();
    setTimeout(tick, 600);
  }
  function go() {
    say(null); closeSheet();
    const to = `sessions.html#${encodeURIComponent(st.sid || "")}`;
    if (typeof CONSOLE !== "undefined") CONSOLE.navigate(to); else location.href = to;
  }
  function fit() {
    field.style.height = "auto";
    field.style.height = `${field.scrollHeight}px`;
  }
  /** Bottom centre, unless a pane that fills the screen stands on the right under where it would sit: then it is centred
   *  under the column left of that pane, so the pane's own field and buttons are never under it (Sessions at 1440). */
  function place() {
    const rem = parseFloat(getComputedStyle(document.documentElement).fontSize) || 12;
    const vw = document.documentElement.clientWidth, w = Math.min((st.open || st.card ? 58 : 50) * rem, vw - 2 * rem);
    const pane = [...document.querySelectorAll("main.page .pane")].map((el) => el.getBoundingClientRect())
      .find((r) => r.width > 0 && r.height > innerHeight / 2 && r.left > vw / 4 && r.left < (vw + w) / 2 + rem);
    let left = "", width = "";
    if (pane) {
      const main = document.querySelector("main.page");
      const from = main.getBoundingClientRect().left + (parseFloat(getComputedStyle(main).paddingLeft) || 0);
      const room = pane.left - from - 1.5 * rem;
      if (room >= 24 * rem) { width = `${Math.round(Math.min(room, w))}px`; left = `${Math.round(from + room / 2)}px`; }
    }
    if (dock.style.left !== left) dock.style.left = left;
    if (dock.style.width !== width) dock.style.width = width;
  }

  // ---------- mounting ----------
  function mount() {
    dock = document.createElement("div");
    dock.className = "cmp";
    dock.setAttribute("role", "region");
    dock.setAttribute("aria-label", "The splice manager");
    dock.innerHTML = `<section class="cmp-card" role="dialog" aria-labelledby="cmp-card-h" hidden></section>`
      + `<section class="cmp-sheet" aria-label="Conversation with the splice manager" hidden>`
      + `<header class="cmp-sh"><b>splice manager</b><span class="cmp-where"></span><button type="button" class="cmp-x" data-act="close" aria-label="Close the conversation" title="Close (Esc)">${X}</button></header>`
      + `<div class="cmp-log" role="log" aria-live="polite" tabindex="0"></div>`
      + `<div class="cmp-ask" role="group" aria-label="The manager asks" hidden></div>`
      + `<div class="cmp-meters" role="group" aria-label="Context and limits" hidden></div>`
      + `<footer class="cmp-foot"></footer></section>`
      + `<div class="cmp-line" role="status" aria-live="polite" hidden></div>`
      + `<form class="cmp-pill" novalidate><span class="cmp-lamp off" aria-hidden="true"><i></i></span>`
      + `<button type="button" class="cmp-door" hidden><span>Start the splice manager</span><kbd>${KEYS}</kbd></button>`
      + `<textarea class="cmp-field" rows="1" aria-label="Message the splice manager" autocomplete="off"></textarea>`
      + `<button type="submit" class="act primary cmp-send" disabled>Send</button></form>`;
    card = dock.querySelector(".cmp-card"); sheet = dock.querySelector(".cmp-sheet");
    where = dock.querySelector(".cmp-where"); log = dock.querySelector(".cmp-log");
    ask = dock.querySelector(".cmp-ask"); meters = dock.querySelector(".cmp-meters"); foot = dock.querySelector(".cmp-foot"); line = dock.querySelector(".cmp-line");
    lamp = dock.querySelector(".cmp-pill > .cmp-lamp"); door = dock.querySelector(".cmp-door");
    field = dock.querySelector(".cmp-field"); send = dock.querySelector(".cmp-send");
    document.body.append(dock);

    dock.querySelector("form").addEventListener("submit", (e) => { e.preventDefault(); submit(); });
    door.addEventListener("click", openCard);
    field.addEventListener("input", () => { st.recall = -1; fit(); drawPill(); });
    const reveal = () => { if (!st.open && st.sid && st.m && st.m.state !== "off") openSheet(); };
    field.addEventListener("focus", reveal);
    field.addEventListener("click", reveal);
    field.addEventListener("keydown", (e) => {
      if ((e.key === "ArrowUp" || e.key === "ArrowDown") && !(e.shiftKey || e.altKey || e.ctrlKey || e.metaKey || e.isComposing)) {
        if (recalled(e.key === "ArrowUp" ? 1 : -1)) e.preventDefault();
        return;
      }
      if (e.key !== "Enter" || e.shiftKey || e.altKey || e.isComposing) return;
      e.preventDefault();
      submit();
    });
    dock.addEventListener("click", (e) => {
      const act = e.target.closest("[data-act]"), lineAct = e.target.closest(".cmp-line button[data-i]");
      if (lineAct) { st.line?.acts?.[+lineAct.dataset.i]?.run(); return; }
      if (!act) return;
      switch (act.dataset.act) {
        case "pick": st.pick = act.dataset.head; drawCard(); card.querySelector(`.cmp-hd[data-head="${CSS.escape(st.pick)}"]`)?.focus(); return;
        case "start": if (st.pick) start(st.pick, st.m?.state === "resumable" ? "resume" : "start"); return;
        case "later": notNow(); return;
        case "close": closeSheet(); field.focus(); return;
        case "menu": {
          const kind = act.dataset.menu;
          st.menu = st.menu === kind ? null : kind;
          if (st.menu === "model") readModels();
          drawFoot();
          if (st.menu) { const own = foot.querySelector(`.cmp-menu[data-for="${kind}"]`); (own?.querySelector('[aria-checked="true"]:not(:disabled)') || own?.querySelector(".cmp-hd:not(:disabled)"))?.focus(); }
          return;
        }
        case "head": move(act.dataset.head); return;
        case "model": steer("model", act.dataset.id, act.dataset.label); return;
        case "effort": if (act.getAttribute("aria-checked") !== "true") steer("effort", act.dataset.effort, act.dataset.label); else { st.menu = null; drawFoot(); } return;
        case "compact": compact(); return;
        case "stop": stop(); return;
        case "answer": answer(Number(act.dataset.choice), act.dataset.label); return;
        case "go": e.preventDefault(); go(); return;
        default:
      }
    });
    // a tool call he opened stays open through every redraw
    log.addEventListener("toggle", (e) => { const k = e.target.dataset?.k; if (k) { if (e.target.open) st.opened.add(k); else st.opened.delete(k); } }, true);
    // its keys are its own: Escape here never also closes what the page has open (Sessions' in-prompt card, a menu)
    dock.addEventListener("keydown", (e) => {
      e.stopPropagation();
      if (e.key !== "Escape") return;
      e.preventDefault();
      if (st.menu) { const was = st.menu; st.menu = null; drawFoot(); foot.querySelector(`[data-menu="${was}"]`)?.focus(); return; }
      if (st.open) { closeSheet(); return; }
      if (st.card) { notNow(); return; }
      if (st.line?.acts?.length) { say(null); return; }
      document.activeElement?.blur();
    });
    document.addEventListener("pointerdown", (e) => { if ((st.open || st.menu) && !dock.contains(e.target)) closeSheet(); }, true);
    addEventListener("keydown", (e) => {
      if (!(e.ctrlKey || e.metaKey) || e.altKey || e.shiftKey || String(e.key).toLowerCase() !== "k") return;
      if (locked() || document.querySelector(".scrim")) return;
      e.preventDefault(); e.stopPropagation();
      if (!door.hidden) { openCard(); return; }
      field.focus();
      reveal();
    }, true);
    // a page opens or closes its pane on its own clock, so where the pill stands is looked at a little more than once a second
    setInterval(() => { if (!locked()) place(); }, 700);
    addEventListener("resize", place);
    document.addEventListener("visibilitychange", () => { if (!document.hidden) tick(); });
    draw();
    place();
    tick();
  }
  // after console.js's own DOMContentLoaded (it loads first), so the router never counts the dock among the page's nodes
  if (document.readyState === "loading") addEventListener("DOMContentLoaded", mount); else mount();
})();
