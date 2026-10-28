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
  // A meter turns the limit red where Accounts' own row does, at the limit (kit.js DRAW.meter "full"): at 85 the same
  // 7-day window read red here and in its provider's colour on Accounts.
  const LIMITS_MS = 30_000, MODELS_MS = 60_000, WANT_MS = 12_000, WANT_POLL_MS = 1_200, CRITICAL_PCT = 100;
  const TINTS_MS = 300_000, NAMES_MS = 120_000;   // how long the head colours and the session names are kept
  const GROUP_MS = 180_000;                       // a break in the talk this long starts a new group, with its time over it
  const COMPACT_MS = 120_000;                     // how long the context meter waits for a compaction it asked for
  const BODY_LINES = 6, RESULT_LINES = 10, CHIPS_SHOWN = 4;  // what a long message, a result and a burst show before "+N"
  // the levels Claude Code's /effort takes (code.claude.com/docs/en/model-config); a model that has fewer falls back to the highest below
  const EFFORTS = [["low", "Low"], ["medium", "Medium"], ["high", "High"], ["xhigh", "Extra high"], ["max", "Max"]];
  const MAC = /Mac|iPhone|iPad/.test(navigator.platform || navigator.userAgent || "");
  const KEYS = MAC ? "⌘K" : "Ctrl+K";
  const WORD = { working: "Working", waiting: "Needs you", idle: "Ready" };
  // A command that cannot carry the manager now, in the console's own state words for the daemon's reasons
  // (ManagerWiring.why). Each state is said once, over the commands in it, never under every one of them.
  const HEAD_STATES = [[/^not installed/i, "Not installed"], [/quota/i, "At limit"], [/not answering/i, "Not answering"], [/stopped/i, "Stopped"]];
  const headState = (x) => HEAD_STATES.find(([re]) => re.test(x.why || ""))?.[1] ?? "Not ready";
  // WHAT A CHANGE OF MODEL OR EFFORT COSTS, DRAWN AS THE FIGURE IT RE-READS (Oct 28): said once at the foot of the menu
  // that makes it, with the size of the conversation the manager's own status line reports, never a guess at one
  const rereadLine = (ctx) => `<p class="cmp-menuline cmp-reread">${ICON.retries}`
    + (ctx?.used > 0 ? `<span>Re-reads <b>${DRAW.big(ctx.used)}</b> tokens once</span>` : `<span>Re-reads the conversation once</span>`) + `</p>`;
  // the command that installs every command splice runs on, which a "Not installed" group carries beside it
  const INSTALL_LINE = "splice install --all";
  // what the field is for, and the short form for a bar standing in a column too narrow for it (fitBar)
  const HINT = "Message the splice manager", SHORT_HINT = "Message the manager";
  const X = `<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.4" aria-hidden="true"><path d="M6 6l12 12M18 6L6 18"/></svg>`;
  const CARET = `<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.4" aria-hidden="true"><path d="M6 15l6-6 6 6"/></svg>`;
  // WHAT A TOOL CALL DID, IN HIS WORDS AND WITH A SHAPE (Oct 26). Every call drew the same hairline chip labelled with
  // Claude Code's own identifier ("SendMessage", "ListAgents"), so a burst of ten reads as ten identical rows and the
  // reader cannot tell a message from a file read, or a failure from a success. Each kind now has one glyph, one word
  // the manager's own user would say, and one plural for a burst's count.
  const glyph = (d) => G(d, 'aria-hidden="true"');
  const KIND = {
    send: { word: "Sent", many: "sent", icon: ICON.send },
    read: { word: "Read", many: "read", icon: glyph('<path d="M5 4.5h9l5 5V19.5H5z"/><path d="M14 4.5v5h5"/>') },
    write: { word: "Wrote", many: "written", icon: glyph('<path d="M4.5 19.5h4l10-10-4-4-10 10z"/><path d="M14.5 5.5l4 4"/>') },
    run: { word: "Ran", many: "run", icon: glyph('<path d="M4.5 5.5h15v13h-15z"/><path d="M8 10l2.5 2.5L8 15M13 15h3"/>') },
    look: { word: "Searched", many: "searched", icon: glyph('<circle cx="11" cy="11" r="6"/><path d="M15.5 15.5L20 20"/>') },
    web: { word: "Fetched", many: "fetched", icon: glyph('<circle cx="12" cy="12" r="8.5"/><path d="M3.5 12h17M12 3.5c2.4 2.4 2.4 14.2 0 17-2.4-2.8-2.4-14.6 0-17z"/>') },
    sessions: { word: "Listed sessions", many: "listed", icon: ICON.team },
    helper: { word: "Asked a helper", many: "asked", icon: glyph('<circle cx="12" cy="8" r="3.2"/><path d="M5.5 19.5c1-3.6 3.4-5.5 6.5-5.5s5.5 1.9 6.5 5.5"/>') },
    note: { word: "Noted", many: "noted", icon: glyph('<path d="M5.5 4.5h13v15h-13z"/><path d="M8.5 9h7M8.5 12.5h7M8.5 16h4"/>') },
    // not a kind of call: the folder a file chip and the first-run card's first tile stand on
    folder: { icon: glyph('<path d="M3.5 7h6l2 2.2h9v9.8h-17z"/>') },
    step: { word: "Step", many: "done", icon: glyph('<circle cx="12" cy="12" r="3"/>') },
  };
  const TOOL_KIND = {
    SendMessage: "send", Read: "read", NotebookRead: "read", Glob: "read", Edit: "write", Write: "write",
    NotebookEdit: "write", MultiEdit: "write", Bash: "run", BashOutput: "run", KillShell: "run", Grep: "look",
    WebFetch: "web", WebSearch: "web", ListAgents: "sessions", SendMessageToAgent: "send", Task: "helper",
    TodoWrite: "note", ExitPlanMode: "note", AskUserQuestion: "note",
  };
  // Nothing on the wire says a call failed: the served result carries no flag (SessionTranscripts.kt holds name, result
  // and id alone), so these are the shapes a failure actually takes in the manager's own log, and nothing else is
  // called one. A request for the flag is filed; until it lands these four cover the failures the log holds.
  const FAILED = [/^\s*\{\s*"success"\s*:\s*false/, /<tool_use_error>/, /^Auto mode could not evaluate this action/m, /^\s*Exit code [1-9]/m];
  const failedResult = (text) => typeof text === "string" && FAILED.some((r) => r.test(text));

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
    tints: null,                // each head's provider, learned from the accounts { map, at }: the key cannot be guessed
    names: null, namesOut: false, // the sessions a message was sent to, by address and by name { byAddr, byName, at }
    wantNames: false,           // the drawn log holds a recipient or a sender whose session is not named yet
    unread: 0,                  // replies that landed while he was reading further up
    still: false,               // the next drawing of the log is older messages opening above: nothing in it eases in
    older: false,               // Earlier messages was pressed and the page before the oldest one is being read
    slow: false,                // the first read of the manager has been out longer than a read should take
    flash: null,                // { what, cls, at } the one place a press landed or was refused, for one turn
    compacting: null,           // { at, was } Compact was pressed: the context meter is pending until the reading drops
    busy: false, line: null, reading: false, retrying: false, timer: 0, opened: new Set(),
    narrow: false, shortHint: false, keyW: 0,  // the room the bar has in the column it stands in (fitBar)
    sizing: false,              // the bar is folding or opening, so its width is a passing one
  };
  let dock, dim, tab, tabLamp, card, sheet, log, ask, meters, foot, line, pill, lamp, door, said, field, send, lineTimer = 0;
  const drawn = { card: "", ask: "", meters: "", foot: "", head: "", rows: [] };

  // ---------- reading ----------
  const heads = () => st.m?.heads || [];
  const headOf = (key) => heads().find((x) => x.key === key) ?? null;
  const labelOf = (key) => headOf(key)?.label || key || "a command";
  /** A head's colour is its provider's (kit.js colorOf).
   *
   *  THE PROVIDER IS LEARNED, NOT GUESSED (Oct 26). /api/manager names a head by key alone, so this read the key: the
   *  first token that matched a family won, which made every one of his claude-* heads draw in Claude's colour
   *  (claude-grok is Grok's, claude-kimi is Kimi's) and left claudex, his own manager's head, in the muted grey of a
   *  provider splice has no colour for. The accounts know: each row carries its provider and the heads it serves
   *  (readTints). Until that read lands the key is read from its LAST token, which is the one that names the provider
   *  in his own naming, and a key that names none draws in no colour rather than in another provider's. */
  function tint(key) {
    try {
      const learned = st.tints?.map?.[key];
      if (learned) return colorOf(learned);
      const hd = headOf(key);
      let fam = hd?.provider ? PROVIDER_FAMILY[hd.provider] ?? hd.provider : null;
      for (const t of String(key || "").toLowerCase().split(/[^a-z0-9]+/).reverse()) {
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
    settleCompact(m);
    settleWant();
  }
  /** A compaction is done when the context reading it was asked to shrink has shrunk, and the pending mark comes off
   *  the meter. Two minutes with no drop stops the mark whatever the manager is doing, so it never stays on forever. */
  function settleCompact(m) {
    const c = st.compacting;
    if (!c) return;
    const pct = m.state === "live" ? m.line?.context?.percent ?? null : null;
    if (Date.now() - c.at > COMPACT_MS || (pct != null && c.was != null && pct < c.was)) st.compacting = null;
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
    // said in a few words, and shaken on the chip it concerns; its terminal, on Sessions, shows what held it
    mark('.cmp-foot [data-menu="steer"]', "shook");
    say({ tone: "refused", text: `Not switched to ${w.label}`, acts: [{ word: "Open on Sessions", run: go }] });
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
    st.log = { sid: id, messages: res.body.messages, earlier: res.body.earlier || null, moves: res.body.moves || [] };
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
  const bare = (v) => String(v ?? "").replace(/\[[^\]]*\]$/, "").toLowerCase();
  const lineOf = () => (st.m?.state === "live" ? st.m.line ?? null : null);
  /** A head's model row is the one the manager runs when its id or label is what its status line names (a [1m] hint aside). */
  const isModel = (row, ln) => Boolean(ln) && (bare(row.id) === bare(ln.model_id) || bare(row.label) === bare(ln.model));
  /** He may steer it (a model, an effort, a compaction) only while it is idle and nothing is mid-flight. */
  const canSteer = () => st.m?.state === "live" && st.m.status === "idle" && !st.busy && !st.phase && !st.want && !st.ask;
  /** When a window comes back, in Accounts' own words and times: "Resets 4:50 PM" today, "Resets Mon 4:50 AM" this
   *  week, "Resets Nov 3" past it, the instant rounded once to the minute so every view of one reset reads alike. */
  function resetWord(epochSeconds) {
    const ms = epochSeconds * 1000;
    if (!(ms > Date.now())) return "";
    const d = new Date(Math.round(ms / 60_000) * 60_000);
    const days = Math.round((new Date(d).setHours(0, 0, 0, 0) - new Date().setHours(0, 0, 0, 0)) / 864e5);
    const at = days === 0 ? clock(d) : days < 7 ? `${d.toLocaleDateString("en-US", { weekday: "short" })} ${clock(d)}`
      : d.toLocaleDateString("en-US", { month: "short", day: "numeric" });
    return `Resets ${at}`;
  }
  /** The head's account and its windows, the one Sessions' choices read: of several accounts, the one the head sends as next.
   *  The same read teaches every head its provider, which no other route tells the composer (tint). */
  async function readLimits() {
    const head = st.m?.head;
    if (!head || (st.limits?.head === head && Date.now() - st.limits.at < LIMITS_MS)) return;
    const res = await API.get("/api/accounts");
    learnTints(res);
    if (head !== st.m?.head) return;
    const mine = res.ok ? (res.body?.accounts || []).filter((r) => (r.heads || []).includes(head)) : [];
    const next = mine.length > 1 ? (await API.get(`/api/auth/${encodeURIComponent(head)}/order`)).body?.next_target : null;
    const row = mine.find((r) => (r.account_labels?.[head] || r.label) === next) || mine.find((r) => r.credential_present) || mine[0] || null;
    st.limits = { head, row, at: Date.now() };
  }
  /** Which provider serves each head, as the accounts say it: every row carries its provider and the heads it serves. */
  function learnTints(res) {
    if (!res.ok) return;
    const map = {};
    for (const row of res.body?.accounts || []) {
      const fam = PROVIDER_FAMILY[row.provider] ?? (COLORS[row.provider] ? row.provider : null);
      if (!fam) continue;
      for (const key of row.heads || []) map[key] ??= fam;
    }
    st.tints = { map, at: Date.now() };
  }
  /** Read once where a head chip is about to be drawn, so the chip opens in its provider's colour rather than changing
   *  under him a read later. The windows' own read refreshes it. */
  async function readTints() {
    if (st.tints && Date.now() - st.tints.at < TINTS_MS) return;
    st.tints = { map: st.tints?.map ?? {}, at: Date.now() };  // one read at a time, and never a second on a refusal
    learnTints(await API.get("/api/accounts"));
    draw();
  }
  /** THE SESSIONS A MESSAGE WENT TO, BY NAME (Oct 26): the log showed a send as "to uds:/run/user/1000/cc-socks/…sock",
   *  the socket splice types into, while the peer block right above it carried that session's name. /api/sessions is the
   *  one place that maps an address to a session, so it is read ONCE, and only when a row the sheet is drawing needs it:
   *  until it lands the row says what it did and how it went, and the recipient's chip simply is not there yet. */
  async function readNames() {
    if (st.namesOut || (st.names && Date.now() - st.names.at < NAMES_MS)) return;
    st.namesOut = true;
    const res = await API.get("/api/sessions");
    st.namesOut = false;
    if (!res.ok) { st.names = { byAddr: {}, byName: {}, at: Date.now() }; return; }
    const byAddr = {}, byName = {};
    for (const row of res.body?.sessions || []) {
      const who = { id: row.session_id, name: row.name || row.title || null };
      if (!who.id) continue;
      if (row.address) byAddr[row.address] = who;
      if (row.name) byName[row.name] = who;
    }
    st.names = { byAddr, byName, at: Date.now() };
    drawLog();
  }
  /** The session a `to` or a `from` names, when splice knows it: a name, and the door to its own page. */
  function whoIs(to) {
    const text = String(to ?? "").trim();
    if (!text || !st.names) { st.wantNames = Boolean(text); return null; }
    const found = st.names.byAddr[text] ?? st.names.byName[text] ?? null;
    if (!found) return null;
    return { id: found.id, name: found.name || text };
  }
  /** The model menu's rows: this head's, read when the menu opens and again after a minute. */
  async function readModels() {
    const head = st.m?.head;
    if (!head || (st.models?.head === head && Date.now() - st.models.at < MODELS_MS)) return;
    const res = await API.get("/api/models");
    if (head !== st.m?.head) return;
    st.models = { head, list: res.ok ? (res.body?.heads || []).find((x) => x.head === head)?.models || [] : null, at: Date.now() };
    if (st.menu === "steer") drawFoot();
  }

  // ---------- drawing ----------
  function draw() {
    drawPill(); drawCard(); drawSheet();
    dock.classList.toggle("wide", st.open || st.card);
    // THE PAGE DIMS BEHIND AN OPEN SHEET OR CARD (Oct 27), so it reads as above the page, and a press outside it,
    // which closes it, is plainly the way back. Nothing under it stops working: the dim takes no presses.
    dim.classList.toggle("on", (st.open || st.card) && !locked());
    tuck();
  }
  /** THE BAR SAYS THE STATE ONCE (Oct 26). The lamp, the placeholder, the button and the sheet's foot each said that the
   *  manager was not running, so one state was written four times over; and the first read was announced in the field
   *  as the words "Reading the splice manager", where the catalog draws a shape. Now the lamp carries the state, the
   *  button says what the press does, the placeholder names the one thing the field is for, the shortcut is a key
   *  rather than a sentence, and a read not back yet is a striped ghost the size of the hint. */
  function drawPill() {
    const m = st.m, door1 = Boolean(m) && unasked(m) && !st.phase, first = !m && !st.gone;
    const words = field.value.trim() !== "";
    // A SPLICE THAT CANNOT BE REACHED, OR THAT HAS NO MANAGER, SAYS SO ONCE IN THE FIELD'S PLACE (Oct 27): a short
    // muted label beside the lamp that already draws it, with the one act that can help, where the placeholder held a
    // sentence and the only button was a Send that could not send. Words he had typed keep the field, and the open
    // sheet's header says the state instead.
    const down = st.gone === "down" && !first;
    // a first read out longer than a read takes says so once beside its stripes, as a page's header does
    const dead = first ? (st.slow ? "Slow to read" : null) : door1 ? null : down ? "splice is not answering" : !m ? "This splice has no manager" : null;
    const label = Boolean(dead) && !words && !st.open;
    door.hidden = !door1; field.hidden = door1 || first || label;
    said.hidden = !label;
    if (label) said.textContent = dead;
    pill.querySelector(".cmp-wait").hidden = !first;
    // the card says what the door says, so the door is not drawn under it: the title is read once
    pill.classList.toggle("beneath", st.card && door1);
    // the door's verb is the card's: Resume over a card that resumes, Start over one that starts
    door.querySelector("span").textContent = `${m?.state === "resumable" ? "Resume" : "Start"} the splice manager`;
    lamp.className = `cmp-lamp ${lampOf()}`;
    tabLamp.className = lamp.className;
    field.readOnly = st.busy || Boolean(st.phase) || !m || down;
    field.placeholder = dead ? "" : st.shortHint ? SHORT_HINT : HINT;
    // WHILE IT WAITS ON HIM THE BAR'S ONE ACT IS ANSWER (Oct 27). The button was lit for a waiting manager and also
    // disabled for an empty field, and a disabled act is drawn unlit, so the light it was given could never show.
    // Closed, it reads Answer and opens the sheet on the question; open, the question's own choices are the act.
    const answering = m?.state === "live" && m.status === "waiting" && !st.open && !down;
    send.hidden = door1 || first || (Boolean(dead) && !down);
    send.disabled = st.busy || st.retrying || Boolean(st.phase) || (!down && !answering && (!m || (m.state === "live" && !words)));
    send.textContent = st.busy ? "Sending" : down ? "Try again" : answering ? "Answer" : m?.state === "resumable" && !words ? "Resume" : "Send";
    // THE KEY LIGHT MARKS THE ONE ACT TO TAKE, AND THE BAR SHARES EVERY PAGE (Oct 26). Resume wore it whenever the
    // bar was drawn, so a page whose own act was New team or Send had a brighter button in the corner of the eye than
    // the thing it leads to. The bar lights its button when it holds something to send, or when the manager is
    // waiting on him; until then it waits as a quiet outline, as a disabled Send does.
    const lit = !down && (words || answering);
    send.classList.toggle("primary", lit);
    send.classList.toggle("quiet", !lit);
    send.setAttribute("aria-busy", String(st.busy || st.retrying));
  }
  function headChip(x, on, role) {
    return `<button type="button" class="cmp-hd" role="${role}" aria-checked="${on}" data-act="${role === "radio" ? "pick" : "head"}" data-head="${h(x.key)}"`
      + ` style="--c:${tint(x.key)}"${x.ready ? "" : " disabled"}><span class="cmp-blot" aria-hidden="true"></span><span>${h(x.label || x.key)}</span></button>`;
  }
  /** The commands in two parts: those that can carry the manager now, then the rest under one kicker per state they
   *  are in ("Not answering", "At limit"), dimmed. A reason sat under every one of them, four times the same line. */
  function headsHtml(on, role) {
    const list = heads(), off = new Map();
    for (const x of list.filter((y) => !y.ready)) {
      const s = headState(x);
      if (!off.has(s)) off.set(s, []);
      off.get(s).push(x);
    }
    return {
      ready: list.filter((x) => x.ready).map((x) => headChip(x, x.key === on, role)).join(""),
      // a command that is not installed carries the command that installs it, with Copy, beside its group, so the way
      // out is where the reason is
      off: [...off].map(([s, xs]) => `<div class="cmp-offgroup"><p class="kicker">${h(s)}</p>`
        + `<div class="cmp-offrow">${xs.map((x) => headChip(x, x.key === on, role)).join("")}</div>`
        + (s === "Not installed" ? `<div class="cmp-install"><code>${INSTALL_LINE}</code><button type="button" class="act quiet small" data-act="copyinstall">Copy</button></div>` : "")
        + `</div>`).join(""),
    };
  }
  /** THREE LABELLED FACTS WHERE A PARAGRAPH STOOD (Oct 26, Oct 27). The card opened with about 45 words naming a Claude
   *  Code session, splice.toml and a folder path; then three sentences. Each fact is now its label and its few words:
   *  where it works, what it reads, what it changes. The folder is named as he knows it, splice's settings folder (the
   *  daemon's own: the parent of splice.toml, ManagerWiring), and its path stands on the manager's session on Sessions. */
  function drawCard() {
    card.hidden = !st.card;
    if (!st.card) return;
    // a first run opens the card on its own, not through openCard, so the heads' colours are read here too
    if (!st.tints) readTints();
    const list = heads(), ready = list.filter((x) => x.ready);
    if (!ready.some((x) => x.key === st.pick)) st.pick = ready.find((x) => x.key === st.m?.head)?.key ?? ready[0]?.key ?? null;
    const verb = st.m?.state === "resumable" ? "Resume" : "Start";
    // THREE GLYPH TILES WHERE THREE LABELLED FACTS STOOD (Oct 28): a folder for where it works, the pages it reads drawn as
    // the top bar's own glyphs, a pencil for what it changes. The label is the tile's name for a reader that cannot see
    // the glyph, and the title says it on hover; the words beside the glyph are the fact's own.
    const fact = (name, glyphs, words) => `<div class="cmp-fact" title="${name}"><dt>${name}</dt><dd><span class="cmp-fg" aria-hidden="true">${glyphs}</span><span>${words}</span></dd></div>`;
    const parts = headsHtml(st.pick, "radio");
    const html = `<div class="cmp-top"><span class="cmp-lamp ${lampOf()}" aria-hidden="true"><i></i></span>`
      + `<h2 id="cmp-card-h">${verb} the splice manager</h2>`
      + `<button type="button" class="act quiet small" data-act="later">Not now</button></div>`
      + `<dl class="cmp-facts">`
      + fact("Works in", KIND.folder.icon, "splice's settings folder")
      + fact("Reads", ["models.html", "accounts.html", "sessions.html"].map((f) => NAV_GLYPH[f]).join(""), "commands, accounts, sessions")
      + fact("Changes", KIND.write.icon, "settings, once you approve")
      + `</dl>`
      + `<p class="kicker" id="cmp-heads-l">${ready.length ? "Runs on" : "No command can run it"}</p>`
      + `<div class="cmp-heads" role="radiogroup" aria-labelledby="cmp-heads-l">${parts.ready}</div>`
      + (parts.off ? `<div class="cmp-off">${parts.off}</div>` : "")
      + `<div class="cmp-go"><button type="button" class="act primary" data-act="start"${st.pick ? "" : " disabled"}>${verb}</button></div>`;
    if (html !== drawn.card) { card.innerHTML = html; drawn.card = html; }
  }
  /** The sheet's own header: the lamp, the name, and the ONE state word. It read "splice manager" beside the folder
   *  path in mono, a filesystem path as a panel's subtitle, while the state was said twice more below. */
  function drawHead() {
    const m = st.m, look = lampOf();
    const word = st.phase ? phaseWord()
      : st.gone === "down" ? "splice is not answering"
      : m?.state === "live" ? WORD[m.status] ?? "Ready"
      : m?.state === "resumable" ? "Not running" : "";
    const html = `<span class="cmp-lamp ${look}" aria-hidden="true"><i></i></span><b>splice manager</b>`
      + `<span class="cmp-sw ${look}">${h(word)}</span>`
      + `<button type="button" class="cmp-x" data-act="close" aria-label="Close the conversation">${X}</button>`;
    if (html !== drawn.head) { sheet.querySelector(".cmp-sh").innerHTML = html; drawn.head = html; }
  }
  function drawSheet() {
    const shown = !sheet.hidden;
    sheet.hidden = !st.open;
    if (!st.open) { st.unread = 0; return; }
    // a sheet just opened starts at its newest words (a hidden log forgets its scroll), and one he was reading at the
    // foot stays there when a question opens under it and takes its room
    const stick = !shown || log.scrollHeight - log.scrollTop - log.clientHeight < FOLLOW_PX;
    drawHead(); drawLog(); drawAsk(); drawMeters(); drawFoot();
    if (stick) { log.scrollTop = log.scrollHeight; st.unread = 0; }
    edge();
  }
  /** One window as Accounts draws it: the kit's 16.7 px meter in the head's provider colour, its figure reading "NN% used",
   *  and a reset ring with the time beside it. A share splice has not read is the empty track, striped; a figure it has
   *  no reading for is the dash. Over the limit the fill is the limit red and nothing else is.
   *  [row]: { name, pct, of, word, reset, len, act, pending, limit, even, lead }. [of] is the room the share is of,
   *  said on the figure's baseline ("44% of 272K"); [word] what the share is when it is not "used"; [even] a share
   *  where more is better, drawn in a neutral fill so it never reads as one more limit being spent; [lead] the share
   *  the strip is here for, whose figure is the focal one the eye lands on, where every figure stood at body size. */
  function meterRow({ name, pct, of = "", word = "used", reset = null, len = 0, act = "", pending = false, limit = true, even = false, lead = false, after = "" }) {
    const known = typeof pct === "number" && isFinite(pct);
    const p = known ? Math.min(100, Math.max(0, Math.round(pct))) : null;
    // the limit red marks a limit being reached and nothing else: a cache that serves 92% is not a limit, it is good
    const hot = known && limit && p >= CRITICAL_PCT;
    // a share not reported yet is the dashed empty track the console draws for "no reading here", never the stripes of a read
    // still out, which this was: those say "coming", and for the manager this only comes after its next turn
    const bar = known ? DRAW.meter(p, `${hot ? "full" : ""}${pending ? " reading" : ""}`.trim()) : DRAW.meter(null);
    const back = reset ? resetWord(reset) : "";
    const ring = reset && len > 0 ? DRAW.ring(Math.max(0, Math.min(1, (reset * 1000 - Date.now()) / len))) : "";
    const tail = of ? `<span class="cmp-of">${h(of)}</span>` : word ? `<span class="cmp-of">${h(word)}</span>` : "";
    return `<div class="cmp-mrow${hot ? " hot" : ""}${even ? " even" : ""}${lead ? " lead" : ""}" data-meter="${h(name)}">`
      + `<span class="cmp-mname">${h(name)}</span>`
      + `<span class="cmp-mbar">${bar}</span>`
      + `<span class="cmp-mnum">${known ? `<b>${p}%</b>${tail}` : after ? `<span class="cmp-of">${h(after)}</span>` : DRAW.dash}</span>`
      + `<span class="cmp-mreset">${ring}${back ? `<span>${h(back)}</span>` : ""}</span>`
      + `<span class="cmp-mact">${act}</span></div>`;
  }
  /** Over the foot: how full the manager's context is (with Compact on it), and how much of its account's 5 h and 7 d
   *  windows is spent.
   *
   *  ACCOUNTS' ROWS, NOT .45rem SLIVERS WITH WORDS AFTER THEM (Oct 26). Every bar here was a 7 px sliver whose meaning
   *  was carried by the text beside it, and a reading splice did not have yet was written out as "empty" or "shows
   *  after its next turn". These are the meter rows on Accounts: one 16.7 px meter per window on a shared
   *  column, "NN% used" beside it, a reset ring with the time it comes back, and the account's own name said once over
   *  them. An unread window is its own striped track; a figure splice has no reading for is the dash. */
  function drawMeters() {
    const ln = lineOf(), ctx = ln?.context, rows = [];
    if (st.m?.state === "live") {
      const pending = Boolean(st.compacting);
      const compact = DRAW.act({ word: "Compact", act: "compact", cls: "quiet small",
        disabled: !canSteer() || !(ctx?.used > 0), busy: pending });
      // splice keeps the manager's last status-line reading, and an idle session posts none until something happens in
      // it: no reading is the striped track, which says "not read" without a sentence.
      // THE SHARE IS READ AGAINST ITS ROOM (Oct 27): "44% of 272K", the window the status line itself reports, so it
      // holds for a model splice's catalog has never listed; the row read "44% used" with the room drawn nowhere.
      const room = ctx?.window > 0 ? ctx.window : null;
      rows.push(meterRow({ name: "Context", pct: room ? ctx.percent : null, of: room ? `of ${DRAW.big(room)}` : "", act: compact, pending, lead: true, after: "After its next turn" }));
      // the share of the newest input served from the cache: more is better, so it is drawn neutral, not as a limit
      if (ctx?.cache != null) rows.push(meterRow({ name: "From cache", pct: ctx.cache, word: "", limit: false, even: true }));
      const row = st.limits?.row, who = [row?.display_name, row?.plan].filter(Boolean).join(" · ");
      const windows = [["5 hours", row?.five_hour_used_percent, row?.five_hour_reset_epoch_seconds, 5 * 3600_000],
        ["7 days", row?.seven_day_used_percent, row?.seven_day_reset_epoch_seconds, 7 * 86400_000]]
        // a window whose reset has gone by holds the ended window's figure, which Accounts drops too: it is not spent now
        .filter(([, pct, reset]) => pct != null && !(reset && reset * 1000 <= Date.now()));
      if (windows.length && who) rows.push(`<p class="kicker">${h(who)}</p>`);
      for (const [name, pct, reset, len] of windows) rows.push(meterRow({ name, pct, reset, len }));
    }
    const html = rows.join("");
    const first = !drawn.meters;   // the strip arriving grows its bars in once; a new reading slides to its width
    meters.hidden = !html;
    if (html !== drawn.meters) {
      meters.innerHTML = html;
      meters.style.setProperty("--c", tint(st.m?.head));
      drawn.meters = html;
      DRAW.paint(meters, { grow: first ? "all" : null });
    }
  }
  /** One chip of the foot with the menu it opens above it. */
  const wrap = (kind, chip, items) => `<div class="cmp-menuwrap">${chip}<div class="cmp-menu" data-for="${kind}" role="menu"${st.menu === kind ? "" : " hidden"}>${items}</div></div>`;
  const effortWord = (v) => EFFORTS.find(([x]) => x === v)?.[1] ?? v;
  /** THE MODEL AND THE EFFORT ARE ONE CHIP AND ONE MENU (Oct 27). They were two chips, each opening a menu that ended on
   *  the same warning, and each read "Model" or "Effort" when the status line had not said. The chip is what the
   *  manager answers with, read as one phrase, "GPT-5.6 Codex · High"; a value not said yet is the dash. */
  function steerChip(ln, w, steer) {
    const model = w?.kind === "model" ? w.label : ln?.model, effort = w?.kind === "effort" ? w.label : ln?.effort ? effortWord(ln.effort) : null;
    const label = model == null && effort == null ? DRAW.dash
      : `${model == null ? DRAW.dash : h(model)}<span class="cmp-sep" aria-hidden="true">·</span>${effort == null ? DRAW.dash : h(effort)}`;
    return `<button type="button" class="cmp-hd cmp-ch" data-act="menu" data-menu="steer" aria-haspopup="menu" aria-expanded="${st.menu === "steer"}"`
      + `${steer ? "" : " disabled"}${w ? ' aria-busy="true"' : ""}><span>${label}</span>${CARET}</button>`;
  }
  /** One model: its name, and its window as a bar on the menu's one scale with the figure at the bar's end, so the
   *  rooms compare by length and the unit is said once, in the column's heading. */
  const modelRow = (r, top) => `<button type="button" class="cmp-hd cmp-mod" role="menuitemradio" aria-checked="${r.on}" data-act="model" data-id="${h(r.id)}" data-label="${h(r.label)}">`
    + `<span class="cmp-mlabel">${h(r.label)}${r.pinned ? `<span class="cmp-tag">default</span>` : ""}</span>`
    + `<span class="cmp-wbar${r.win ? "" : " none"}" aria-hidden="true">${r.win ? `<i style="width:${((100 * r.win) / top).toFixed(1)}%"></i>` : ""}</span>`
    + `<span class="cmp-wnum">${r.win ? DRAW.big(r.win) : DRAW.dash}</span></button>`;
  /** The menu: this command's models with their windows under one "Context" heading, the one it runs ticked even where
   *  the catalog does not list it (its window then the status line's own), the efforts as one switch, and what a change
   *  costs said once at the foot. A list still being read is the shape that will land; one that failed has Try again. */
  function steerItems(ln, steer) {
    const read = st.models?.head === st.m?.head ? st.models : null;
    let models;
    if (!read) models = `<div class="cmp-menuwait">${GHOST.lines([11, 9, 12, 8])}</div>`;
    else if (!read.list) models = `<p class="cmp-menuline cmp-failed">${DRAW.nothing("Models not read")}<button type="button" class="act quiet small" data-act="remodels">Try again</button></p>`;
    else {
      const rows = read.list.map((r) => ({ id: r.id, label: r.label || r.id, win: r.context_window || null, pinned: r.pinned, on: isModel(r, ln) }));
      if (ln?.model && !rows.some((r) => r.on)) rows.unshift({ id: ln.model_id || ln.model, label: ln.model, win: ln.context?.window || null, on: true });
      const top = Math.max(1, ...rows.map((r) => r.win || 0));
      models = rows.length ? `<div class="cmp-mhead"><span class="kicker">Model</span><span class="kicker">Context</span></div>${rows.map((r) => modelRow(r, top)).join("")}`
        : `<p class="cmp-menuline">${DRAW.nothing("No models listed")}</p>`;
    }
    const efforts = `<div class="cmp-effort"><p class="kicker">Effort</p>${DRAW.switcher(EFFORTS, ln?.effort ?? null, "effort", "Effort", { disabled: !steer })}</div>`;
    return models + efforts + rereadLine(ln?.context);
  }
  /** The foot is what the manager IS and what he can do to it: the command it runs on, what it answers with, Stop while
   *  a turn runs, and the door to its own page. The state word left for the header, where it is said once. */
  function drawFoot() {
    const m = st.m, key = m?.head, live = m?.state === "live", ln = lineOf(), w = st.want;
    const steer = canSteer();
    const parts = headsHtml(key, "menuitemradio");
    const head = key ? wrap("head", `<button type="button" class="cmp-hd" data-act="menu" data-menu="head" aria-haspopup="menu" aria-expanded="${st.menu === "head"}" style="--c:${tint(key)}"`
      + ` aria-label="The command the manager runs on: ${h(labelOf(key))}"${st.phase || !heads().length ? " disabled" : ""}${st.phase?.kind === "move" ? ' aria-busy="true"' : ""}>`
      + `<span class="cmp-blot" aria-hidden="true"></span><span>${h(labelOf(key))}</span>${CARET}</button>`,
      `<p class="kicker">Continue on</p>${parts.ready}${parts.off}`) : "";
    const model = live ? wrap("steer", steerChip(ln, w, steer), steerItems(ln, steer)) : "";
    const stop = live && m.status === "working"
      ? DRAW.act({ word: st.stopping ? "Stopping" : "Stop", act: "stop", cls: "small", busy: st.stopping, disabled: st.stopping }) : "";
    const open = st.sid ? `<a class="act quiet small cmp-open" href="sessions.html#${encodeURIComponent(st.sid)}" data-act="go">Open on Sessions${DRAW.go}</a>` : "";
    const html = head + model + stop + open;
    if (html !== drawn.foot) { foot.innerHTML = html; drawn.foot = html; }
    flash();
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
      // with no question on its screen the block is its choices or its door alone: a stand-in sentence only said
      // again what the lamp and the header's "Needs you" already say
      const q = ruled(asking) || ruled(a.asked) || ruled(why);
      const going = st.answering;
      // The press he is most likely to want is the first Yes: a menu whose first line is "No, exit" (the folder-trust
      // question) never paints the exit as its primary button.
      const yes = (a.choices || []).findIndex((c) => /^yes\b/i.test(c.label));
      // A Yes that also widens what the manager may do from now on (a whole folder, every edit, auto mode) reads quieter than
      // the plain Yes, so the plain one is the easy press.
      const broad = /auto mode|allow (all|reading|writing)|all edits|don.t ask again|this project|this session/i;
      // ONE CHOICE PER ROW, ON A NUMBERED RAIL, IN HIS WORDS (Oct 27). The choices wrapped two to a row and a third
      // under them, each behind a key cap that answered no key here, and a label could end on the terminal's own
      // "(esc)", which closes this sheet. The terminal's key hint comes off and the manager is named as the manager;
      // the label pressed is still the screen's own, so a menu that moved is caught (answer).
      const shown = (label) => oneLine(String(label).replace(/\s*\((?:esc|tab|shift\+tab|enter|ctrl\+\w)\)\s*$/i, "")
        .replace(/\bClaude\b(?! Code)/g, "the manager"));
      const choices = (a.choices || []).map((c, i) => `<button type="button" class="act cmp-choice${i === yes ? " primary" : ""}${broad.test(c.label) ? " broad" : ""}" data-act="answer" data-choice="${c.choice}" data-label="${h(c.label)}"`
        + `${going ? " disabled" : ""}${going === c.choice ? ' aria-busy="true"' : ""}><span class="cmp-n" aria-hidden="true">${c.choice}</span><span>${h(shown(c.label))}</span></button>`).join("");
      // with no numbered choice to press, the way to answer is the door itself, not a sentence naming where to go
      const door = st.sid ? `<a class="act small cmp-askdoor" href="sessions.html#${encodeURIComponent(st.sid)}" data-act="go">Answer on Sessions${DRAW.go}</a>` : "";
      // and the line the screen puts above the framed command, which is what the manager wants it FOR ("Run the fence's
      // routing tests" over `bun test …`): it was read here and then used only if the question went missing, so a
      // press approved a command with no reason beside it (Oct 26)
      const asFor = why && ruled(why) !== q ? `<p class="cmp-why">${h(ruled(why))}</p>` : "";
      html = `${q ? `<p class="cmp-q">${h(q)}</p>` : ""}${asFor}${run}`
        + `<div class="cmp-choices">${choices || door}</div>`;
    }
    ask.hidden = !html;
    if (html !== drawn.ask) { ask.innerHTML = html; drawn.ask = html; }
    flash();
  }

  // ---------- the conversation ----------
  /** Light markdown, as the manager writes it: paragraphs, bullets, numbered lists, inline and fenced code, bold,
   *  italics, links, headings and pipe tables. A heading was set as a bold body line and a table, a link or an italic
   *  run printed its own pipes, brackets and asterisks; the manager writes all four, so the sheet reads them (Oct 26).
   *  Only an address this console can open is made a link, so nothing in a reply can carry a script. */
  const LINK = /\[([^\]\n]+)]\((https?:\/\/[^\s)]+|[a-z0-9-]+\.html(?:[?#][^\s)]*)?|[#/][^\s)]*)\)/gi;
  /** A COMMAND THE MANAGER NAMES IS DRAWN AS THAT COMMAND (Oct 27). Its replies name commands in code or in a table's
   *  column, and the foot under them draws the same command as a chip in its provider's colour, so the log printed as a
   *  plain word what every other part of the console draws as an object. A code span or a table cell that is exactly
   *  one of this splice's commands is that command's chip; any other text stays as it was written. */
  const commandChip = (text) => {
    const t = String(text).trim(), x = t ? heads().find((y) => y.key === t || y.label === t) : null;
    return x ? DRAW.chip({ text: x.label || x.key, data: ` style="--c:${tint(x.key)}"` }) : null;
  };
  // a code span is taken out before the text is read for bold and italics and put back drawn, so a bold run that holds a
  // code span is one run, with no asterisks left on the screen, and nothing in code is read for emphasis
  const inline = (s) => {
    const held = [];
    const text = String(s).replace(/\u0000/g, "").replace(/`([^`\n]+)`/g, (_, code) => `\u0000${held.push(commandChip(code) ?? `<code>${h(code)}</code>`) - 1}\u0000`);
    return h(text)
      .replace(LINK, (_, label, url) => `<a href="${url}" rel="noreferrer">${label}</a>`)
      .replace(/\*\*([^*\n]+)\*\*/g, "<strong>$1</strong>")
      .replace(/(^|[\s\u0000(])\*([^*\n]+)\*(?=[\s\u0000).,;:!?]|$)/g, "$1<em>$2</em>")
      .replace(/\u0000(\d+)\u0000/g, (_, n) => held[+n] ?? "");
  };
  /** A reply that is nothing but a path is what it is: the mono face, so it reads as a place and not as a sentence. */
  const PATH_ONLY = /^(?:~|\.{0,2}\/)[^\s]*$/;
  const ROW = /^\s*\|(.+)\|\s*$/;
  const cells = (ln) => ROW.exec(ln)[1].split("|").map((c) => c.trim());
  const RULE = /^\s*\|[\s:|-]+\|\s*$/;
  function md(text) {
    const out = [];
    let para = [], list = null, code = null, table = null;
    const endPara = () => { if (para.length) out.push(para.length === 1 && PATH_ONLY.test(para[0].trim())
      ? `<p class="cmp-path">${fileChip(para[0].trim())}</p>` : `<p>${para.map(inline).join("<br>")}</p>`); para = []; };
    const endList = () => { if (list) out.push(`<${list.tag}>${list.items.map((x) => `<li>${inline(x)}</li>`).join("")}</${list.tag}>`); list = null; };
    const endTable = () => {
      if (!table) return;
      const [head, ...body] = table;
      out.push(`<table class="cmp-tbl"><thead><tr>${head.map((c) => `<th>${inline(c)}</th>`).join("")}</tr></thead>`
        + `<tbody>${body.map((r) => `<tr>${r.map((c) => `<td>${commandChip(c) ?? inline(c)}</td>`).join("")}</tr>`).join("")}</tbody></table>`);
      table = null;
    };
    const lines = String(text).split("\n");
    for (let i = 0; i < lines.length; i++) {
      const ln = lines[i];
      if (code) {
        if (/^\s*```/.test(ln)) { out.push(`<pre><code>${h(code.join("\n"))}</code></pre>`); code = null; } else code.push(ln);
        continue;
      }
      if (/^\s*```/.test(ln)) { endPara(); endList(); endTable(); code = []; continue; }
      // a table is its header, its rule, and the rows under it: anything else ends it
      if (ROW.test(ln) && (table || RULE.test(lines[i + 1] || ""))) {
        endPara(); endList();
        if (!table) { table = [cells(ln)]; i++; continue; }
        if (!RULE.test(ln)) table.push(cells(ln));
        continue;
      }
      endTable();
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
      if (hd) { endPara(); out.push(`<p class="cmp-h">${inline(hd[1])}</p>`); continue; }
      para.push(ln);
    }
    if (code) out.push(`<pre><code>${h(code.join("\n"))}</code></pre>`);
    endPara(); endList(); endTable();
    return out.join("");
  }
  /** A body bounded to its first lines, with a count to open the rest in place: one peer message ran eleven lines and
   *  250 px tall, pushing everything around it out of the sheet (principle 8, the bounded list).
   *
   *  The bound is on the LINES HE SEES, not on the lines the sender typed: a message of two long paragraphs wraps to a
   *  dozen. The body is clamped by the stylesheet to six of them, and the count on the pill is measured off the drawn
   *  block once it lands (countLines), so "+7 lines" is seven lines he cannot see and never an estimate. */
  function body(key, text) {
    const whole = String(text).trim();
    const open = st.opened.has(`b${key}`);
    const small = whole.length < 420 && whole.split("\n").length <= BODY_LINES;
    if (open || small) return `<div class="cmp-md">${md(whole)}</div>`;
    return `<div class="cmp-md cmp-clamp">${md(whole)}</div>`
      + `<button class="morepill pill" type="button" data-act="more" data-k="${h(key)}" hidden><b></b> more lines</button>`;
  }
  /** What a clamped body is hiding, measured on the block itself: with nothing hidden the pill is not drawn at all.
   *
   *  A CLAMP ENDS ON A WHOLE LINE (Oct 27): six lines' height cut a reply's table through a row, and the command's
   *  chip in that row in half, and the count divided the hidden height by a paragraph's line, so a table's rows,
   *  taller than a line, were counted as more lines than there were. The block now ends under the last line, row or
   *  chip that fits whole in the six lines the stylesheet allows, and the count is of what lies under that edge: a line
   *  of text is one, a row of a table is one.
   *  A block is measured once, when it is drawn in an open sheet, and again when the window's width changes
   *  [again]; a hidden log has nothing to measure, and is measured when the sheet opens (drawLog). */
  function countLines(again = false) {
    if (sheet.hidden) return;
    for (const block of log.querySelectorAll(".cmp-clamp")) {
      const pill = block.nextElementSibling;
      if (!pill?.classList.contains("morepill") || (block.style.maxHeight && !again)) continue;
      const cut = wholeLines(block);
      pill.hidden = !cut;
      if (!cut) { block.classList.remove("cmp-clamp"); continue; }
      block.style.maxHeight = `${cut.at}px`;
      pill.querySelector("b").textContent = `+${cut.hidden}`;
      pill.lastChild.textContent = cut.hidden === 1 ? " more line" : " more lines";
    }
  }
  /** Where a clamped block can end, and how many lines lie under that edge; null when every line fits. A line of text, a
   *  table's row and a chip with its offset edge are each a span the edge may not cross. When no edge inside the bound
   *  falls clear of every span, the block is cut where the bound falls. */
  function wholeLines(block) {
    block.style.maxHeight = "";
    const limit = block.clientHeight, top = block.getBoundingClientRect().top;
    const spans = [], range = document.createRange();
    const add = (r, line, edge = 0) => spans.push({ top: r.top - top, bottom: r.bottom - top + edge, line });
    const walk = document.createTreeWalker(block, NodeFilter.SHOW_TEXT);
    for (let n = walk.nextNode(); n; n = walk.nextNode()) {
      if (!n.data.trim() || n.parentElement.closest("tr")) continue;
      range.selectNodeContents(n);
      for (const r of range.getClientRects()) if (r.width) add(r, true);
    }
    for (const tr of block.querySelectorAll("tr")) add(tr.getBoundingClientRect(), true);
    for (const chip of block.querySelectorAll(".chip")) {
      add(chip.getBoundingClientRect(), false, parseFloat(getComputedStyle(chip).boxShadow.split("px")[1]) || 0);
    }
    if (Math.max(...spans.filter((s) => s.line).map((s) => s.bottom)) <= limit + 1) return null;
    const whole = (y) => !spans.some((s) => s.top < y - 1 && s.bottom > y + 1);
    let at = 0;
    for (const s of spans) if (s.bottom <= limit && s.bottom > at && whole(s.bottom)) at = s.bottom;
    at = at ? Math.max(at, Math.min(limit, ...spans.filter((s) => s.top >= at - 1).map((s) => s.top))) : limit;
    // the lines under the edge: a span that overlaps the lines above it by less than half its own height starts a line
    let hidden = 0, end = -Infinity;
    for (const s of spans.filter((x) => x.line && x.top >= at - 1).sort((a, b) => a.top - b.top)) {
      if (end - s.top < (s.bottom - s.top) / 2) hidden++;
      end = Math.max(end, s.bottom);
    }
    return { at, hidden: Math.max(1, hidden) };
  }
  /** What a call was about, from its input as the transcript keeps it (Sessions' callSummary); a path in the manager's
   *  own folder reads from there. A message's own summary is what it was about; who it went to is drawn as that
   *  session's own chip, so no row has to spell a socket path. */
  /** WHAT A CALL WAS ABOUT IS SAID IN WORDS, AND THE RAW INPUT WAITS ONE PRESS UNDER IT (Oct 28). The row printed the
   *  command line, the whole path or the address as it was sent, so a row read as a shell history. [say] is what the row
   *  shows: the call's own description of itself when it wrote one, the file's name for a file, the pattern for a
   *  search, the host for an address; [raw] is the command, the whole path or the whole address, drawn under the row
   *  when it has more to tell than [say] does. A call that wrote no description keeps its command in the row, since a
   *  stand-in for it would be a guess. */
  function callAbout(m) {
    let input;
    try { input = JSON.parse(m.text); } catch { return { say: oneLine(m.text), raw: null }; }
    if (!input || typeof input !== "object") return { say: oneLine(m.text), raw: null };
    const folder = st.m?.folder ? `${String(st.m.folder).replace(/\/$/, "")}/` : null;
    const file = (p) => (folder && p.startsWith(folder) ? p.slice(folder.length) : home(p));
    const name = (p) => String(p).replace(/\/+$/, "").split("/").pop() || String(p);
    // a summary that is only a place is read as the place it is: its name in the row, the whole path under it
    const said = (text) => (PATH_ONLY.test(text) ? { say: name(text), raw: { path: file(text) } } : { say: text, raw: null });
    if (input.summary) return said(oneLine(input.summary));
    if (input.command) {
      const raw = String(input.command).trim();
      return input.description ? { say: oneLine(input.description), raw } : { say: oneLine(raw), raw: null };
    }
    if (input.file_path || input.notebook_path) {
      const path = input.file_path || input.notebook_path;
      return { say: name(path), raw: { path: file(path) } };
    }
    if (input.pattern) return { say: input.path ? `${input.pattern} in ${name(input.path)}` : String(input.pattern), raw: input.path ? { path: file(input.path) } : null };
    if (input.questions?.[0]?.question) return { say: input.questions[0].question, raw: null };
    if (input.url) {
      let host = input.url;
      try { host = new URL(input.url).host; } catch { /* a malformed address is shown as it came */ }
      return { say: host, raw: input.url === host ? null : String(input.url) };
    }
    if (input.to && input.message) return said(oneLine(input.message));
    return { say: oneLine(Object.values(input).find((v) => typeof v === "string" && v.trim()) ?? ""), raw: null };
  }
  /** A place the way a file chip draws it: its name first, in full ink, and the folder it is in beside it, muted. */
  function fileChip(path) {
    const text = String(path).trim(), cut = text.lastIndexOf("/"), dir = cut > 0 ? home(text.slice(0, cut)) : "", name = cut >= 0 ? text.slice(cut + 1) : text;
    return `<span class="cmp-file" title="${h(home(text))}">${KIND.read.icon}<b>${h(name || text)}</b>${dir ? `<span>${h(dir)}</span>` : ""}</span>`;
  }
  /** Who a call reached, when splice knows that session: its name, as a door to its own page. */
  function callTo(m) {
    if (TOOL_KIND[m.tool] !== "send") return null;
    try { return whoIs(JSON.parse(m.text)?.to); } catch { return null; }
  }
  const chipOfWho = (who) => `<a class="chip cmp-to" href="sessions.html#${encodeURIComponent(who.id)}" data-act="go">${h(who.name)}</a>`;
  /** One call: its kind's glyph and word, what it was about, who it reached, and how it went. Its result opens under it,
   *  bounded, with no scroller of its own inside the log's. */
  function callRow(key, m, result, live) {
    const kind = TOOL_KIND[m.tool] ?? "step";
    const k = KIND[kind], open = st.opened.has(key);
    const bad = failedResult(result), out = result != null;
    const who = callTo(m), about = callAbout(m);
    // a failure is red once, on its own mark: the word and the glyph keep the ink every other row has
    const mark = bad ? `<span class="cmp-mk bad">${ICON.bang}Failed</span>`
      : !out && live ? `<span class="cmp-mk live" role="img" aria-label="Still going"><i></i></span>` : "";
    const word = kind === "step" ? (m.tool || k.word) : k.word;
    // what was sent, whole, one press under the words: a command in the code face, a place as its file chip
    const raw = !about.raw ? "" : typeof about.raw === "string" ? `<pre class="cmp-raw">${h(about.raw)}</pre>` : `<p class="cmp-rawpath">${fileChip(about.raw.path)}</p>`;
    return `<div class="cmp-call${bad ? " bad" : ""}" data-k="${h(key)}">`
      + `<button class="disc cmp-cb" type="button" aria-expanded="${open}" aria-controls="cmp-r-${h(key)}" data-act="call" data-k="${h(key)}">`
      + `${DRAW.chev}${k.icon}<b>${h(word)}</b><span class="cmp-sum">${h(about.say)}</span>${who ? chipOfWho(who) : ""}${mark}</button>`
      + `<div class="cmp-drop" id="cmp-r-${h(key)}" data-open="${open}"><div class="cmp-dropin">${raw}${resultHtml(key, result)}</div></div></div>`;
  }
  /** A result, bounded to its first lines with the rest behind a count: left whole, an opened result was a scroller of
   *  its own inside the log that hid most of a long result. */
  function resultHtml(key, result) {
    if (result == null) return `<p class="cmp-none">${DRAW.nothing("No result yet")}</p>`;
    const text = String(result).trim();
    if (!text) return `<p class="cmp-none">${DRAW.nothing("No output")}</p>`;
    const lines = text.split("\n"), whole = st.opened.has(`r${key}`);
    const cut = !whole && lines.length > RESULT_LINES;
    return `<pre>${h(cut ? lines.slice(0, RESULT_LINES).join("\n") : text)}</pre>`
      + (cut ? `<button class="morepill pill" type="button" data-act="moreres" data-k="${h(key)}"><b>+${lines.length - RESULT_LINES}</b> lines</button>` : "");
  }
  /** A burst of calls in one turn: one row with a glyph and a count per kind, the sessions it wrote to, and a red count
   *  of what failed. Ten calls used to stack as ten identical chips. It opens into the calls themselves. */
  function burstRow(key, calls, live) {
    const open = st.opened.has(key);
    const kinds = new Map();
    for (const c of calls) {
      const kind = TOOL_KIND[c.m.tool] ?? "step";
      kinds.set(kind, (kinds.get(kind) ?? 0) + 1);
    }
    const bad = calls.filter((c) => failedResult(c.result)).length;
    const tiles = [...kinds].map(([kind, n]) => `<span class="cmp-t">${KIND[kind].icon}<b>${n}</b> ${h(KIND[kind].many)}</span>`).join("")
      + (bad ? `<span class="cmp-t bad">${ICON.bang}<b>${bad}</b> failed</span>` : "");
    const who = [...new Map(calls.map((c) => callTo(c.m)).filter(Boolean).map((x) => [x.id, x])).values()];
    const chips = who.slice(0, CHIPS_SHOWN).map(chipOfWho).join("")
      + (who.length > CHIPS_SHOWN ? `<span class="cmp-more">+${who.length - CHIPS_SHOWN}</span>` : "");
    return `<div class="cmp-burst" data-k="${h(key)}">`
      + `<button class="disc cmp-cb" type="button" aria-expanded="${open}" aria-controls="cmp-b-${h(key)}" data-act="call" data-k="${h(key)}">`
      + `${DRAW.chev}<span class="cmp-tiles">${tiles}</span><span class="cmp-who">${chips}</span></button>`
      + `<div class="cmp-drop" id="cmp-b-${h(key)}" data-open="${open}"><div class="cmp-dropin">`
      + `${open ? calls.map((c) => callRow(`${key}-${c.key}`, c.m, c.result, live)).join("") : ""}</div></div></div>`;
  }
  /** The relay a note came through, and who wrote it: "[eli-dispatch e801f85a from eli] …" is plumbing in the middle of
   *  a conversation, so the tag comes off, the writer takes the label, and the relay is said once beside it. */
  const ENVELOPE = /^\s*\[([a-z0-9][a-z0-9-]*)\s+[0-9a-f]{6,}\s+from\s+([^\]]{1,60})]\s*/i;
  // the same tag in the middle of a note (a relay that wrote its own tag after the first paragraph) is plumbing too
  const TAG_ANYWHERE = /\[[a-z0-9][a-z0-9-]*\s+[0-9a-f]{6,}\s+from\s+[^\]]{1,60}]\s*/gi;
  function peerRow(key, m) {
    const raw = String(m.text);
    const seal = ENVELOPE.exec(raw);
    const said = (seal ? raw.slice(seal[0].length) : raw).replace(TAG_ANYWHERE, "");
    const wrote = (seal ? seal[2] : m.from) || "another session";
    const relay = seal && m.from && oneLine(m.from) !== oneLine(wrote) ? oneLine(m.from) : "";
    const who = whoIs(wrote);
    const name = who ? `<a class="cmp-who" href="sessions.html#${encodeURIComponent(who.id)}" data-act="go">${h(who.name)}${DRAW.go}</a>` : `<span class="cmp-who">${h(wrote)}</span>`;
    return `<div class="cmp-m peer" data-k="${h(key)}"><span class="cmp-from">${name}${relay ? `<span class="cmp-via">via ${h(relay)}</span>` : ""}</span>`
      + body(key, said) + `</div>`;
  }
  /** WHEN, SAID WHERE THE TALK BREAKS (Oct 26): the log carried no time at all, so a burst of calls could not be placed
   *  against the replies around it and a conversation that ran from the afternoon to midnight read as one stream. A
   *  break of three minutes or more starts a group, with its time over it as a rule across the sheet, and the day
   *  joins the time when the group falls on another one. */
  function when(ts, before) {
    const now = new Date(ts), was = before == null ? null : new Date(before);
    if (was && ts - before < GROUP_MS && was.toDateString() === now.toDateString()) return null;
    const sameDay = now.toDateString() === new Date().toDateString();
    return sameDay ? clock(now) : `${dayWord(now)} · ${clock(now)}`;
  }
  /** The rows the sheet shows, each with a key, so a poll only adds what is new and never redraws what he is reading. */
  function rows() {
    const held = st.log, msgs = held?.messages || [], out = [];
    const live = st.m?.state === "live";
    st.wantNames = false;
    // EARLIER MESSAGES OPEN WHERE THEY ARE (Oct 28): the pill was a door to Sessions, so reading further back left the
    // sheet he was in. It reads the page before the oldest one drawn and puts it above, with the view held where it was.
    if (held?.earlier && st.sid) {
      out.push({ key: "earlier", html: `<button class="cmp-older morepill pill" type="button" data-act="older"${st.older ? ' aria-busy="true"' : ""}>Earlier messages${CARET}</button>` });
    }
    const results = new Map(msgs.filter((m) => m.role === "tool" && m.tool_use_id).map((m) => [m.tool_use_id, m.text]));
    const moves = (held?.moves || []).slice().sort((a, b) => a.moved_at - b.moved_at);
    const exits = new Set(); // the /exit splice typed to end the client for a move: the move's mechanics, not his words
    for (const mv of moves) {
      const exit = msgs.find((m) => m.role === "user" && m.ts && m.ts >= mv.moved_at - 5_000 && m.ts - mv.moved_at <= EXIT_MS && oneLine(m.text) === "/exit");
      if (exit) exits.add(exit.index);
    }
    // a move's thinking that did not cross is a tag on the move itself, said once where it happened
    const joint = (mv) => ({ key: `mv${mv.moved_at}`, html: `<p class="cmp-moved"><span>Continued on</span><span class="cmp-blot" style="--c:${tint(mv.command)}" aria-hidden="true"></span>`
      + `<b>${h(headOf(mv.command)?.label || mv.command || "another command")}</b>${mv.thinking_dropped ? `<span class="cmp-tag">Thinking not carried over</span>` : ""}</p>` });
    let last = null;              // when the row before this one was written, for the group rules
    let burst = null;             // the run of calls being gathered, drawn as one row
    const flush = () => {
      if (!burst) return;
      const one = burst.calls.length === 1;
      out.push({ key: burst.key, html: one ? callRow(burst.calls[0].key, burst.calls[0].m, burst.calls[0].result, live)
        : burstRow(burst.key, burst.calls, live) });
      burst = null;
    };
    const stamp = (ts) => {
      if (!ts) return;
      const word = when(ts, last);
      last = ts;
      if (word) out.push({ key: `t${ts}`, html: `<p class="cmp-when"><span>${h(word)}</span></p>` });
    };
    for (const m of msgs) {
      while (moves.length && m.ts && moves[0].moved_at <= m.ts) { flush(); out.push(joint(moves.shift())); }
      const key = `${m.index}`;
      if (m.role === "tool" || exits.has(m.index)) continue;
      if (m.tool) {
        // one turn's calls are one row: a burst keeps the key of its first call, so a later call only redraws that row
        if (!burst) { stamp(m.ts); burst = { key: `b${key}`, calls: [] }; }
        burst.calls.push({ key, m, result: results.get(m.tool_use_id) ?? null });
        continue;
      }
      flush();
      if (m.role === "system") { if (m.kind === "interrupted") out.push({ key, html: `<p class="cmp-moved"><span>Stopped</span></p>` }); continue; }
      if (!String(m.text || "").trim()) continue;
      stamp(m.ts);
      if (m.role === "peer") { out.push({ key, html: peerRow(key, m) }); continue; }
      if (m.role === "user") { out.push({ key, html: `<div class="cmp-m you">${h(String(m.text).trim())}</div>` }); continue; }
      out.push({ key, html: `<div class="cmp-m ai" data-k="${h(key)}">${body(key, m.text)}</div>` });
    }
    flush();
    for (const mv of moves) out.push(joint(mv)); // a move after the newest message
    for (const t of st.typed) {
      out.push({ key: `typed${t.at}`, html: `<div class="cmp-m you typed">${h(t.text)}`
        + `<span class="cmp-pend" role="img" aria-label="Not in the conversation yet"><i></i></span></div>` });
    }
    if (!out.length) {
      // AN EMPTY LOG IS ONE MUTED LINE, OR THE SHAPE OF WHAT IS COMING (Oct 26): four full sentences stood here, each
      // repeating what the lamp, the header and the foot already say.
      // A READ THAT FAILED IS A FAILURE, NOT A LOAD (Oct 27): it was drawn in the loading ghost's stripes under its
      // word, so it looked like a read still coming. It says so once, with the act that reads it again.
      const html = !st.log ? (st.sid ? GHOST.lines([18, 26, 14, 22, 10]) : "")
        : st.log.off ? `<p class="cmp-empty">${DRAW.nothing("Reading conversations is off")}<a class="act quiet small" href="settings.html#data">Turn reading on</a></p>`
        : st.log.error ? `<p class="cmp-empty">${DRAW.nothing(oneLine(st.log.error))}<button type="button" class="act quiet small" data-act="reread">Try again</button></p>`
        : `<p class="cmp-empty">${DRAW.nothing("No messages yet")}</p>`;
      out.push({ key: "empty", html });
    }
    if (st.wantNames) readNames();
    return out;
  }
  /** Only what changed is drawn again, from the first row that differs; the view follows only when he was at the foot.
   *  A reply that lands while he is reading further up leaves the view where it is and counts itself on the pill at the
   *  log's foot, which was the one thing the sheet never said. */
  function drawLog() {
    const next = rows(), old = drawn.rows;
    let i = 0;
    while (i < old.length && i < next.length && old[i].key === next[i].key && old[i].html === next[i].html) i++;
    if (i === old.length && i === next.length) { countLines(); return; }  // a log drawn while the sheet was closed
    const follow = log.scrollHeight - log.scrollTop - log.clientHeight < FOLLOW_PX || !old.length;
    const top = log.scrollTop;
    while (log.children.length > i) log.lastElementChild.remove();
    log.insertAdjacentHTML("beforeend", next.slice(i).map((r) => r.html).join(""));
    // A ROW THAT ARRIVES EASES IN (Oct 28), as every other thing that lands on the console does: only rows the log did not
    // have, so a row redrawn for a new count stays still, and never the page of older messages that opens above
    if (old.length && !st.older && !st.still) {
      const had = new Set(old.map((r) => r.key));
      [...log.children].slice(i).forEach((el, j) => { if (!had.has(next[i + j]?.key)) el.classList.add("rise"); });
    }
    const grew = next.length > old.length && old.length > 0;
    drawn.rows = next;
    countLines();
    if (follow) { log.scrollTop = log.scrollHeight; st.unread = 0; } else { log.scrollTop = top; if (grew) st.unread += next.length - old.length; }
    drawUnread();
    edge();
  }
  /** The page of the conversation before the oldest message drawn, put above it with the view held on what he was reading.
   *  A cursor the daemon did not give, or a read that fails, says so once, with the act that tries again. */
  async function older() {
    const held = st.log, id = st.sid, cursor = typeof held?.earlier === "string" ? held.earlier : null;
    if (st.older || !held?.earlier || !id) return;
    st.older = true; drawLog();
    const res = cursor ? await API.get(`${route(id, "transcript")}?before=${encodeURIComponent(cursor)}&limit=${PAGE}`) : null;
    st.older = false;
    // the polls hand the log on as a new object each time, so what matters is that it is still this conversation's, read
    // from the same oldest message: a log that was read again from the end has an oldest of its own
    const cur = st.log;
    if (id !== st.sid || cur?.sid !== id || cur.earlier !== held.earlier) { drawLog(); return; }
    if (!res?.ok || !Array.isArray(res.body?.messages)) {
      drawLog();
      say({ tone: "refused", text: "Earlier messages not read", acts: [{ word: "Try again", primary: true, run: () => { say(null); older(); } }] });
      return;
    }
    const first = cur.messages[0]?.index ?? Infinity, before = log.scrollHeight, top = log.scrollTop;
    st.log = { ...cur, messages: [...res.body.messages.filter((m) => m.index < first), ...cur.messages], earlier: res.body.earlier || null };
    // rows that open above what he reads are older ones, never new ones: the count of what landed while he was up here stays
    const unread = st.unread;
    st.still = true;
    drawLog();
    st.still = false;
    st.unread = unread;
    drawUnread();
    log.scrollTop = top + (log.scrollHeight - before);
    edge();
  }
  /** More of the conversation past either edge fades out at that edge, so a cut line reads as a scroll and never as
   *  the end, or the start, of what the manager said. */
  function edge() {
    const more = log.scrollHeight - log.scrollTop - log.clientHeight > 2;
    log.parentElement.classList.toggle("below", more);
    log.parentElement.classList.toggle("above", log.scrollTop > 2);
  }
  function drawUnread() {
    const pillEl = sheet.querySelector(".cmp-new");
    const show = st.unread > 0 && st.open;
    pillEl.hidden = !show;
    if (show) pillEl.innerHTML = `<b>${st.unread}</b> new${st.unread > 1 ? " messages" : ""}<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.2" aria-hidden="true"><path d="M12 5v14M6 13l6 6 6-6"/></svg>`;
  }

  // ---------- the line: what a start, a move or a send did, or why not, and the one way out ----------
  /** WHERE IT LANDS, IT LANDS IN PLACE (Oct 26). The line sat between the sheet and the bar, so every refusal lifted
   *  the open sheet by at least 47 px and dropped it again, and a refusal with no act had no way out at all: no timer,
   *  no close, and Escape skipped it. Inside the open sheet the sheet's own height is fixed, so the line takes its room
   *  from the log and nothing moves; closed, it rises over the bar as before. Every line can be put away. */
  function say(next) {
    clearTimeout(lineTimer);
    st.line = next;
    if (next?.title && next.title !== next.text) console.warn(`manager: ${next.title}`);
    if (next?.tone === "ok") lineTimer = setTimeout(() => { if (st.line === next) say(null); }, SAID_MS);
    line.hidden = !next;
    const holder = st.open ? sheet : dock;
    if (line.parentElement !== holder) (st.open ? sheet.insertBefore(line, foot) : dock.insertBefore(line, pill));
    if (!next) { line.innerHTML = ""; return; }
    line.className = `cmp-line ${next.tone}${next.wrap ? " wrap" : ""}`;
    const words = next.html ?? h(next.text);
    line.innerHTML = `<span class="cmp-mark" aria-hidden="true"></span><span class="cmp-text">${words}`
      + `${next.held != null ? ` <q>${h(next.held)}</q>` : ""}</span>`
      + (next.acts?.length ? `<span class="cmp-acts">${next.acts.map((x, i) => `<button type="button" class="act small${x.primary ? " primary" : " quiet"}" data-i="${i}">${h(x.word)}</button>`).join("")}</span>` : "")
      + `<button type="button" class="cmp-x" data-act="said" aria-label="Put this away">${X}</button>`;
  }
  /** The one place a press landed or was refused, marked where he pressed: a refused act shakes once, a change that
   *  landed pulses once. It is held as state, so a redraw an instant later still carries it. */
  function mark(what, cls) {
    st.flash = { what, cls, at: Date.now() };
    flash();
    setTimeout(() => { if (st.flash?.at && Date.now() - st.flash.at >= 900) st.flash = null; }, 1000);
  }
  function flash() {
    const f = st.flash;
    if (!f) return;
    if (Date.now() - f.at > 1200) { st.flash = null; return; }
    const el = dock.querySelector(f.what);
    if (!el || el.classList.contains(f.cls)) return;
    el.classList.remove("shook", "landed");
    void el.offsetWidth;
    el.classList.add(f.cls);
  }

  // ---------- acts ----------
  /** A detail he opened or closed: the row keeps it through every redraw, and the log keeps its own place. */
  function toggle(key) {
    if (!key) return;
    const opening = !st.opened.has(key);
    const shown = opening && key.startsWith("b") ? bodyOf(key.slice(1))?.clientHeight : 0;
    if (opening) st.opened.add(key); else st.opened.delete(key);
    const top = log.scrollTop;
    drawLog();
    log.scrollTop = top;
    if (shown) grow(key.slice(1), shown);
    if (opening) setTimeout(() => reveal(key), 260);
  }
  const bodyOf = (k) => log.querySelector(`.cmp-m[data-k="${CSS.escape(k)}"] > .cmp-md`);
  /** WHAT HE OPENED IS WHAT HE READS (Oct 28): a call opened near the log's foot grew below the edge and left him to scroll for it.
   *  Once it has grown, the log moves just far enough to show its end, and not at all when it already shows. */
  function reveal(key) {
    const row = log.querySelector(`[data-k="${CSS.escape(key)}"]`);
    if (!row || sheet.hidden) return;
    const over = row.getBoundingClientRect().bottom - log.getBoundingClientRect().bottom + 16;
    if (over > 0) log.scrollBy({ top: over, behavior: "smooth" });
  }
  /** A LONG MESSAGE OPENS OVER --t-snap (Oct 27): it jumped from six lines to its whole height in one frame. It grows
   *  from the lines it showed [from] to its full height, in the console's own time and easing for a change in place. */
  function grow(k, from) {
    const md = bodyOf(k);
    const root = getComputedStyle(document.documentElement);
    const ms = parseFloat(root.getPropertyValue("--t-snap")) || 0;
    if (!md || !ms || !md.animate) return;
    md.animate([{ maxHeight: `${from}px`, overflow: "hidden" }, { maxHeight: `${md.scrollHeight}px`, overflow: "hidden" }],
      { duration: ms, easing: root.getPropertyValue("--e-out").trim() || "ease-out" });
  }
  function openSheet() {
    if (!st.m || st.m.state === "off") return;
    st.open = true; st.card = false;
    readTints();
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
    readTints();
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
    // ONE PHASE, SAID IN ONE PLACE (Oct 26): it was written into the field's hint, the foot's state word and this line
    // at once. The open sheet's header says it; closed, this line does, and the lamp breathes either way.
    const progress = st.open ? null : { tone: "busy", text: phaseWord() };
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
  /** WHAT HAPPENED, IN A FEW PLAIN WORDS, WITH THE ACT BESIDE THEM (Oct 27). The lines read "splice answered 503" and
   *  two-sentence notices; the daemon's own sentence goes to the browser's console (say), and its terminal on Sessions
   *  shows the rest. */
  function startRefused(res, head, kind) {
    const why = res.body?.error || "", label = labelOf(head);
    const again = { word: "Try again", primary: true, run: () => start(head, kind) };
    const other = { word: "Choose another command", run: openCard };
    // the press that did not take shakes once, where he pressed it
    mark(st.card ? '.cmp-card [data-act="start"]' : ".cmp-send", "shook");
    const plain = (text, acts = [again]) => say({ tone: "refused", text, title: why || text, acts });
    switch (res.body?.reason) {
      case "not_installed": installLine(label, why, [other, again]); return;
      case "head_unknown": plain(`Not started · no command called ${head || "that"}`, [{ ...other, primary: true }]); return;
      case "launch_failed": plain(`Not started on ${label}`, [other, again]); return;
      case "folder_missing": plain("Not started · No settings folder"); return;
      default:
        if (res.status === 503) plain("Not started · No terminal");
        else if (res.status === 404) plain("This splice has no manager", []);
        else if (res.status === 0) plain("splice is not answering");
        else plain("Not started");
    }
  }
  /** A command that is not installed: the command that installs every one, and Copy beside it. */
  function installLine(label, why, acts) {
    const copy = { word: "Copy", run: copyInstall };
    say({ tone: "refused", wrap: true, text: `${label} is not installed`, title: why,
      html: `${h(label)} is not installed. Run <code>${INSTALL_LINE}</code>`, acts: [copy, ...acts] });
  }
  async function copyInstall() {
    let ok = true;
    try { await navigator.clipboard.writeText(INSTALL_LINE); } catch { ok = false; }
    say({ tone: ok ? "ok" : "refused", text: ok ? "Copied" : "Could not copy" });
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
      // WHERE THE MOVE LANDED, PULSED, NOT NARRATED (Oct 26): a sentence said "The manager resumes on X next time",
      // which is what the head chip itself now shows. The chip pulses once, so the eye finds the change.
      mark('.cmp-foot [data-menu="head"]', "landed");
    } else moveRefused(res, key, m.head);
    draw();
    tick();
  }
  /** A move that did not happen: what held it, the act that clears it, and a shake on the chip he pressed. The holds
   *  (an answer waiting, unsent text) are holds, as they are on the send path, not failures. */
  function moveRefused(res, key, from) {
    const why = res.body?.error || "";
    mark('.cmp-foot [data-menu="head"]', "shook");
    const plain = (text, acts = [], tone = "refused") => say({ tone, text, title: why || text, acts });
    const look = { word: "Open on Sessions", run: go };
    switch (res.body?.reason) {
      case "waiting": plain("The manager is waiting on an answer", [{ word: "Answer it", primary: true, run: openSheet }], "held"); return;
      case "same_head": plain(`It already runs on ${labelOf(key)}`); return;
      case "draft": plain("Its prompt has unsent text", [{ word: "Open on Sessions", run: go }], "held"); return;
      case "not_installed": installLine(labelOf(key), why, [{ word: "Choose another command", run: () => { st.menu = "head"; drawFoot(); } }]); return;
      default:
        plain(res.status === 0 ? `splice is not answering. It stays on ${labelOf(from)}` : `Not moved. It stays on ${labelOf(from)}`, [look]);
    }
  }
  /** His message, whole, to the manager. A manager that is not running resumes first; words that did not go through stay
   *  in the field. */
  async function submit(clear = false) {
    if (st.gone === "down") { retry(); return; }
    if (st.busy || st.phase || !st.m) return;
    const m = st.m, text = field.value.trim();
    if (unasked(m)) { openCard(); return; }
    // Answer: the sheet opens on what it asks, whose choices are the act
    if (m.state === "live" && m.status === "waiting" && !st.open) { openSheet(); return; }
    if (m.state === "resumable") {
      if (await start(m.head, "resume") && text) await submit(clear);
      return;
    }
    // an empty Enter opens the conversation, as a press on the field does
    if (!text) { openSheet(); field.focus(); return; }
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
  /** splice did not answer: read it again now, the button spinning while the read is out, and shaking if it still
   *  does not answer. */
  async function retry() {
    if (st.retrying) return;
    st.retrying = true; drawPill();
    await readAll();
    st.retrying = false; draw();
    if (st.gone === "down") mark(".cmp-send", "shook");
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
  /** THE CONTEXT METER CARRIES THE COMPACTION (Oct 26). A sentence used to narrate the press he had just made and clear
   *  itself after four seconds, while the Compact button went back to looking ready and invited a second press. Now the
   *  meter it changes is marked as being worked on, and the figure dropping is the result. */
  async function compact() {
    if (!canSteer() || !(ctxOf()?.used > 0)) return;
    st.compacting = { at: Date.now(), was: ctxOf()?.percent ?? null };
    drawMeters();
    if (!(await deliver("/compact"))) { st.compacting = null; drawMeters(); return; }
    draw();
    setTimeout(tick, WANT_POLL_MS);
  }
  const ctxOf = () => lineOf()?.context ?? null;
  /** Sessions' own Stop: it ends the turn and leaves the conversation waiting for his next message. */
  async function stop() {
    if (!st.sid || st.stopping || st.m?.status !== "working") return;
    st.stopping = true; say(null); draw();
    const res = await API.post(route(st.sid, "stop"));
    if (!res.ok) {
      st.stopping = false;
      draw();
      mark('.cmp-foot [data-act="stop"]', "shook");
      say({ tone: "refused", text: res.status === 0 ? "splice is not answering. Nothing was stopped" : "Not stopped",
        title: res.body?.error || "", acts: [{ word: "Open on Sessions", run: go }] });
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
  // HIS WORDS WAIT WHILE THE MANAGER ASKS (Oct 28): the sheet opens on the question, whose lamp and choices say it is asking,
  // and the choices shake once where his eye lands; the sentence that said it again is gone
  function asking() {
    openSheet();
    mark(".cmp-choices", "shook");
  }
  /** Why /say did not type, in plain words, with the way out it offers (SessionDrive's refusals, by their reason). */
  function refused(res) {
    const why = res.body?.error || "";
    const plain = (text, acts) => say({ tone: "refused", text, title: why || text, acts });
    const look = { word: "Open on Sessions", run: go };
    switch (res.body?.reason) {
      case "draft":
        say({ tone: "held", text: "The manager's prompt already has unsent text", held: oneLine(res.body.draft),
          acts: [{ word: "Cancel", run: () => { say(null); field.focus(); } }, { word: "Clear and send", primary: true, run: () => submit(true) }] });
        return;
      case "closed":
      case "pane_gone":
        // its terminal is gone: the manager is resumable, and the act on the line resumes it, his words still in the field
        st.m = { ...st.m, state: "resumable", status: null };
        plain("Not sent · Terminal closed", [{ word: "Resume", primary: true, run: () => submit() }]);
        setTimeout(tick, 400);
        return;
      case "not_ours": plain("Not sent · Not splice's terminal", [look]); return;
      case "pane_taken": plain("Not sent · Terminal reused", [look]); return;
      case "no_terminal": plain("Not sent · No terminal", [look]); return;
      case "refused": plain("Not sent · Terminal refused it", [look]); return;
      default: plain(res.status === 0 ? "splice is not answering · Not sent" : "Not sent", [look]);
    }
  }
  /** Press the numbered choice he picked, as Sessions' needs-you card does; the label guards against a menu that moved. */
  async function answer(choice, label) {
    if (st.answering || !st.sid) return;
    st.answering = choice; say(null); draw();
    const res = await API.post(route(st.sid, "answer"), { choice, label });
    st.answering = null;
    if (res.ok) st.ask = null;
    else if (res.body?.reason === "screen_moved") {
      // THE CHOICES THEMSELVES SAY IT CHANGED (Oct 26): the console reads the screen again and redraws them, so the
      // shake belongs on the new choices, where his eye already is, and nothing else needs saying.
      await readScreen();
      draw();
      mark(".cmp-choices", "shook");
    } else {
      draw();
      mark(`.cmp-ask [data-choice="${choice}"]`, "shook");
      say({ tone: "refused", text: res.status === 0 ? "splice is not answering. Nothing was pressed" : "Not pressed",
        title: res.body?.error || "", acts: [{ word: "Open on Sessions", run: go }] });
    }
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
  /** ONE HOME AND ONE WIDTH (Oct 27). The bar sits at the bottom centre of every page, and it is the same bar on each:
   *  its own width (composer.css .cmp), bottom centre, so the shortcut's key, the hint and Send are where they were on
   *  the page before. It used to step aside and narrow itself under a tall pane, so it stood at one x on five pages and
   *  another on a sixth, and at 432 and 493 px on two, where the narrow bar hid its own Ctrl+K.
   *
   *  What it covers is scrolled clear instead of moved around: the page is the only thing that scrolls (console.css ONE
   *  SCROLL PER PAGE), and whatever the bar is tall right now, plus 4u, is the room every page's scroll reserves at its
   *  end (composer.css main.page::after), so the last line of any page, a pane's own field and buttons included, stops
   *  above the bar. An open sheet is taller, and reserves its own height the same way. */
  function place() {
    if (dock.style.left) dock.style.left = "";
    if (dock.style.width) dock.style.width = "";
    fitBar();
    room();
    tuck();
  }
  /** WHILE THE PAGE GOES ON UNDER IT, AN IDLE BAR FOLDS INTO THE WINDOW'S MARGIN (Oct 27). Mid-scroll the bar stood over
   *  the page's own lines: two answers' middle lines on Compare, a pane's last rows on Settings, the last cards on
   *  Accounts, and a press near the window's foot could land on it. Folded to a smaller bar at its own home it still
   *  stood over a line of a page here and there, so it folds to a tab in the margin the page keeps clear (composer.css
   *  .cmp-tab) and covers nothing at any scroll. At the end of a scroll the reserve already holds the whole bar clear, so
   *  there it is the whole bar. A press on the tab, Ctrl+K or Tab brings it back. It never folds while it has something
   *  for him: an open sheet or card, a menu, a line it is saying, words in the field, a send under way, or the manager
   *  waiting on his answer. */
  function tuck() {
    if (!dock || !field) return;
    // nor before the first read lands, whose striped hint is the only sign the bar is still being read, nor while the
    // read fails: "splice is not answering" and its Try again, or a splice with no manager, stay said in full
    const m = st.m, idle = !st.open && !st.card && !st.menu && !st.line && !st.busy && !st.phase && !field.value.trim()
      && !(m?.state === "live" && m.status === "waiting") && Boolean(m) && !st.gone;
    const doc = document.scrollingElement || document.documentElement;
    const under = doc.scrollHeight - (doc.scrollTop + innerHeight) > 8;
    dock.classList.toggle("tucked", idle && under && !locked());
  }
  /** THE BAR READS ON ONE LINE IN A NARROW COLUMN (Oct 26). Stepped aside under a 27 rem list at 1440 it had 331 px,
   *  and "Message the splice manager" wrapped onto a second line that the bar then clipped. The room is measured, not
   *  guessed at a width: what the hint needs against what the field has once the lamp, the button and the gaps are
   *  taken out. Too tight, and the shortcut's key steps aside (composer.css .cmp.narrow); tighter still, and the hint
   *  is the short one (drawPill). Both tests read the same measurements, so neither can flip the other back. */
  function fitBar() {
    if (!pill || !field) return;
    // a folded bar is not drawn, so the last whole bar's reading stands until it is back
    if (dock.classList.contains("tucked") && !dock.matches(":focus-within")) return;
    // nor while it is folding or opening: half way, it would read a width it is only passing through (the end of the
    // move measures it again)
    if (st.sizing) return;
    const cs = getComputedStyle(pill), gap = parseFloat(cs.gap) || 0;
    const w = (el) => (el && !el.hidden ? el.getBoundingClientRect().width : 0);
    const keyNow = w(dock.querySelector(".cmp-k"));
    if (keyNow) st.keyW = keyNow;
    const inner = pill.getBoundingClientRect().width - parseFloat(cs.paddingLeft) - parseFloat(cs.paddingRight);
    const room = inner - w(lamp) - w(send) - 2 * gap;
    const fs = getComputedStyle(field);
    const need = textPx(HINT, fs) + parseFloat(fs.paddingLeft) + parseFloat(fs.paddingRight) + 2;
    const hideKey = room - (st.keyW || 0) - gap < need, short = room < need;
    if (hideKey !== st.narrow) { st.narrow = hideKey; dock.classList.toggle("narrow", hideKey); }
    if (short !== st.shortHint) { st.shortHint = short; drawPill(); }
  }
  /** How wide a run of words is in a field's own type, so a hint is shortened only when it would wrap. */
  let gauge = null;
  function textPx(text, cs) {
    gauge ??= document.createElement("canvas").getContext("2d");
    gauge.font = `${cs.fontStyle} ${cs.fontWeight} ${cs.fontSize} ${cs.fontFamily}`;
    return gauge.measureText(text).width;
  }
  /** The room the bar takes, published to the page as --cmp-room: its own height, from the top of whatever it shows
   *  (the pill, or the sheet above it) to the bottom of the window. A page that is locked, or that hides the bar behind
   *  a sheet's scrim, reserves nothing. */
  function room() {
    const hidden = locked() || document.querySelector(".scrim") || !dock.isConnected;
    const px = hidden ? 0 : Math.round(innerHeight - dock.getBoundingClientRect().top);
    const was = document.documentElement.style.getPropertyValue("--cmp-room");
    const now = `${Math.max(0, px)}px`;
    if (was !== now) document.documentElement.style.setProperty("--cmp-room", now);
  }

  // ---------- mounting ----------
  function mount() {
    dock = document.createElement("div");
    dock.className = "cmp";
    dock.setAttribute("role", "region");
    dock.setAttribute("aria-label", "The splice manager");
    dock.innerHTML = `<section class="cmp-card" role="dialog" aria-labelledby="cmp-card-h" hidden></section>`
      + `<section class="cmp-sheet" aria-label="Conversation with the splice manager" hidden>`
      + `<header class="cmp-sh"></header>`
      + `<div class="cmp-body"><div class="cmp-log" role="log" aria-live="polite" tabindex="0"></div>`
      + `<button type="button" class="cmp-new morepill pill" data-act="newest" hidden></button></div>`
      + `<div class="cmp-ask" role="group" aria-label="The manager asks" hidden></div>`
      + `<div class="cmp-meters" role="group" aria-label="Context and limits" hidden></div>`
      + `<footer class="cmp-foot"></footer></section>`
      + `<div class="cmp-line" role="status" aria-live="polite" hidden></div>`
      + `<form class="cmp-pill" novalidate><span class="cmp-lamp off" aria-hidden="true"><i></i></span>`
      + `<button type="button" class="cmp-door" hidden><span>Start the splice manager</span><kbd>${KEYS}</kbd></button>`
      + `<span class="cmp-wait" aria-hidden="true" hidden>${GHOST.bar(16)}</span>`
      + `<span class="cmp-said" hidden></span>`
      + `<textarea class="cmp-field" rows="1" aria-label="Message the splice manager" autocomplete="off"></textarea>`
      + `<kbd class="cmp-k" aria-hidden="true">${KEYS}</kbd>`
      + `<button type="submit" class="act primary cmp-send" disabled>Send</button></form>`;
    card = dock.querySelector(".cmp-card"); sheet = dock.querySelector(".cmp-sheet");
    log = dock.querySelector(".cmp-log");
    ask = dock.querySelector(".cmp-ask"); meters = dock.querySelector(".cmp-meters"); foot = dock.querySelector(".cmp-foot"); line = dock.querySelector(".cmp-line");
    pill = dock.querySelector(".cmp-pill");
    lamp = dock.querySelector(".cmp-pill > .cmp-lamp"); door = dock.querySelector(".cmp-door"); said = dock.querySelector(".cmp-said");
    field = dock.querySelector(".cmp-field"); send = dock.querySelector(".cmp-send");
    dim = document.createElement("div");
    dim.className = "cmp-dim";
    dim.setAttribute("aria-hidden", "true");
    // where the bar stands while it is folded (composer.css .cmp-tab): after the bar, which its style reads
    tab = document.createElement("button");
    tab.type = "button";
    tab.className = "cmp-tab";
    tab.title = `The splice manager · ${KEYS}`;
    tab.innerHTML = `<span class="cmp-lamp off" aria-hidden="true"><i></i></span><span class="cmp-tabword">Manager</span>`;
    tabLamp = tab.querySelector(".cmp-lamp");
    document.body.append(dim, dock, tab);

    dock.querySelector("form").addEventListener("submit", (e) => { e.preventDefault(); submit(); });
    door.addEventListener("click", openCard);
    field.addEventListener("input", () => { st.recall = -1; fit(); drawPill(); });
    // A PRESS ON THE FIELD OPENS THE CONVERSATION; FOCUS ALONE DOES NOT (Oct 27). Focus opened it too, so the close
    // cross, which hands focus back to the field, closed the sheet and opened it again in the same instant.
    const reveal = () => { if (!st.open && st.sid && st.m && st.m.state !== "off") openSheet(); };
    field.addEventListener("click", reveal);
    // Ctrl+K and a press on the folded tab bring the bar back where it was and take him to it: the door's card when the
    // manager was never asked, else the field with the conversation open
    const summon = () => {
      dock.classList.remove("tucked");
      if (!door.hidden) { openCard(); return; }
      field.focus();
      reveal();
    };
    tab.addEventListener("click", summon);
    // done with it, the bar folds again over a page that goes on
    dock.addEventListener("focusout", (e) => { if (!dock.contains(e.relatedTarget)) setTimeout(tuck, 0); });
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
        case "said": say(null); return;                                   // a line he has read goes away where it stands
        case "newest": log.scrollTop = log.scrollHeight; st.unread = 0; drawUnread(); return;
        case "older": older(); return;
        case "copyinstall": copyInstall(); return;
        // a call, a burst, a long body and a long result each open and close in place, and stay as he left them
        case "call": toggle(act.dataset.k); return;
        case "more": toggle(`b${act.dataset.k}`); return;
        case "moreres": toggle(`r${act.dataset.k}`); return;
        case "menu": {
          const kind = act.dataset.menu;
          st.menu = st.menu === kind ? null : kind;
          if (st.menu === "steer") readModels();
          drawFoot();
          if (st.menu) { const own = foot.querySelector(`.cmp-menu[data-for="${kind}"]`); (own?.querySelector('[aria-checked="true"]:not(:disabled)') || own?.querySelector(".cmp-hd:not(:disabled)"))?.focus(); }
          return;
        }
        case "head": move(act.dataset.head); return;
        // the one it runs already, or the effort it has, closes the menu: a change to the same costs a re-read for nothing
        case "model": if (act.getAttribute("aria-checked") !== "true") steer("model", act.dataset.id, act.dataset.label); else { st.menu = null; drawFoot(); } return;
        case "effort": if (act.getAttribute("aria-pressed") !== "true") steer("effort", act.dataset.v, effortWord(act.dataset.v)); else { st.menu = null; drawFoot(); } return;
        case "remodels": st.models = null; drawFoot(); readModels(); return;
        case "reread": st.log = null; drawLog(); readAll(); return;
        case "compact": compact(); return;
        case "stop": stop(); return;
        case "answer": answer(Number(act.dataset.choice), act.dataset.label); return;
        case "go": e.preventDefault(); go(); return;
        default:
      }
    });
    // what he opened stays open through every redraw; the log keeps its place when a detail opens above the fold
    log.addEventListener("scroll", () => {
      if (log.scrollHeight - log.scrollTop - log.clientHeight < FOLLOW_PX && st.unread) { st.unread = 0; drawUnread(); }
      edge();
    }, { passive: true });
    // its keys are its own: Escape here never also closes what the page has open (Sessions' in-prompt card, a menu)
    dock.addEventListener("keydown", (e) => {
      e.stopPropagation();
      if (e.key !== "Escape") return;
      e.preventDefault();
      if (st.menu) { const was = st.menu; st.menu = null; drawFoot(); foot.querySelector(`[data-menu="${was}"]`)?.focus(); return; }
      // a line with no act was the one thing Escape skipped, so a refusal with nothing to press stayed up for good
      if (st.line) { say(null); return; }
      if (st.open) { closeSheet(); return; }
      if (st.card) { notNow(); return; }
      document.activeElement?.blur();
    });
    // Only the person's own press or key reaches these: the manager's acts on the page (pilot.js) are not theirs, and
    // never close the sheet they are reading or pull the field open under them.
    document.addEventListener("pointerdown", (e) => { if (e.isTrusted && (st.open || st.menu) && !dock.contains(e.target)) closeSheet(); }, true);
    addEventListener("keydown", (e) => {
      if (!e.isTrusted || !(e.ctrlKey || e.metaKey) || e.altKey || e.shiftKey || String(e.key).toLowerCase() !== "k") return;
      if (locked() || document.querySelector(".scrim")) return;
      e.preventDefault(); e.stopPropagation();
      summon();
    }, true);
    // a page opens or closes its pane on its own clock, so where the pill stands is looked at a little more than once a second
    setInterval(() => { if (!locked()) place(); }, 700);
    // a clamped body re-wraps when the window's width changes or the console's faces land, so its edge moves with it
    addEventListener("resize", () => { place(); countLines(true); });
    addEventListener("scroll", tuck, { passive: true });
    const sizing = (on) => (e) => { if (e.target !== dock || e.propertyName !== "width") return; st.sizing = on; if (!on) fitBar(); };
    dock.addEventListener("transitionrun", sizing(true));
    dock.addEventListener("transitionend", sizing(false));
    dock.addEventListener("transitioncancel", sizing(false));
    document.fonts?.ready.then(() => countLines(true));
    // the first read of the manager has six seconds, the same a page's read has before it says it is slow (kit.js watchGhosts)
    setTimeout(() => { if (!st.m && !st.gone) { st.slow = true; drawPill(); } }, 6_000);
    document.addEventListener("visibilitychange", () => { if (!document.hidden) tick(); });
    draw();
    place();
    tick();
  }
  // after console.js's own DOMContentLoaded (it loads first), so the router never counts the dock among the page's nodes
  if (document.readyState === "loading") addEventListener("DOMContentLoaded", mount); else mount();
})();
