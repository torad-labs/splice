/* Sessions, Teams, then the work: the approved Night Deck board (prototype sessions/i-teams-reader), on splice's live
   reads. The board is the bird's-eye view: a column per team, its header carrying the team's health (one lamp in its
   worst state, the counts, an activity spark over the hour, a state strip). Columns reflow to the width and quiet
   teams fold to their headers. Opening a session grows its row into its conversation with a live band above it (this
   session's lane and its teammates', on a 30-minute axis), the question waiting on him as a Deck.answer above the
   composer, and the steering one move away. Back returns to the board where he left it.

   Reads (polled as the old page polled them): /api/sessions, /api/heads and each head's /turns/live every 5 s;
   /api/teams every 5 s; each head's /api/perf/turns over the last hour every 30 s (the activity and state history);
   /api/accounts every 30 s; a waiting session's /screen; /api/sessions/history for a search. The open session's
   conversation is read by the shared session reader's connector (reply-view/connect.js: its /transcript pages and its
   /live feed). Writes go where the old console sent them. */
(() => {
  'use strict';
  const { $, h, iconBtn, ICON, ago } = Deck;
  const MIN = 60000, WIN = 60, BAND_MS = 30 * MIN, LOOK_MS = 14 * MIN;
  const READ_MS = 5000, PERF_MS = 30000;
  const DPR = Math.min(2, window.devicePixelRatio || 1);
  const RANK = { needs: 0, stalled: 1, working: 2, idle: 3 };
  const PHASE = { connect: 'Sent, no output yet', streaming: 'Streaming' };
  // how a request that held a session back ended, in the console's words (the old kit's OUTCOME_WORD)
  const ENDING = {
    'error:auth-missing': ['signout', 'Signed out', 'Its login is signed out'], 'error:plan-limit': ['limit', 'At limit', 'Its plan is at a limit'],
    'error:all-accounts-exhausted': ['limit', 'At limit', 'Every account is at its limit'], 'error:upstream-failed': ['gaveup', 'Provider error', 'Splice stopped retrying'],
    'error:turn-cap': ['gaveup', 'Given up', 'No progress from the model'],
  };
  // a silence shows as stalled past this, unless splice will re-send it itself (the head's re-anchor)
  const STALL_SHOWN_MS = 30000, MAX_CONTINUATIONS = 5;
  const NAMES_KEY = 'splice.sessions.names';
  const ORDER_KEY = 'splice.sessions.team-order';

  /* ---------- small helpers ---------- */
  const rgbOf = name => (Deck.css(name).match(/[\d.]+/g) || [0, 0, 0]).slice(0, 3).map(Number);
  const C = { verd: rgbOf('--verdigris'), verm: rgbOf('--vermilion'), ink: rgbOf('--ink'), prus: rgbOf('--prussian') };
  const rgba = (c, a) => 'rgba(' + c[0] + ',' + c[1] + ',' + c[2] + ',' + a + ')';
  const setTxt = (el, v) => { if (el && el._t !== v) { el._t = v; el.textContent = v; } };
  /** hides or shows [el] only when that changes: a tick that sets what is already so writes nothing */
  const setHidden = (el, v) => { if (el && el.hidden !== v) el.hidden = v; };
  const agoText = ms => ms < 1000 ? 'now' : ago(ms) + ' ago';
  const slug = t => t.name.trim().toLowerCase().replace(/\s+/g, '-');
  const enc = encodeURIComponent;
  const providerOf = s => SPLICE.providerOf(s.head) || null;
  const providerName = s => { const p = providerOf(s); return p ? p.name : 'Direct'; };
  const epoch = v => (typeof v === 'number' ? v : Date.parse(v || '')) || null;
  function modelLabel(s) {
    if (!s.model) return null;
    const n = SPLICE.modelName(s.model);
    if (n !== s.model) return n;
    return s.model.replace(/^claude-/, '').replace(/-(\d)-(\d)$/, ' $1.$2').replace(/-/g, ' ').replace(/^\w/, c => c.toUpperCase());
  }
  const askKey = a => a.question + '|' + a.choices.join('|');
  const isTyping = () => /INPUT|TEXTAREA/.test(document.activeElement && document.activeElement.tagName);
  const names = (() => { try { return new Map(Object.entries(JSON.parse(localStorage.getItem(NAMES_KEY) || '{}'))); } catch { return new Map(); } })();
  function keepName(id, name) {
    if (name) names.set(id, name); else names.delete(id);
    try { localStorage.setItem(NAMES_KEY, JSON.stringify(Object.fromEntries(names))); } catch { /* storage off: the name lasts until the tab closes */ }
  }
  /** what the daemon answered, in the kit's words: the refusal's own reason, or that splice did not answer */
  const why = res => (res.status === 0 ? 'splice is not answering' : (res.body && (res.body.error || res.body.reason)) || 'splice answered ' + res.status);

  /* ---------- the live read: each session in the board's shape ---------- */
  const S = new Map(), GONE = new Map(), SCREEN = new Map(), seen = new Map();
  let heads = [], teamRows = [], accounts = [], perfRows = new Map(), readOk = null, readAt = 0, reading = true;
  let suggestedTasks = [], sessionChapters = [];
  let events = [];

  /** a session's state, as the console has always read it: waiting on him, working, stalled, idle */
  function stateOf(row, turn, head) {
    if (row.waiting_for) return { state: 'needs', stall: null };
    const working = Boolean(turn) || (row.availability !== 'stale' && (row.status === 'working' || row.status === 'busy'));
    const stall = stallOf(turn, head, row.ended_by);
    if (stall) return { state: 'stalled', stall };
    return { state: working ? 'working' : 'idle', stall: null };
  }
  function stallOf(turn, head, ended) {
    if (!turn) {
      const e = ended && ENDING[ended.outcome];
      if (!e) return null;
      if (e[0] === 'limit' && ended.reset_ms != null && ended.reset_ms <= Date.now()) return null;
      return { kind: e[0], word: e[1], detail: e[0] === 'limit' && ended.reset_ms ? 'resets in ' + ago(ended.reset_ms - Date.now()) : e[2] };
    }
    const idle = turn.idle_ms || 0;
    if (turn.retries > 0 && !turn.seen_output) return { kind: 'retries', word: 'Retrying', detail: turn.retries + (turn.retries === 1 ? ' retry' : ' retries') };
    const reanchor = head && head.gate ? head.gate.stall_reanchor_ms : null;
    const wontResume = turn.seen_output === true && turn.will_resume === false;
    if (reanchor && !wontResume && (turn.resumes || 0) < MAX_CONTINUATIONS) return null; // splice re-sends it itself
    const limit = Math.min(STALL_SHOWN_MS, (head && head.gate ? (turn.seen_output ? head.gate.stream_idle_ms : head.gate.first_byte_timeout_ms) : null) || STALL_SHOWN_MS);
    return idle >= limit ? { kind: 'silent', word: 'Silent', detail: ago(idle) + ' without output' } : null;
  }
  function askOf(row, screen) {
    if (!row.waiting_for) return null;
    const asked = row.last && Array.isArray(row.last.asks) ? row.last.asks[0] : null;
    if (asked && Array.isArray(asked.options) && asked.options.length) {
      return { kind: 'input', question: asked.question || row.waiting_for, choices: asked.options.slice(), picks: asked.options.map((_, i) => i + 1) };
    }
    const offered = screen && Array.isArray(screen.choices) ? screen.choices : [];
    const call = row.last && row.last.tool ? row.last : null;
    let what = '';
    if (call) { try { const o = JSON.parse(call.text || ''); what = o.command || o.file_path || o.description || ''; } catch { what = call.text || ''; } }
    return {
      kind: 'dialog', tool: call ? call.tool : undefined, what: what || undefined,
      question: (screen && screen.asked) || row.waiting_for, choices: offered.map(c => c.label), picks: offered.map(c => c.choice),
    };
  }
  function sessionOf(row, lives, waits) {
    const id = row.session_id, turn = lives.get(id) || null, head = heads.find(x => x.key === row.head);
    const { state, stall } = stateOf(row, turn, head);
    const was = S.get(id) || {};
    const since = was.state === state && was.since ? was.since
      : state === 'working' && turn && turn.age_ms != null ? Date.now() - turn.age_ms : epoch(row.status_updated_at) || Date.now();
    const wt = row.repo && row.repo.worktree ? row.repo.worktree.split('/').filter(Boolean).pop() : null;
    const wait = waits.get(id);
    return Object.assign(was, {
      id, row, head: row.head, name: names.get(id) || row.name || row.title || id.slice(0, 8),
      project: row.project ? row.project.name || row.project.label || null : null, worktree: wt, place: row.cwd || '',
      command: head ? head.label || head.key : null, route: row.route,
      model: (turn && turn.model) || row.model || row.served_model || null, account: row.account || null,
      state, stall, stale: row.availability === 'stale', since: Math.min(since, Date.now()),
      startedAt: epoch(row.started_at) || epoch(row.updated_at) || Date.now(), turns: row.turns == null ? null : row.turns,
      version: row.version || '', last: row.last ? { tool: row.last.tool || null, text: row.last.text || '' } : null,
      request: turn ? { phase: turn.phase || (wait ? 'queued' : null), compact: !!turn.compact, retries: turn.retries || 0, model: turn.model, startAbs: Date.now() - (turn.age_ms || 0), turn: turn.id }
        : wait ? { phase: 'queued', compact: false, retries: 0, model: null, startAbs: Date.now() - (wait.waited_ms || 0) } : null,
      ask: state === 'needs' ? askOf(row, SCREEN.get(id)) : null,
      window: row.client_context_window || null,
      seenAt: was.seenAt || Date.now() - LOOK_MS,
    });
  }
  /** the hour of a session from its requests: per minute, working where splice served it a request */
  // a request row names its session by the first eight characters of its id, as the old Sessions page asked for it
  function ticksOf(id) { return perfRows.get(String(id).slice(0, 8)) || []; }
  function histOf(s) {
    const out = new Array(WIN).fill('idle'), t = Date.now();
    for (const k of ticksOf(s.id)) { const b = WIN - 1 - Math.floor((t - k.t) / MIN); if (b >= 0 && b < WIN) out[b] = 'working'; }
    out[WIN - 1] = s.state;
    return out;
  }

  async function read() {
    const [sessions, hd, tm, st, ch] = await Promise.all([
      API.get('/api/sessions'), API.get('/api/heads'), API.get('/api/teams'),
      API.get('/api/suggested-tasks'), API.get('/api/chapters'),
    ]);
    readOk = sessions.ok; if (sessions.ok) readAt = Date.now();
    if (hd.ok) heads = (hd.body && hd.body.heads) || [];
    if (tm.ok) teamRows = ((tm.body && tm.body.teams) || []).filter(t => !t.archived);
    if (st.ok) suggestedTasks = (st.body && st.body.tasks) || [];
    if (ch.ok) sessionChapters = (ch.body && ch.body.chapters) || [];
    if (!sessions.ok) { paint(); return; }
    reading = !(sessions.body && sessions.body.reading === false);
    const lives = new Map(), waits = new Map();
    const live = await Promise.all(heads.map(x => API.get('/api/heads/' + enc(x.key) + '/turns/live')));
    live.forEach(r => {
      for (const t of (r.body && r.body.turns) || []) if (t.session && !t.stopped) lives.set(t.session, t);
      for (const w of (r.body && r.body.waiting) || []) if (w.session) waits.set(w.session, w);
    });
    const rows = ((sessions.body && sessions.body.sessions) || []).filter(r => r.session_id);
    const here = new Set();
    GONE.clear();
    for (const row of rows) {
      if (row.availability === 'gone') { GONE.set(row.session_id, row); continue; }
      here.add(row.session_id);
      const before = S.get(row.session_id), was = before && before.state;
      const s = sessionOf(row, lives, waits);
      if (!S.has(s.id)) S.set(s.id, s);
      if (was && was !== s.state) {
        const kind = { idle: 'finished', working: 'started', needs: 'asked', stalled: 'stuck' }[s.state];
        if (kind) events.push({ t: Date.now(), kind });
      }
    }
    for (const id of [...S.keys()]) if (!here.has(id) && !S.get(id).ended) { S.delete(id); const r = rowEl.get(id); rowEl.delete(id); if (r) r.remove(); if (openId === id) closeOpen(); }
    syncTeams();
    await readScreens();
    renderIfChanged();
    paint();
  }
  /** the screens of the sessions that wait on him: what each offers to press */
  async function readScreens() {
    const ids = [...S.values()].filter(s => s.state === 'needs' && !(s.row.last && s.row.last.asks && s.row.last.asks.length)).map(s => s.id);
    const got = await Promise.all(ids.map(id => API.get('/api/sessions/' + enc(id) + '/screen')));
    ids.forEach((id, i) => { if (got[i].ok) SCREEN.set(id, got[i].body); const s = S.get(id); if (s) s.ask = askOf(s.row, SCREEN.get(id)); });
  }
  /** each command's requests over the last hour, by session: the sparks, the strips and the band's ticks. They take the
   *  console's performance queue (api.js perfRequest), four at a time inside the daemon's admission, as every page's do. */
  async function readPerf() {
    const since = Date.now() - WIN * MIN;
    const got = await Promise.all(heads.map(x => API.perfRequest('perf:sessions:' + x.key, 'GET', '/api/perf/turns?' + new URLSearchParams({ head: x.key, since: String(since), n: '2000', usage: '0' })).then(r => r || { ok: false, body: null })));
    const next = new Map();
    got.forEach(r => {
      for (const hd of (r.body && r.body.heads) || []) for (const row of hd.rows || []) {
        const sid = String(row.session || row.session_id || '').slice(0, 8); if (!sid) continue;
        if (!next.has(sid)) next.set(sid, []);
        next.get(sid).push({ t: epoch(row.ts) || 0, kind: 'tool', ok: row.outcome === 'ok', tokens: (row.in_tokens || 0) + (row.cached_tokens || 0) + (row.cache_write_tokens || 0) });
      }
    });
    for (const list of next.values()) list.sort((a, b) => a.t - b.t);
    if (got.some(r => r.ok)) perfRows = next;
  }
  async function readAccounts() {
    const r = await API.get('/api/accounts');
    if (r.ok) accounts = (r.body && r.body.accounts) || [];
  }

  /* ---------- teams: splice's own, each slot bound to a session ---------- */
  let teams = [];
  const order = (() => { try { return JSON.parse(localStorage.getItem(ORDER_KEY) || '[]'); } catch { return []; } })();
  function syncTeams() {
    const byId = new Map(teamRows.map(t => [t.id, t]));
    const ids = [...order.filter(id => byId.has(id)), ...teamRows.map(t => t.id).filter(id => !order.includes(id))];
    teams = ids.map(id => {
      const t = byId.get(id), slots = t.slots || [];
      const members = slots.map(sl => sl.session).filter(sid => sid && S.has(sid));
      const lead = (slots.find(sl => sl.lead && sl.session && S.has(sl.session)) || {}).session || null;
      return { id, name: t.name, goal: t.goal || '', repo: t.repo || '', project: t.project ? t.project.name : null, kind: 'team', members, lead, row: t };
    });
    teams.push(...groups(new Set(teams.flatMap(x => x.members))));
  }
  /** The groups the read itself shows among the sessions on no team, as the approved board draws them: the sessions that
   *  share a worktree (one branch's work), then the sessions that share a project, two or more each. They are columns
   *  of what is, never a team splice keeps: the first act on one (a rename, a session dropped on it) makes it a team. */
  function groups(teamed) {
    const free = liveList().filter(s => !teamed.has(s.id));
    const out = [], byTree = new Map(), byProject = new Map();
    for (const s of free) {
      const tree = s.row.repo && s.row.repo.worktree;
      if (tree) { if (!byTree.has(tree)) byTree.set(tree, []); byTree.get(tree).push(s.id); }
    }
    const inTree = new Set();
    for (const [tree, ids] of byTree) if (ids.length >= 2) {
      ids.forEach(i => inTree.add(i));
      const p = S.get(ids[0]).project;
      out.push({ id: 'wt:' + tree, name: tree.split('/').filter(Boolean).pop(), kind: 'worktree', project: p, members: ids, lead: leadOf(ids, p) });
    }
    for (const s of free) if (!inTree.has(s.id) && s.project) { if (!byProject.has(s.project)) byProject.set(s.project, []); byProject.get(s.project).push(s.id); }
    for (const [p, ids] of byProject) if (ids.length >= 2) out.push({ id: 'p:' + p, name: p, kind: 'project', project: p, members: ids, lead: leadOf(ids, p) });
    const urgency = g => Math.min(...g.members.map(rankOf));
    return out.map((g, i) => [g, i]).sort((a, b) => urgency(a[0]) - urgency(b[0]) || a[1] - b[1]).map(x => x[0]);
  }
  /** a group's lead, where its names say so: the session named for the project, or one whose name ends in -lead */
  function leadOf(ids, project) { return ids.find(id => S.get(id).name === project || /-lead$/.test(S.get(id).name)) || null; }
  const inferred = t => t && t.kind !== 'team' && t.kind !== 'loose';
  function teamOf(id) { return teams.find(t => t.members.includes(id)) || null; }
  /** the sessions running now: an ended one opened from history is read, never counted */
  const liveList = () => [...S.values()].filter(s => !s.ended);
  function looseIds() { return [...S.keys()].filter(id => !S.get(id).ended && !teamOf(id)); }
  function rawMembers(id) {
    if (id === 'loose') return looseIds();
    const t = teams.find(x => x.id === id);
    return t ? t.members.filter(i => S.has(i)) : [];
  }
  function rankOf(id) { const s = S.get(id); return s ? RANK[s.state] : 9; }
  function membersOf(id) { return rawMembers(id).slice().sort((a, b) => rankOf(a) - rankOf(b)); }
  function kindLabel(t) {
    const lead = t.lead && S.get(t.lead) ? ' · led by ' + S.get(t.lead).name : '';
    if (t.kind === 'loose') return 'Sessions with no team';
    if (t.kind === 'worktree') return 'Worktree · ' + (t.project || 'no project');
    if (t.kind === 'project') return 'Project' + lead;
    return 'Team you named' + lead;
  }
  function wordOf(s) {
    if (s.state === 'stalled' && s.stall) return s.stall.word + ' · ' + s.stall.detail;
    if (s.state === 'working' && s.request && s.request.phase === 'queued') return 'In line · ' + ago(Date.now() - s.request.startAbs);
    const base = { working: 'Working', needs: 'Needs you', idle: s.stale ? 'Silent' : 'Idle' }[s.state] || 'Idle';
    return base + ' · ' + ago(Date.now() - s.since);
  }
  /** the row's corner holds one short figure, as the approved board does: its turns. What the live request is doing
   *  is the open session's (its rail's Live request), and the state line already says how long it has worked. */
  function ageOf(s) {
    return s.turns != null ? Deck.count(s.turns, 'turn') : '';
  }
  function sayOf(s) {
    if (!reading) return 'Reading conversations is off.';
    if (s.last && s.last.text) return s.last.text;
    if (s.last && s.last.tool) return 'Running ' + s.last.tool;
    return s.state === 'needs' && s.ask ? s.ask.question : 'No output recorded yet.';
  }
  function worstOf(ms) { let w = null; for (const s of ms) if (!w || RANK[s.state] < RANK[w]) w = s.state; return w || 'idle'; }
  function doingOf(ms, loose) {
    if (!ms.length) return loose ? 'Every session is in a team.' : 'Empty. Drop a session here.';
    const need = ms.find(s => s.state === 'needs' && s.ask);
    if (need) return 'Needs you: ' + need.ask.question;
    const stall = ms.find(s => s.state === 'stalled' && s.stall);
    if (stall) return stall.stall.word + ': ' + stall.name;
    const w = ms.find(s => s.state === 'working' && s.last && s.last.text);
    if (w) return w.last.text;
    if (ms.some(s => s.state === 'working')) return 'Working.';
    return 'Idle. Nothing running.';
  }
  function matches(s) {
    const t = (s.name + ' ' + (s.project || '') + ' ' + ((s.last && s.last.text) || '') + ' ' + ((s.ask && s.ask.question) || '')).toLowerCase();
    return t.includes(filterText);
  }
  /** a team's own vitals: its requests per minute over the hour, and its worst state per minute */
  function actOf(ids) {
    const out = new Array(WIN).fill(0), t = Date.now();
    for (const i of ids) for (const k of ticksOf(i)) { const b = WIN - 1 - Math.floor((t - k.t) / MIN); if (b >= 0 && b < WIN) out[b]++; }
    return out;
  }
  function segsOf(ids) {
    const hs = ids.map(i => S.get(i)).filter(Boolean).map(histOf), out = [];
    for (let i = 0; i < WIN; i++) {
      let w = 'idle';
      for (const hist of hs) if (RANK[hist[i]] < RANK[w]) w = hist[i];
      out.push({ state: w, share: 1 / WIN });
    }
    return out;
  }

  /* ---------- the view state ---------- */
  let openId = null, openEnded = null, filterText = '';
  let boardY = 0, lookAt = Date.now();
  let F = {}, fleet = {}, lastNeeds = -1, needOnly = false, lastCtx = '', tickN = 0, bandKey = '', boardKey = '';
  const rowEl = new Map();
  const board = $('#board'), convEl = $('#conv'), endEl = $('#endview'), dockEl = $('#dock');
  const bandLanes = $('#bdLanes'), bdAxis = $('#bdAxis'), bdCount = $('#bdCount');
  const qIn = $('#q'), qWrap = $('#qwrap'), backBtn = $('#back');
  const redir = $('#redir'), redirIn = $('#redirIn'), stRedir = $('#stRedir');
  const ins = Deck.inspector(document.body);

  /* ---------- the moves on teams, each written to splice and said as it answered ---------- */
  function said(res, done, refused) {
    if (res.ok) composer.note('idle', done, 2500);
    else composer.note('stalled', refused + ': ' + why(res), 5000);
    return res.ok;
  }
  /** the team's body as PUT /api/teams/{id} takes it */
  const teamBody = (t, over = {}) => Object.assign({ id: t.row.id, name: t.row.name, goal: t.row.goal || '', repo: t.row.repo || '', slots: t.row.slots || [] }, over);
  async function leaveTeam(id) {
    for (const t of teams) {
      const slot = (t.row.slots || []).find(sl => sl.session === id);
      if (slot) { const r = await API.put('/api/teams/' + enc(t.id) + '/sessions', { bindings: { [slot.id]: null } }); if (!r.ok) return r; }
    }
    return { ok: true };
  }
  async function joinTeam(t, id) {
    const s = S.get(id);
    let slot = (t.row.slots || []).find(sl => !sl.session);
    if (!slot) {
      slot = { id: 'member-' + id.slice(0, 8), role: 'Member', head: s.head, lead: false };
      const put = await API.put('/api/teams/' + enc(t.id), teamBody(t, { slots: [...(t.row.slots || []), slot] }));
      if (!put.ok) return put;
    }
    return API.put('/api/teams/' + enc(t.id) + '/sessions', { bindings: { [slot.id]: id } });
  }
  /** makes a group a splice team named [name], with [extra] joining it, and answers the team splice saved */
  async function makeTeam(g, name, extra) {
    const ids = [...g.members, ...(extra ? [extra] : [])];
    const first = S.get(g.lead || ids[0]);
    const slots = ids.map((sid, i) => ({ id: 'm' + (i + 1), role: sid === g.lead ? 'Lead' : 'Member', head: S.get(sid).head, lead: sid === g.lead }));
    const body = { name, goal: '', repo: (first.row.repo && first.row.repo.root) || first.place, slots };
    const r = await API.put('/api/teams', body, { 'Idempotency-Key': 'console-' + Date.now().toString(36) + Math.random().toString(36).slice(2, 8) });
    if (!r.ok) return r;
    if (extra) { const left = await leaveTeam(extra); if (!left.ok) return left; }
    return API.put('/api/teams/' + enc(r.body.id) + '/sessions', { bindings: Object.fromEntries(ids.map((sid, i) => ['m' + (i + 1), sid])) });
  }
  async function moveSession(id, lane) {
    const g = teams.find(x => x.id === lane);
    if (inferred(g)) {
      said(await makeTeam(g, g.name, id), 'Moved to ' + g.name + ' · now a team', 'Not moved');
      return read();
    }
    if (lane === 'new') return createTeam(id, true);
    const cur = (teamOf(id) || { id: 'loose' }).id;
    if (cur === lane) return;
    let res = await leaveTeam(id);
    if (res.ok && lane !== 'loose') { const t = teams.find(x => x.id === lane); if (t) res = await joinTeam(t, id); }
    said(res, lane === 'loose' ? 'Moved out of its team' : 'Moved to ' + ((teams.find(x => x.id === lane) || {}).name || 'the team'), 'Not moved');
    await read();
  }
  async function createTeam(sid, inline) {
    const s = sid ? S.get(sid) : null;
    const repo = s ? (s.row.repo && s.row.repo.root) || s.place : '';
    const body = { name: 'New team', goal: '', repo, slots: s ? [{ id: 'lead', role: 'Lead', head: s.head, lead: true }] : [] };
    const r = await API.put('/api/teams', body, { 'Idempotency-Key': 'console-' + Date.now().toString(36) + Math.random().toString(36).slice(2, 8) });
    if (!said(r, 'Team made', 'No team made')) return;
    if (sid) { await leaveTeam(sid); await API.put('/api/teams/' + enc(r.body.id) + '/sessions', { bindings: { lead: sid } }); }
    await read();
    if (inline && r.body && r.body.id) nameInline(r.body.id);
  }
  async function disband(id) {
    const r = await API.post('/api/teams/' + enc(id) + '/archive');
    said(r, 'Team disbanded · its sessions stay live', 'Not disbanded');
    await read();
  }
  async function renameTeam(t, v) {
    if (inferred(t)) { said(await makeTeam(t, v), 'Named ' + v + ' · now a team', 'Not named'); return read(); }
    const r = await API.put('/api/teams/' + enc(t.id), teamBody(t, { name: v }));
    said(r, 'Renamed', 'Not renamed');
    await read();
  }
  function reorderTeam(id, x, y) {
    const others = [...board.querySelectorAll('.team[data-team]:not(.loose)')].filter(c => c.dataset.team !== id);
    let best = null, bd = Infinity;
    for (const c of others) {
      const r = c.getBoundingClientRect();
      const d = Math.hypot(x - (r.left + r.width / 2), y - (r.top + r.height / 2));
      if (d < bd) { bd = d; best = c; }
    }
    if (inferred(teams.find(x => x.id === id))) return;
    const ids = others.map(c => c.dataset.team).filter(x => !inferred(teams.find(y => y.id === x)));
    let idx = best && ids.includes(best.dataset.team) ? ids.indexOf(best.dataset.team) : ids.length;
    if (best && ids.includes(best.dataset.team)) { const r = best.getBoundingClientRect(); if (x > r.left + r.width / 2) idx++; }
    ids.splice(idx, 0, id);
    if (ids.join() === teams.filter(t => !inferred(t)).map(t => t.id).join()) return;
    order.splice(0, order.length, ...ids);
    try { localStorage.setItem(ORDER_KEY, JSON.stringify(order)); } catch { /* storage off: the order lasts until the tab closes */ }
    syncTeams();
    renderBoard();
    paint();
  }
  function stepLane(id, dir) {
    const lanes = ['loose', ...teams.map(t => t.id)];
    const i = lanes.indexOf((teamOf(id) || { id: 'loose' }).id) + dir;
    if (i < 0 || i >= lanes.length) return;
    moveSession(id, lanes[i]).then(() => { const r = rowEl.get(id); if (r) r.focus({ preventScroll: true }); });
  }
  function colOf(id) { return board.querySelector('.team[data-lane="' + id + '"]'); }
  function nameInline(id) {
    const t = teams.find(x => x.id === id), col = colOf(id);
    if (!t || !col || !col._p) return;
    const inp = h('input', 'team-rename');
    inp.value = t.name; inp.setAttribute('aria-label', 'Team name');
    col._p.nm.replaceWith(inp);
    let done = false;
    const commit = keep => {
      if (done) return; done = true;
      const v = inp.value.trim();
      inp.replaceWith(col._p.nm);
      if (keep !== false && v && v !== t.name) renameTeam(t, v);
    };
    inp.addEventListener('keydown', e => {
      e.stopPropagation();
      if (e.key === 'Enter') { e.preventDefault(); commit(); }
      else if (e.key === 'Escape') { e.preventDefault(); commit(false); }
    });
    inp.addEventListener('blur', () => commit());
    requestAnimationFrame(() => { inp.focus(); inp.select(); });
  }

  /* ---------- an answer: Deck.answer, keyed by its question so it is built once ---------- */
  function waitText(s) { return 'waiting ' + ago(Date.now() - s.since); }
  function syncAsk(box, s, compact) {
    if (!s.ask || !s.ask.choices.length) { if (box._k) { box._k = ''; box.replaceChildren(); } return; }
    const k = askKey(s.ask);
    if (box._k !== k) {
      box._k = k;
      const id = s.id;
      box.replaceChildren(Deck.answer(s.ask, { compact: !!compact, waited: waitText(s), onAnswer: (i, label, typed) => answerSession(id, i, typed, k) }));
    }
    const w = box.querySelector('.ask-wait'); if (w) setTxt(w, waitText(s));
  }
  /** presses the choice he picked, as the old console did (POST answer); words he typed go after it (POST say) */
  async function answerSession(id, index, typed, key) {
    const s = S.get(id);
    if (!s || !s.ask || (key && askKey(s.ask) !== key)) return;
    const choice = s.ask.picks[index] != null ? s.ask.picks[index] : index + 1;
    let res = await API.post('/api/sessions/' + enc(id) + '/answer', { choice });
    if (res.ok && typed) res = await API.post('/api/sessions/' + enc(id) + '/say', { text: typed });
    said(res, 'Answered', 'Not answered');
    await read();
  }

  /* ---------- the board: a column per team ---------- */
  function renderIfChanged() {
    const key = teams.map(t => t.id + ':' + t.name + ':' + t.members.join('.')).join('|') + '#' + looseIds().join('.');
    if (key !== boardKey) renderBoard();
  }
  function renderBoard() {
    boardKey = teams.map(t => t.id + ':' + t.name + ':' + t.members.join('.')).join('|') + '#' + looseIds().join('.');
    const folds = new Map([...board.querySelectorAll('.team[data-lane]')].map(c => [c.dataset.lane, c._open]));
    const lanes = [{ id: 'loose', name: 'Not in a team', kind: 'loose', project: null, members: [] }, ...teams];
    const cols = lanes.map(t => makeCol(t));
    board.replaceChildren(...cols, makeNewCol());
    cols.forEach((col, i) => { col._open = folds.get(lanes[i].id); col._p.body.replaceChildren(...membersOf(lanes[i].id).map(rowFor)); col._p.key = membersOf(lanes[i].id).join(','); paintTeam(col); });
    fitBoard();
  }
  /* the number of columns that fit the width; the rest reflow onto the next row, never sideways */
  function fitBoard() {
    const n = board.querySelectorAll('.team').length;
    const W = board.clientWidth, minW = Deck.px('--team-min'), gap = Deck.px('--team-gap');
    if (!W || !n) return;
    const cols = Math.max(1, Math.min(n, Math.floor((W + gap) / (minW + gap))));
    board.style.setProperty('--cols', String(cols));
  }
  function makeCol(t) {
    const loose = t.id === 'loose';
    const col = h('section', 'team' + (loose ? ' loose' : ''));
    col.dataset.team = t.id; col.dataset.lane = t.id;
    const hd = h('header', 'team-hd');
    const top = h('div', 'team-top');
    const lamp = h('span', 'team-lamp');
    const idn = h('div', 'team-id');
    const kick = h('div', 'eyebrow team-kick');
    const nm = h('h2', 'team-name', t.name);
    idn.append(kick, nm);
    top.append(lamp, idn);
    if (!loose) {
      hd.dataset.drag = 'team';
      const more = iconBtn('more', 'Actions for ' + t.name);
      more.classList.add('team-more');
      more.addEventListener('click', e => { e.stopPropagation(); teamMenu(t.id, more); });
      top.append(more);
      nm.title = 'Double-click to rename';
      nm.addEventListener('dblclick', () => nameInline(t.id));
    }
    const counts = h('div', 'team-counts mono');
    const vit = h('div', 'team-vitals');
    const spark = Deck.spark(new Array(WIN).fill(0));
    const stripSlot = h('div', 'team-stripslot');
    vit.append(spark.el, stripSlot);
    const doing = h('p', 'team-doing');
    const fold = h('button', 'team-fold'); fold.type = 'button';
    fold.addEventListener('click', e => { e.stopPropagation(); col._open = !col._open; paintTeam(col); });
    hd.append(top, counts, vit, doing, fold);
    const body = h('div', 'team-rows');
    col.append(hd, body);
    col._p = { kick, nm, lamp, counts, spark, stripSlot, doing, fold, body, key: null, n: 0 };
    setTxt(kick, kindLabel(t));
    return col;
  }
  function makeNewCol() {
    const col = h('section', 'team new');
    col.dataset.lane = 'new';
    const b = h('button', 'btn quiet press newteam', 'New team');
    b.type = 'button';
    b.addEventListener('click', () => createTeam(null, true));
    col.append(b, h('p', 'team-doing', 'Drop a session here to start a team, then name it.'));
    return col;
  }
  function paintTeam(col) {
    const id = col.dataset.team; if (id === undefined) return;
    const p = col._p, loose = id === 'loose';
    const t = loose ? null : teams.find(x => x.id === id);
    const ids = rawMembers(id), ms = ids.map(i => S.get(i)).filter(Boolean);
    const c = { working: 0, needs: 0, stalled: 0, idle: 0 };
    ms.forEach(s => { c[s.state]++; });
    const parts = [];
    if (c.needs) parts.push(c.needs + ' need you');
    if (c.stalled) parts.push(c.stalled + ' stalled');
    if (c.working) parts.push(c.working + ' working');
    if (c.idle) parts.push(c.idle + ' idle');
    setTxt(p.counts, parts.join(' · ') || 'Empty');
    const worst = worstOf(ms);
    if (col._worst !== worst) { col._worst = worst; p.lamp.replaceChildren(Deck.lamp(worst)); }
    const quiet = !c.needs && !c.stalled && !c.working;
    col.classList.toggle('is-quiet', quiet);
    col.classList.toggle('has-needs', c.needs > 0);
    setTxt(p.kick, kindLabel(t || { kind: 'loose' }));
    setHidden(p.body, quiet && !col._open);
    setHidden(p.fold, !quiet);
    setTxt(p.fold, col._open ? 'Fold' : 'Folded · ' + ms.length + (ms.length === 1 ? ' session' : ' sessions') + ' · show');
    setTxt(p.doing, doingOf(ms, loose));
    p.spark.update(actOf(ids));
    if (p.n % 5 === 0 || !p.stripped) { p.stripped = true; Deck.stripInto(p.stripSlot, segsOf(ids)); }
    p.n++;
    const key = membersOf(id).join(',');
    if (p.key !== key && !p.body.contains(document.activeElement)) {
      p.key = key;
      p.body.replaceChildren(...membersOf(id).map(rowFor));
    }
  }
  function teamMenu(id, btn) {
    const t = teams.find(x => x.id === id); if (!t) return;
    Deck.openMenu(btn, [
      { label: 'Message the team', sub: '@' + slug(t), run: () => composer.insert('@' + slug(t) + ' ') },
      { label: 'Rename', run: () => nameInline(id) },
      '-',
      { label: 'Stop all', sub: 'Working and needs you', run: () => stopMany(t.members) },
      { label: 'Move all to a model', sub: 'Continue on', run: () => pickModel(btn, (p, m) => Promise.all(t.members.map(i => setModelOf(i, p, m))), null) },
      '-',
      ...(inferred(t) ? [] : [{ label: 'Disband team', sub: 'Sessions stay live', danger: true, run: () => disband(id) }]),
    ], 'down');
  }

  /* ---------- a session row in its column ---------- */
  function rowFor(id) {
    let r = rowEl.get(id);
    if (!r) { r = makeRow(id); rowEl.set(id, r); }
    return r;
  }
  function makeRow(id) {
    const row = h('article', 'srow');
    row.tabIndex = 0; row.dataset.sid = id;
    const lampSlot = h('span', 'srow-lamp');
    const nm = h('div', 'srow-nm');
    const st = h('div', 'srow-st');
    const who = h('div', 'srow-who');
    who.append(nm, st);
    const age = h('span', 'srow-age mono');
    const top = h('div', 'srow-top');
    top.append(lampSlot, who, age);
    const say = h('p', 'srow-say');
    const ask = h('div', 'srow-ask'); ask.hidden = true;
    const fix = h('button', 'srow-fix press'); fix.type = 'button'; fix.hidden = true;
    const fixNm = h('span', 'srow-fix-nm'), fixSub = h('span', 'srow-fix-sub');
    fix.append(fixNm, fixSub);
    fix.addEventListener('click', e => { e.stopPropagation(); applyFix(id); });
    row.append(top, say, ask, fix);
    row._p = { lampSlot, nm, st, age, say, ask, fix, fixNm, fixSub };
    return row;
  }
  function paintRow(row) {
    const s = S.get(row.dataset.sid); if (!s) return;
    const p = row._p;
    if (row._st !== s.state) { row._st = s.state; p.lampSlot.replaceChildren(Deck.lamp(s.state)); }
    row.classList.toggle('is-needs', s.state === 'needs');
    row.classList.toggle('is-stalled', s.state === 'stalled');
    row.classList.toggle('is-open', openId === s.id);
    row.classList.toggle('is-dim', (!!filterText && !matches(s)) || (needOnly && s.state !== 'needs'));
    setTxt(p.nm, s.name);
    setTxt(p.st, wordOf(s));
    setTxt(p.age, ageOf(s)); if (p.age.title !== p.age.textContent) p.age.title = p.age.textContent;
    setTxt(p.say, sayOf(s));
    const need = s.state === 'needs' && !!s.ask && s.ask.choices.length > 0;
    setHidden(p.ask, !need);
    if (need) syncAsk(p.ask, s, true);
    else syncAsk(p.ask, { ask: null });
    /* a stalled session carries its one fix on its own row */
    const f = s.state === 'stalled' ? fixOf(s) : null;
    setHidden(p.fix, !f);
    if (f) { setTxt(p.fixNm, f.label); setTxt(p.fixSub, f.sub); }
  }

  /* ---------- painting: every second, in place. A tick writes only what changed since the last one: the kit's
     sparks, strips and rolling numbers draw nothing when handed what they already show, so a board where nothing
     happened in a second draws nothing in it. ---------- */
  function paint() {
    const c = counts();
    if (c.needs !== lastNeeds) {
      lastNeeds = c.needs;
      // pressed, the float leaves standing only the sessions waiting on the person; pressed again, every row is back
      if (!c.needs && needOnly) { needOnly = false; $('#needBtn')?.setAttribute('aria-pressed', 'false'); }
      Deck.setNeed(c.needs, 'need you', on => { needOnly = on; if (on) jumpToNeed(); paint(); });
    }
    for (const row of rowEl.values()) paintRow(row);
    board.querySelectorAll('.team[data-lane]').forEach(col => paintTeam(col));
    paintHead(c);
    if (openId && S.has(openId)) { const s = S.get(openId); paintBand(s); paintFacts(); syncDock(s); }
    else if (!openEnded) paintFleet(c);
    const ctx = JSON.stringify(contextLine());
    if (ctx !== lastCtx) { lastCtx = ctx; composer.refresh(); }
  }
  function counts() {
    const c = { total: liveList().length, working: 0, needs: 0, stalled: 0, idle: 0, silent: 0 };
    for (const s of liveList()) { c[s.state] = (c[s.state] || 0) + 1; if (s.state === 'idle' && s.stale) c.silent++; }
    return c;
  }
  function paintHead(c) {
    const kick = $('#kick'), title = $('#title'), sub = $('#sub');
    if (openId && S.has(openId)) {
      const s = S.get(openId), t = teamOf(openId);
      setTxt(kick, (t ? t.name : 'Not in a team') + ' · ' + (s.project || 'no project'));
      setTxt(title, s.name);
      setTxt(sub, (modelLabel(s) || 'Not through Splice') + ' · ' + (loginNameOf(s) || 'no account') + ' · ' + wordOf(s));
    } else if (openEnded) {
      setTxt(kick, 'Sessions · Ended');
      setTxt(title, openEnded);
      setTxt(sub, Deck.count(endedIn(openEnded).length, 'ended session'));
    } else {
      setTxt(kick, 'Sessions · Teams');
      setTxt(title, c.needs ? (c.needs === 1 ? '1 session needs you' : c.needs + ' sessions need you') : 'Nothing needs you');
      const named = teams.filter(x => !inferred(x)).length, grouped = teams.length - named;
      setTxt(sub, c.working + ' working · ' + c.stalled + ' stalled · ' + c.idle + ' idle · ' + named + (named === 1 ? ' team' : ' teams') + (grouped ? ' · ' + grouped + (grouped === 1 ? ' group' : ' groups') : ''));
    }
    setHidden(qWrap, !!(openId || openEnded));
    setHidden(backBtn, !(openId || openEnded));
    const status = $('.head .status');
    if (status) {
      const off = readOk === false;
      status.classList.toggle('is-off', off);
      setTxt(status.children[1], off ? 'Not answering' : 'Live');
    }
  }
  function jumpToNeed() {
    if (openId || openEnded) closeOpen();
    const row = board.querySelector('.srow.is-needs');
    if (row) { row.scrollIntoView({ block: 'center', behavior: 'smooth' }); row.focus({ preventScroll: true }); }
  }

  /* the one fix a stalled session gets: another login when signed out, another model at a limit or a failing provider */
  function fixOf(s) {
    if (!s.stall) return null;
    if (s.stall.kind === 'signout') {
      const a = accountList(s).find(x => x.label !== s.account);
      return a ? { label: 'Switch to ' + (a.display_name || a.label), sub: 'Its login is signed out', run: () => setAccountOf(s.id, a.label) } : null;
    }
    if (s.stall.kind !== 'limit' && s.stall.kind !== 'gaveup') return null;
    const p = providerOf(s);
    const m = p && p.models.find(x => x[1] !== s.model);
    return m ? { label: 'Continue on ' + m[0], sub: s.stall.kind === 'limit' ? 'Its model is at a limit' : 'Its provider keeps failing', run: () => setModelOf(s.id, p, m) } : null;
  }
  function applyFix(id) {
    const s = S.get(id); if (!s) return;
    const f = fixOf(s); if (f) f.run();
  }

  /* ---------- the right rail with nothing open: the fleet as instruments ---------- */
  function inst(label, valueEl, body, cap) {
    const box = h('div', 'inst');
    const top = h('div', 'inst-top');
    top.append(h('span', 'eyebrow', label));
    if (valueEl) top.append(valueEl);
    box.append(top);
    if (body) box.append(body);
    if (cap) box.append(cap);
    return box;
  }
  function kvRow(label) {
    const row = h('div', 'rl-kv'), v = h('span', 'mono');
    row.append(h('span', 'quiet', label), v);
    return { el: row, v };
  }
  function buildFleet() {
    const R = $('#railR'); R.replaceChildren();
    fleet = { provRows: new Map(), accRows: new Map(), sinceV: {} };
    const sec1 = h('section', 'sec');
    fleet.reqV = h('span', 'inst-v mono'); fleet.reqSpark = Deck.spark(new Array(WIN).fill(0));
    sec1.append(inst('Model calls a minute', fleet.reqV, fleet.reqSpark.el, h('p', 'inst-cap', 'Model calls across the fleet, last hour')));
    const sec2 = h('section', 'sec');
    fleet.wV = h('span', 'inst-v mono'); fleet.wSpark = Deck.spark(new Array(WIN).fill(0));
    sec2.append(inst('Working now', fleet.wV, fleet.wSpark.el, h('p', 'inst-cap', 'Sessions working, last hour')));
    const sec3 = h('section', 'sec');
    fleet.stripSlot = h('div'); fleet.shareCap = h('p', 'inst-cap', 'Share of the last hour');
    sec3.append(inst('Fleet state', null, fleet.stripSlot, fleet.shareCap));
    const sec4 = h('section', 'sec');
    fleet.provBox = h('div', 'rl-prov-list');
    sec4.append(h('h2', 'eyebrow', 'Providers'), fleet.provBox);
    const sec5 = h('section', 'sec');
    fleet.accBox = h('div', 'rl-prov-list');
    fleet.accNone = h('p', 'faint small', 'No Claude account reports its week.');
    sec5.append(h('h2', 'eyebrow', 'Account headroom'), fleet.accBox, fleet.accNone);
    const sec6 = h('section', 'sec');
    fleet.sinceCap = h('p', 'inst-cap');
    sec6.append(h('h2', 'eyebrow', 'Since you last looked'), fleet.sinceCap);
    [['finished', 'Finished'], ['started', 'Started'], ['asked', 'Asked you'], ['stuck', 'Got stuck']].forEach(([k, label]) => {
      const kv = kvRow(label); fleet.sinceV[k] = kv.v; sec6.append(kv.el);
    });
    const sec7 = h('section', 'sec');
    fleet.endH = h('h2', 'eyebrow');
    fleet.endList = h('div', 'rl-ended');
    sec7.append(fleet.endH, fleet.endList);
    R.append(sec1, sec2, sec3, sec4, sec5, sec6, sec7);
    fleet.stripDone = false; fleet.endKey = '';
  }
  function paintFleet(c) {
    if (!fleet.reqSpark) return;
    const all = liveList().map(s => s.id);
    const reqs = actOf(all);
    fleet.reqSpark.update(reqs);
    Deck.roll(fleet.reqV, reqs[WIN - 1]);
    const working = new Array(WIN).fill(0);
    const hists = liveList().map(histOf);
    for (const hist of hists) for (let i = 0; i < WIN; i++) if (hist[i] === 'working') working[i]++;
    fleet.wSpark.update(working);
    Deck.roll(fleet.wV, c.working);
    if (!fleet.stripDone || tickN % 5 === 0) {
      fleet.stripDone = true;
      const tot = { working: 0, needs: 0, stalled: 0, idle: 0 };
      for (const hist of hists) for (const st of hist) tot[st] = (tot[st] || 0) + 1;
      const all2 = hists.length * WIN || 1;
      const segs = ['working', 'needs', 'stalled', 'idle'].filter(st => tot[st] > 0).map(st => ({ state: st, share: tot[st] / all2 }));
      Deck.stripInto(fleet.stripSlot, segs);
      setTxt(fleet.shareCap, segs.length ? segs.map(sg => sg.state + ' ' + Deck.pct(sg.share)).join(' · ') + ' · last hour' : 'No session in the last hour');
    }
    const provs = new Map();
    for (const s of liveList()) { const p = providerOf(s), k = p ? p.id : 'direct'; provs.set(k, { n: ((provs.get(k) || {}).n || 0) + 1, p }); }
    const total = liveList().length || 1;
    for (const [k, row] of fleet.provRows) if (!provs.has(k)) { row.row.remove(); fleet.provRows.delete(k); }
    [...provs].sort((a, b) => b[1].n - a[1].n).forEach(([k, { n, p }]) => {
      let row = fleet.provRows.get(k);
      if (!row) {
        row = { ph: h('div', 'ph'), nm: h('span'), v: h('span', 'mono'), m: null, row: h('div', 'rl-prov') };
        const dot = h('span', 'rl-pdot');
        if (p) dot.style.setProperty('--pc', p.color);
        row.nm.append(dot, p ? (SPLICE.providers.filter(x => x.name === p.name).length < 2 ? p.name : p.name + ' · ' + p.command) : 'Direct');
        row.m = Deck.meter(0, p ? p.color : 'var(--ink-faint)');
        row.ph.append(row.nm, row.v);
        row.row.append(row.ph, row.m);
        fleet.provRows.set(k, row);
        fleet.provBox.append(row.row);
      }
      setTxt(row.v, n + ' · ' + Math.round((n / total) * 100) + '%');
      row.m.set(n / total);
    });
    // each plan login's week, as Accounts reads it: what is left of it, and when it resets
    const weekly = accounts.filter(a => a.seven_day_used_percent != null);
    setHidden(fleet.accNone, weekly.length > 0);
    for (const a of weekly) {
      const key = (a.heads || []).join('+') + '/' + (a.selector_key || a.label);
      let x = fleet.accRows.get(key);
      if (!x) {
        x = { row: h('div', 'rl-prov'), ph: h('div', 'ph'), nm: h('span', 'quiet'), v: h('span', 'mono'), m: Deck.meter(0, 'var(--p-claude)'), sub: h('p', 'rl-sub') };
        x.ph.append(x.nm, x.v); x.row.append(x.ph, x.m, x.sub);
        fleet.accRows.set(key, x); fleet.accBox.append(x.row);
      }
      const left = Math.max(0, 1 - a.seven_day_used_percent / 100);
      setTxt(x.nm, a.display_name || a.label);
      setTxt(x.v, Math.round(left * 100) + '% left');
      x.v.classList.toggle('is-out', left === 0);
      x.m.set(left);
      const reset = a.seven_day_reset_epoch_seconds ? new Date(a.seven_day_reset_epoch_seconds * 1000) : null;
      setTxt(x.sub, reset ? 'Week limit, resets ' + reset.toLocaleString('en-US', { weekday: 'short', hour: 'numeric', minute: '2-digit' }) : 'Week limit');
    }
    // a caption counts no seconds: in its first minute it says so in words, and the page writes nothing for it
    const looked = Date.now() - lookAt;
    setTxt(fleet.sinceCap, 'Since ' + (looked < MIN ? 'a moment' : ago(looked)) + ' ago, when you left a session or the board');
    const since = k => events.filter(e => e.kind === k && e.t >= lookAt).length;
    for (const k of ['finished', 'started', 'asked', 'stuck']) setTxt(fleet.sinceV[k], String(since(k)));
    const byProj = new Map();
    for (const row of GONE.values()) { const p = (row.project && row.project.name) || 'No project'; byProj.set(p, (byProj.get(p) || 0) + 1); }
    const endKey = [...byProj].map(x => x.join(':')).join('|');
    if (endKey !== fleet.endKey) {
      fleet.endKey = endKey;
      setTxt(fleet.endH, 'Ended · ' + GONE.size.toLocaleString('en-US'));
      fleet.endList.replaceChildren(...[...byProj].sort((a, b) => b[1] - a[1]).map(([proj, n]) => {
        const b = h('button', 'rl-row press'); b.type = 'button';
        b.append(h('span', '', proj), h('span', 'mono', n.toLocaleString('en-US')));
        b.addEventListener('click', () => openEndedView(proj));
        return b;
      }));
    }
  }
  /* ---------- the ended sessions of one project: each opens its kept conversation ---------- */
  const endedIn = proj => [...GONE.values()].filter(r => ((r.project && r.project.name) || 'No project') === proj)
    .sort((a, b) => (epoch(b.updated_at) || 0) - (epoch(a.updated_at) || 0));
  function openEndedView(proj) {
    if (!openId && !openEnded) boardY = window.scrollY;
    openId = null; openEnded = proj;
    showView('ended');
    const rows = endedIn(proj);
    const back = h('button', 'btn press', 'Back to teams'); back.type = 'button';
    back.addEventListener('click', closeOpen);
    const list = h('div', 'rl-ended');
    for (const r of rows.slice(0, 200)) {
      const b = h('button', 'rl-row press'); b.type = 'button';
      b.append(h('span', '', names.get(r.session_id) || r.name || r.title || r.session_id.slice(0, 8)), h('span', 'mono', agoText(Date.now() - (epoch(r.updated_at) || Date.now()))));
      b.addEventListener('click', () => openGone(r));
      list.append(b);
    }
    endEl.replaceChildren(h('p', '', rows.length.toLocaleString('en-US') + ' ended sessions in ' + proj + '. Their conversations are kept.'), list, back);
    window.scrollTo(0, 0);
    paint();
  }
  /** an ended session opens read-only: its conversation, with nothing to steer */
  function openGone(row) {
    const s = sessionOf(Object.assign({}, row, { availability: 'stale', waiting_for: null, status: 'idle' }), new Map(), new Map());
    s.ended = true; s.state = 'idle';
    S.set(s.id, s);
    openSession(s.id);
  }

  /* ---------- the open session: the band, the reader, the question, the steering ---------- */
  function bandIds(s) {
    const t = teamOf(s.id);
    const peers = t ? membersOf(t.id).filter(i => i !== s.id) : [];
    return [s.id, ...peers];
  }
  function buildBand(s) {
    const ids = bandIds(s), key = ids.join(',');
    if (key !== bandKey) {
      bandKey = key;
      bandLanes.replaceChildren(...ids.map(id => {
        const b = h('button', 'bd-lane press'); b.type = 'button'; b.dataset.id = id;
        const nm = h('span', 'bd-nm');
        const cv = h('canvas', 'bd-cv'); cv.setAttribute('aria-hidden', 'true');
        b.append(nm, cv);
        b.addEventListener('click', () => { if (id !== openId) openSession(id); });
        return b;
      }));
      const ticks = [['30 min', 0, 'edge-l'], ['20', 1 / 3, ''], ['10', 2 / 3, ''], ['now', 1, 'edge-r']];
      bdAxis.replaceChildren(...ticks.map(([t, x, cls]) => { const el = h('span', 'bd-tl ' + cls, t); el.style.setProperty('left', 'calc(' + x + ' * 100%)'); return el; }));
    }
  }
  function paintBand(s) {
    buildBand(s);
    for (const lane of bandLanes.children) {
      const x = S.get(lane.dataset.id); if (!x) continue;
      lane.classList.toggle('is-cur', x.id === s.id);
      setTxt(lane.querySelector('.bd-nm'), x.name);
      lane.title = x.name + ' · ' + wordOf(x);
    }
    const t = teamOf(s.id);
    setTxt(bdCount, (t ? t.name : 'Not in a team') + ' · ' + bandLanes.children.length + (bandLanes.children.length === 1 ? ' lane' : ' lanes'));
  }
  function drawBand() {
    if (!openId) return;
    for (const lane of bandLanes.children) { const s = S.get(lane.dataset.id); if (s) drawLane(lane.querySelector('canvas'), s); }
  }
  /* a lane: requests as ticks (verdigris when answered, vermilion when not), the live request as a bar, a needs mark
     from when it started waiting, a vermilion line when stalled; the clock runs right to left over 30 minutes */
  function drawLane(cv, s) {
    const r = cv.getBoundingClientRect();
    const W = Math.max(1, Math.round(r.width)), H = Math.max(1, Math.round(r.height));
    if (cv.width !== Math.round(W * DPR) || cv.height !== Math.round(H * DPR)) { cv.width = Math.round(W * DPR); cv.height = Math.round(H * DPR); }
    const ctx = cv.getContext('2d');
    ctx.setTransform(DPR, 0, 0, DPR, 0, 0);
    ctx.clearRect(0, 0, W, H);
    const now = Date.now(), mid = H / 2, bar = 2;
    const X = t => W * (1 - (now - t) / BAND_MS);
    const good = new Uint16Array(W), bad = new Uint16Array(W);
    for (const k of ticksOf(s.id)) {
      if (k.t < now - BAND_MS || k.t > now) continue;
      const c = Math.min(W - 1, Math.max(0, Math.floor(X(k.t))));
      if (k.ok) good[c]++; else bad[c]++;
    }
    ctx.fillStyle = rgba(C.ink, 0.12); ctx.fillRect(0, Math.round(mid), W, 1);
    for (let c = 0; c < W; c++) {
      if (good[c]) { const n = good[c], th = Math.min(H * 0.7, H * 0.36 + n * H * 0.03); ctx.fillStyle = rgba(C.verd, Math.min(0.8, 0.5 + n * 0.03)); ctx.fillRect(c, mid - th / 2, bar, th); }
      if (bad[c]) { ctx.fillStyle = rgba(C.verm, 0.8); ctx.fillRect(c, mid - H * 0.18, bar, H * 0.36); }
    }
    if (s.state === 'needs') {
      const a = Math.min(W, Math.max(0, X(s.since)));
      ctx.fillStyle = rgba(C.verm, 0.14); ctx.fillRect(a, H * 0.14, W - a, H * 0.72);
      ctx.fillStyle = rgba(C.verm, 0.95); ctx.fillRect(a, H * 0.06, bar, H * 0.88);
    }
    if (s.state === 'stalled') {
      const a = Math.min(W, Math.max(0, X(s.since)));
      ctx.fillStyle = rgba(C.verm, 0.9); ctx.fillRect(a, mid - 1, W - a, 2);
      ctx.beginPath(); ctx.arc(a + 1, mid, 3.5, 0, Math.PI * 2); ctx.fill();
    }
    if (s.request && s.state === 'working') {
      const a = Math.min(W, Math.max(0, X(s.request.startAbs))), yy = H * 0.8, rh = Math.max(2, H * 0.14);
      if (s.request.phase !== 'streaming') {
        ctx.strokeStyle = rgba(C.ink, 0.42); ctx.lineWidth = 1; ctx.setLineDash([3, 4]);
        ctx.beginPath(); ctx.moveTo(a, yy); ctx.lineTo(W - 4, yy); ctx.stroke(); ctx.setLineDash([]);
        ctx.fillStyle = rgba(C.ink, 0.42);
      } else {
        ctx.fillStyle = rgba(C.verd, 0.4); ctx.fillRect(a, yy - rh / 2, W - a, rh);
        ctx.fillStyle = rgba(C.verd, 1);
      }
      ctx.beginPath(); ctx.arc(W - 4, yy, 3.2, 0, Math.PI * 2); ctx.fill();
    }
  }

  /* the reader: the conversation drawn by the shared session reader (reply-view/, Nov 21, 2026) and read through its
     connector: the transcript's pages, and the reply as it is written (GET /api/sessions/{id}/live). The page keeps
     what is its own around it: its scroll with the newest line in view, the door to earlier messages, the mark of what
     is new since he last looked, and what to say where there is nothing to read. The page's own drawing of a
     conversation (messages, folded runs of tool calls, notes) went with this: the reader draws all of it, here as on
     every page that reads a session. */
  /* where the conversation rests: its newest line a gap above what floats over the foot of the page (the composer, and on
     a phone the night-and-day switch and the waiting count that sit above it). Opening a session, a reply that grows and
     a question that arrives all rest it there (Nov 21, 2026: a reply that grew used to be followed to the end of the
     page, which stands a second composer's height below the newest line, so at 900 px the newest line sat mid-screen). */
  function footTop() {
    let top = window.innerHeight;
    for (const el of [$('#compWrap'), document.querySelector('.switch'), document.querySelector('.need-float')]) {
      const r = el ? el.getBoundingClientRect() : null;
      if (r && r.height && r.top > window.innerHeight / 2) top = Math.min(top, r.top);
    }
    return top;
  }
  /** the room kept between the newest line and the foot: the height the page's lower edge fades over, so the line a
   *  person reads last is never dimmed */
  function footGap() { return Deck.px('--sp-6'); }
  /** the newest thing in the open session: the question it waits on, else the last line drawn */
  function newest() { return dockEl.childElementCount ? dockEl : lastDrawn(); }
  /** how far below its resting place the newest line stands: none when it is in sight */
  function belowRest() {
    const last = newest();
    return last ? last.getBoundingClientRect().bottom - (footTop() - footGap()) : 0;
  }
  function nearEnd() { return belowRest() <= Deck.px('--sp-7') * 2; }
  function scrollToEnd(force) {
    if (!force && !nearEnd()) return;
    const dy = belowRest();
    if (dy > 0) window.scrollBy(0, dy);
  }
  const noteEl = $('#cvNote'), readerEl = $('#cvReader');
  let readerMods = null;
  // a load that failed is asked again by the next conversation opened: only a load that worked is kept
  const readerParts = () => readerMods || (readerMods = Promise.all([import('/reply-view/reply-view.js'), import('/reply-view/connect.js')])
    .then(([r, c]) => ({ mount: r.mount, connect: c.connect }), err => { readerMods = null; throw err; }));
  /** the open conversation: its view and connector, what splice said of its transcript, and whether older pages remain */
  let rd = null;
  /** whether the newest line was in view before the conversation last grew: a reply that streams in keeps it there */
  let atEnd = true;
  addEventListener('scroll', () => { if (openId) atEnd = nearEnd(); }, { passive: true });
  const NOTE = { off: 'Reading conversations is off. Turn it on in Settings', gone: 'No transcript', failed: 'Transcript did not load' };
  function markLine(text, cls) {
    const el = h('div', 'cv-mark' + (cls ? ' ' + cls : ''));
    el.append(h('span', 'rule'), h('span', 'mk-t', text), h('span', 'rule'));
    return el;
  }
  /** above the conversation: that it is being read, why there is none, or the door to earlier messages */
  function paintNote() {
    noteEl.replaceChildren();
    if (!rd) return;
    const st = !reading ? 'off' : rd.state;
    if (st === null) noteEl.append(markLine('Reading the conversation'));
    // a read that fails once the conversation is drawn leaves it standing: the connector asks again by itself
    else if (NOTE[st] && (st !== 'failed' || !rd.drawn)) noteEl.append(markLine(NOTE[st]));
    else if (rd.more) {
      const b = h('button', 'cv-door press'); b.type = 'button';
      b.append(h('span', 'sum-t', 'Earlier messages'), h('span', 'sum-x', 'Show'));
      b.addEventListener('click', async () => {
        const mine = rd;
        if (!mine || !mine.conn || b.disabled) return;
        b.disabled = true;
        // what he is reading stays where it is: the page grows above it
        const y = document.documentElement.scrollHeight - window.scrollY;
        mine.holding = true;
        await mine.conn.earlier();
        mine.holding = false;
        if (rd === mine) window.scrollTo(0, document.documentElement.scrollHeight - y);
        b.disabled = false;
      });
      noteEl.append(b);
    }
    readerEl.hidden = st === 'off' || st === 'gone';
  }
  /** the first messages handed on say which are new since he last looked: where some but not all are, a line stands
   *  above the first of them */
  function markNew(mine, messages) {
    if (mine.marked) return;
    mine.marked = true;
    const fresh = messages.filter(m => (epoch(m.ts) || 0) > mine.seenAt);
    if (!fresh.length || fresh.length === messages.length) return;
    mine.newFrom = Math.min(...fresh.map(m => m.index)); mine.newCount = fresh.length;
  }
  function placeNewMark(mine) {
    if (mine.newFrom == null || mine.newPlaced) return;
    let el = readerEl.querySelector('.rv-history [data-index="' + mine.newFrom + '"]');
    // a result is drawn inside its call: the line stands above the call that holds it
    while (el && el.parentElement && !el.parentElement.classList.contains('rv-history')) el = el.parentElement;
    if (!el) return;
    mine.newPlaced = true;
    el.before(markLine('New since you last looked · ' + mine.newCount, 'is-new'));
  }
  /** the newest thing drawn: the reply being written, else the last message */
  function lastDrawn() {
    const live = readerEl.querySelector('.rv-live'), hist = readerEl.querySelector('.rv-history');
    return (live && live.lastElementChild) || (hist && hist.lastElementChild) || null;
  }
  function closeReader() {
    if (!rd) return;
    if (rd.conn) rd.conn.close();
    if (rd.view) rd.view.destroy();
    rd = null;
    readerEl.replaceChildren(); noteEl.replaceChildren();
  }
  /** opens [id]'s conversation in the reader; [none] is a session splice holds no transcript for, which is said at once */
  function openReader(id, none) {
    closeReader();
    const s = S.get(id);
    const mine = rd = { id, view: null, conn: null, state: none ? 'gone' : null, more: false, drawn: false, holding: false,
      marked: false, newFrom: null, newCount: 0, newPlaced: false, seenAt: s ? s.seenAt : 0 };
    atEnd = true;
    paintNote();
    if (none || !reading) return;
    readerParts().then(({ mount, connect }) => {
      if (rd !== mine) return;
      mine.view = mount(readerEl, {});
      const feed = { addHistory: ms => { markNew(mine, ms); mine.view.addHistory(ms); }, addLive: ev => mine.view.addLive(ev) };
      mine.conn = connect(feed, {
        session: id,
        onLocked: () => { if (rd === mine) { mine.state = 'failed'; paintNote(); } },
        onEarlier: more => { if (rd === mine) { mine.more = more; paintNote(); } },
        // splice's own words for a read it refused go to the browser's log, never the page
        onState: (st, said) => {
          if (rd !== mine) return;
          if (st === 'failed' && said) console.warn('conversation ' + id + ': ' + said);
          mine.state = st; paintNote();
        },
      });
    }, () => { if (rd === mine) { mine.state = 'failed'; paintNote(); } });
  }
  readerEl.addEventListener('replyview:change', () => {
    if (!rd) return;
    const first = !rd.drawn; rd.drawn = true;
    placeNewMark(rd);
    if (first) { paintNote(); requestAnimationFrame(revealNewest); }
    else if (atEnd && !rd.holding) scrollToEnd(true);
  });

  /* the question waiting on him: a Deck.answer just above the composer */
  function syncDock(s) {
    if (!s || !s.ask || s.state !== 'needs' || !s.ask.choices.length) { if (dockEl._k) { dockEl._k = ''; dockEl.replaceChildren(); } return; }
    const k = askKey(s.ask);
    if (dockEl._k !== k) {
      dockEl._k = k;
      dockEl.replaceChildren(Deck.answer(s.ask, { waited: waitText(s), onAnswer: (i, label, typed) => answerSession(s.id, i, typed, k) }));
      const box = dockEl.firstElementChild;
      if (box && !isTyping()) box.focus({ preventScroll: true });
      // a question that arrives while the newest line was in sight comes into sight itself
      if (atEnd) scrollToEnd(true);
    }
    const w = dockEl.querySelector('.ask-wait'); if (w) setTxt(w, waitText(s));
  }

  /* the open session's facts and actions, as instruments on the right */
  function buildFacts() {
    const R = $('#railR'); R.replaceChildren(); F = {};
    const s = S.get(openId);
    const sec1 = h('section', 'sec');
    F.lede = h('div', 'lede'); F.ledeLamp = h('span'); F.ledeT = h('span');
    F.lede.append(F.ledeLamp, F.ledeT);
    F.model = kvRow('Model'); F.provider = kvRow('Provider'); F.account = kvRow('Account');
    sec1.append(F.lede, F.model.el, F.provider.el, F.account.el);
    const sec2 = h('section', 'sec');
    F.stateV = h('span', 'inst-v mono'); F.stateSlot = h('div');
    sec2.append(inst('In this state', F.stateV, F.stateSlot, h('p', 'inst-cap', 'Each minute of the last hour')));
    const sec3 = h('section', 'sec');
    F.reqV = h('span', 'inst-v mono');
    F.reqBar = h('div', 'rl-reqbar'); F.reqI = h('i'); F.reqBar.append(F.reqI);
    F.reqCap = h('p', 'inst-cap');
    sec3.append(inst('Live request', F.reqV, F.reqBar, F.reqCap));
    const sec4 = h('section', 'sec');
    F.callsSpark = Deck.spark(new Array(12).fill(0)); F.callsV = h('span', 'inst-v mono');
    sec4.append(inst('Activity', F.callsV, F.callsSpark.el, h('p', 'inst-cap', 'Requests, in five-minute blocks')));
    const sec5 = h('section', 'sec');
    F.ctx = Deck.meter(0, 'var(--prussian)'); F.ctxV = h('span', 'inst-v mono');
    sec5.append(inst('Context', F.ctxV, F.ctx, h('p', 'inst-cap', 'Its last request against its window')));
    const sec6 = h('section', 'sec');
    F.turns = kvRow('Turns'); F.started = kvRow('Started'); F.team = kvRow('Team'); F.worktree = kvRow('Worktree'); F.place = kvRow('Folder');
    sec6.append(F.turns.el, F.started.el, F.team.el, F.worktree.el, F.place.el);
    const sec7 = h('section', 'sec');
    sec7.append(h('h2', 'eyebrow', 'Actions'));
    const acts = h('div', 'rl-acts');
    F.endBtn = rowBtn('End this session', 'Its conversation is kept', () => endSession(openId), true);
    if (!s.ended) acts.append(
      rowBtn('Stop', 'Ends the current request', () => stopSession(openId)),
      rowBtn('Continue on…', 'Another model, same session', e => pickModel(e.currentTarget, (p, m) => setModelOf(openId, p, m), { key: s.head, model: s.model })),
      rowBtn('Switch account…', 'Another login of its command', e => pickAccount(e.currentTarget, a => setAccountOf(openId, a), s.account)),
      rowBtn('Move to team…', 'A team, or a new one', e => pickTeam(e.currentTarget, openId)),
      rowBtn('Copy SendMessage line', 'Lets another session message it', () => copyLine(openId)),
      F.endBtn,
    );
    else acts.append(h('p', 'faint small', 'Ended. Its conversation is kept.'));
    sec7.append(acts);
    const sec8 = h('section', 'sec');
    sec8.append(h('h2', 'eyebrow', 'Name'));
    const rn = h('div', 'rl-rename');
    F.name = h('input'); F.name.id = 'rName'; F.name.setAttribute('aria-label', 'Session name');
    F.name.addEventListener('keydown', e => { e.stopPropagation(); if (e.key === 'Enter') { e.preventDefault(); renameSession(openId, F.name.value); } });
    const rnb = h('button', 'btn quiet press', 'Rename'); rnb.type = 'button';
    rnb.addEventListener('click', () => renameSession(openId, F.name.value));
    rn.append(F.name, rnb);
    F.sendline = h('p', 'rl-send');
    sec8.append(rn, F.sendline);
    F.tasksSec = h('section', 'sec');
    F.tasksSec.append(h('h2', 'eyebrow', 'Suggested tasks'));
    F.tasksList = h('div', 'rl-tasks');
    F.tasksSec.append(F.tasksList);
    F.tasksSec.hidden = true;
    F.chapsSec = h('section', 'sec');
    F.chapsSec.append(h('h2', 'eyebrow', 'Chapters'));
    F.chapsList = h('div', 'rl-chaps');
    F.chapsSec.append(F.chapsList);
    F.chapsSec.hidden = true;
    R.append(sec1, F.tasksSec, F.chapsSec, sec2, sec3, sec4, sec5, sec6, sec7, sec8);
    F.stripDone = false; F.lamp = null; F.provKey = null; F.tasksSig = ''; F.chapsSig = '';
    F.name.value = s.name;
  }
  async function actSuggestedTask(task, mode) {
    const claim = await API.post('/api/suggested-tasks/' + enc(task.task_id) + '/claim', { surface: 'console' });
    if (!claim.ok) { composer.note('stalled', why(claim), 3000); await read(); return; }
    if (mode === 'dismiss') {
      await API.post('/api/suggested-tasks/' + enc(task.task_id) + '/resolve', { outcome: 'dismissed' });
      composer.note('idle', 'Dismissed ' + task.title, 2500);
      await read();
      return;
    }
    if (mode === 'here') {
      const sent = await API.post('/api/sessions/' + enc(task.session_id) + '/say', { text: task.prompt });
      if (sent.ok) {
        await API.post('/api/suggested-tasks/' + enc(task.task_id) + '/resolve', { outcome: 'here' });
        composer.note('idle', 'Sent to this session', 2500);
      } else {
        await API.post('/api/suggested-tasks/' + enc(task.task_id) + '/resolve', { outcome: 'released' });
        composer.note('stalled', why(sent), 3500);
      }
      await read();
      return;
    }
    const s = S.get(task.session_id);
    const cwd = task.cwd || (s && s.place) || '';
    const started = await API.post('/api/sessions/start', { prompt: task.prompt, cwd, title: task.title });
    if (started.ok && started.body && started.body.session_id) {
      await API.post('/api/suggested-tasks/' + enc(task.task_id) + '/resolve', { outcome: 'started', session_id: started.body.session_id });
      composer.note('idle', 'Started ' + task.title, 2500);
    } else {
      await API.post('/api/suggested-tasks/' + enc(task.task_id) + '/resolve', { outcome: 'released' });
      composer.note('stalled', why(started), 3500);
    }
    await read();
  }
  function paintTasksAndChapters(s) {
    if (!F.tasksSec || !F.chapsSec) return;
    const tasks = suggestedTasks.filter(t => t.session_id === s.id && (t.state === 'pending' || t.state === 'in_flight'));
    const tSig = tasks.map(t => t.task_id + ':' + t.state).join('|');
    if (tSig !== F.tasksSig) {
      F.tasksSig = tSig;
      F.tasksList.replaceChildren();
      setHidden(F.tasksSec, tasks.length === 0);
      for (const task of tasks) {
        const card = h('div', 'rl-task'); card.dataset.taskId = task.task_id;
        card.append(h('div', 'rl-task-title', task.title || task.task_id));
        if (task.tldr) card.append(h('p', 'rl-task-tldr', task.tldr));
        const btns = h('div', 'rl-task-acts');
        const busy = task.state === 'in_flight';
        const mk = (lbl, mode, cls) => {
          const b = h('button', 'btn press ' + cls, lbl); b.type = 'button'; b.disabled = busy;
          b.addEventListener('click', () => actSuggestedTask(task, mode));
          return b;
        };
        btns.append(mk('Start', 'start', 'primary'), mk('Do it here', 'here', 'quiet'), mk('Dismiss', 'dismiss', 'quiet'));
        card.append(btns);
        F.tasksList.append(card);
      }
    }
    const chaps = sessionChapters.filter(c => c.session_id === s.id);
    const cSig = chaps.map(c => c.created_at + ':' + c.title).join('|');
    if (cSig !== F.chapsSig) {
      F.chapsSig = cSig;
      F.chapsList.replaceChildren();
      setHidden(F.chapsSec, chaps.length === 0);
      for (const ch of chaps) {
        const row = h('div', 'rl-chap');
        row.append(h('span', 'rl-chap-title', ch.title));
        const ts = epoch(ch.created_at);
        if (ts) row.append(h('span', 'rl-chap-at mono', agoText(Math.max(0, Date.now() - ts))));
        if (ch.summary) row.title = ch.summary;
        F.chapsList.append(row);
      }
    }
  }
  function rowBtn(label, sub, run, danger) {
    const b = h('button', 'rl-row press' + (danger ? ' danger' : '')); b.type = 'button';
    b.append(h('span', 'rl-r-nm', label), h('span', 'rl-r-sub', sub));
    b.addEventListener('click', e => run(e));
    return b;
  }
  function paintFacts() {
    const s = S.get(openId);
    if (!s || !F.model) return;
    if (F.lamp !== s.state) { F.lamp = s.state; F.ledeLamp.replaceChildren(Deck.lamp(s.ended ? 'ended' : s.state)); }
    setTxt(F.ledeT, s.ended ? 'Ended' : wordOf(s));
    setTxt(F.model.v, modelLabel(s) || 'Not through Splice');
    const pr = providerOf(s), pk = pr ? pr.id : 'none';
    if (F.provKey !== pk) {
      F.provKey = pk;
      const dot = h('span', 'rl-pdot');
      if (pr) dot.style.setProperty('--pc', pr.color); else dot.hidden = true;
      F.provider.v.replaceChildren(dot, pr ? (SPLICE.providers.filter(x => x.name === pr.name).length < 2 ? pr.name : pr.name + ' · ' + pr.command) : 'Direct');
    }
    setTxt(F.account.v, loginNameOf(s) || 'None');
    setTxt(F.stateV, ago(Date.now() - s.since));
    if (!F.stripDone || tickN % 5 === 0) { F.stripDone = true; Deck.stripInto(F.stateSlot, histOf(s).map(st => ({ state: st, share: 1 / WIN }))); }
    if (s.request) {
      const r = s.request, age = Date.now() - r.startAbs;
      setTxt(F.reqV, r.compact ? 'Compacting' : r.phase === 'streaming' ? 'Streaming' : r.phase === 'queued' ? 'In line' : 'Sent');
      F.reqBar.classList.remove('is-none');
      F.reqBar.classList.toggle('is-connect', r.phase !== 'streaming' && !r.compact);
      F.reqI.style.setProperty('--u', String(Math.min(1, age / MIN)));
      setTxt(F.reqCap, (r.phase === 'queued' ? 'Waiting in line' : PHASE[r.phase] || 'Sent') + ' · ' + ago(age) + (r.retries ? ' · ' + Deck.count(r.retries, 'retry', 'retries') : ''));
    } else {
      setTxt(F.reqV, 'None');
      F.reqBar.classList.add('is-none');
      F.reqI.style.setProperty('--u', '0');
      setTxt(F.reqCap, 'No request running');
    }
    const blocks = new Array(12).fill(0), t = Date.now(), ticks = ticksOf(s.id);
    for (const k of ticks) { const b = 11 - Math.floor((t - k.t) / (5 * MIN)); if (b >= 0 && b < 12) blocks[b]++; }
    F.callsSpark.update(blocks);
    setTxt(F.callsV, String(ticks.filter(k => t - k.t < 5 * MIN).length) + ' in 5 min');
    // the room its last request took in its window: what splice was sent, against the window its client reported
    const last = ticks[ticks.length - 1], win = s.window || windowOf(s);
    if (last && last.tokens && win) { const u = Math.min(1, last.tokens / win); F.ctx.set(u); setTxt(F.ctxV, Deck.pct(u)); }
    else { F.ctx.set(0); setTxt(F.ctxV, '—'); }
    setTxt(F.turns.v, s.turns == null ? '—' : String(s.turns));
    setTxt(F.started.v, ago(Date.now() - s.startedAt) + ' ago');
    const t2 = teamOf(s.id);
    setTxt(F.team.v, t2 ? t2.name : 'Not in a team');
    setTxt(F.worktree.v, s.worktree || 'None');
    setTxt(F.place.v, s.place || '—');
    setTxt(F.sendline, 'SendMessage(to="' + s.name + '")');
    paintTasksAndChapters(s);
  }
  const windows = new Map();
  function windowOf(s) { return windows.get(s.head + '/' + s.model) || null; }
  async function readWindows() {
    const r = await API.get('/api/models');
    for (const row of (r.body && r.body.heads) || []) for (const m of row.models || []) if (m.id && m.context_window) windows.set(row.head + '/' + m.id, m.context_window);
  }
  function renameSession(id, v) {
    const s = S.get(id), nv = (v || '').trim();
    if (!s || !nv) return;
    keepName(id, nv === (s.row.name || s.row.title) ? '' : nv);
    s.name = nv;
    composer.note('idle', 'Renamed in this browser', 2500);
    paint();
  }
  function copyLine(id) {
    const s = S.get(id); if (!s) return;
    const line = 'SendMessage(to="' + s.name + '")';
    const fail = () => composer.note('stalled', 'Could not copy here. Select the line instead', 3000);
    if (navigator.clipboard && navigator.clipboard.writeText) navigator.clipboard.writeText(line).then(() => composer.note('idle', 'Copied ' + line, 2500), fail);
    else fail();
  }

  /* ---------- the inspect glass for pickers ---------- */
  function pickRow(label, sub, onClick, checked) {
    const b = h('button', 'pk-row press'); b.type = 'button';
    const left = h('span');
    left.append(h('span', 'nm', label), h('span', 'sub', sub || ''));
    const ck = h('span');
    if (checked) ck.innerHTML = ICON.check;
    b.append(left, ck);
    b.addEventListener('click', onClick);
    return b;
  }
  function pickModel(anchor, cb, cur) {
    const showProviders = () => ins.openBeside(anchor, el => {
      el.append(h('div', 'eyebrow', 'Continue on'));
      const list = h('div', 'pick-list');
      SPLICE.providers.forEach(p => list.append(pickRow(p.name, p.command, () => showModels(p), !!cur && cur.key === p.key)));
      el.append(list);
    }, document.body);
    const showModels = p => ins.openBeside(anchor, el => {
      el.append(h('div', 'eyebrow', p.name + ' · ' + p.command));
      const list = h('div', 'pick-list');
      list.append(pickRow('Providers', 'Back', showProviders, false));
      p.models.forEach(m => list.append(pickRow(m[0], m[1], () => { ins.close(); cb(p, m); }, !!cur && cur.model === m[1])));
      el.append(list);
    }, document.body);
    showProviders();
  }
  /** the logins of the session's own command, as /api/accounts lists them */
  /** a session's login by the name /api/accounts gives it, as Accounts names it; its label where splice names no login */
  function loginNameOf(s) {
    if (!s.account) return null;
    const r = accounts.find(a => (a.heads || []).includes(s.head) && ((a.account_labels && a.account_labels[s.head]) || a.label) === s.account);
    return r ? r.display_name || r.email || r.label : s.account;
  }
  function accountList(s) { return accounts.filter(a => (a.heads || []).includes(s.head) && a.credential_present !== false); }
  function pickAccount(anchor, cb, current) {
    const s = S.get(openId); if (!s) return;
    ins.openBeside(anchor, el => {
      el.append(h('div', 'eyebrow', 'Switch account'));
      const list = h('div', 'pick-list');
      const mine = accountList(s);
      if (!mine.length) list.append(h('p', 'faint small', 'Its command has no other login.'));
      mine.forEach(a => list.append(pickRow(a.display_name || a.label, a.label, () => { ins.close(); cb(a.label); }, a.label === current)));
      el.append(list);
    }, document.body);
  }
  function pickTeam(anchor, id) {
    ins.openBeside(anchor, el => {
      el.append(h('div', 'eyebrow', 'Move to team'));
      const list = h('div', 'pick-list');
      const cur = (teamOf(id) || { id: 'loose' }).id;
      list.append(pickRow('Not in a team', 'Loose', () => { ins.close(); moveSession(id, 'loose'); }, cur === 'loose'));
      teams.forEach(t => list.append(pickRow(t.name, t.members.length + (t.members.length === 1 ? ' session' : ' sessions'), () => { ins.close(); moveSession(id, t.id); }, cur === t.id)));
      list.append(pickRow('New team', 'Starts a team with this session', () => { ins.close(); createTeam(id, false); }, false));
      el.append(list);
    }, document.body);
  }

  /* ---------- sessions: stop, end, change, each sent where the old console sent it ---------- */
  async function stopSession(id) {
    const s = S.get(id); if (!s) return false;
    const r = s.request && s.request.turn && !SCREEN.has(id)
      ? await API.post('/api/heads/' + enc(s.head) + '/turns/' + enc(s.request.turn) + '/stop')
      : await API.post('/api/sessions/' + enc(id) + '/stop');
    said(r, 'Stopped', 'Not stopped');
    await read();
    return r.ok;
  }
  async function stopMany(ids) {
    const live = ids.filter(i => { const s = S.get(i); return s && s.state !== 'idle'; });
    const got = await Promise.all(live.map(i => API.post('/api/sessions/' + enc(i) + '/stop')));
    const n = got.filter(r => r.ok).length;
    composer.note(n === live.length ? 'idle' : 'stalled', live.length ? 'Stopped ' + n + ' of ' + live.length : 'Nothing was running', 3000);
    await read();
  }
  async function redirectSession(id, text) {
    let r = await API.post('/api/sessions/' + enc(id) + '/stop');
    if (r.ok) r = await API.post('/api/sessions/' + enc(id) + '/say', { text });
    said(r, 'Redirected', 'Not redirected');
    await read();
  }
  async function setModelOf(id, p, m) {
    const r = await API.post('/api/sessions/' + enc(id) + '/continue', { head: p.key, model: m[1] });
    said(r, 'Continued on ' + m[0], 'Not moved');
    if (r.ok && openId === id) composer.setModel(p.id, m[0]);
    await read();
  }
  async function setAccountOf(id, label) {
    const s = S.get(id); if (!s) return;
    const r = await API.post('/api/auth/' + enc(s.head) + '/switch', { label, session: id });
    said(r, 'Switched to ' + label, 'Not switched');
    await read();
  }
  /** End acts on its one press: ending a session is the owner's act (Nov 19, 2026) */
  async function endSession(id) {
    const r = await API.post('/api/sessions/' + enc(id) + '/end', { confirm: true });
    said(r, 'Ended', 'Not ended');
    if (r.ok) closeOpen();
    await read();
  }
  function showView(mode) {
    board.hidden = mode !== 'board';
    convEl.hidden = mode !== 'conv';
    endEl.hidden = mode !== 'ended';
    $('#steer').hidden = mode === 'conv' && S.get(openId) && S.get(openId).ended;
  }
  function revealNewest() {
    const last = newest();
    window.scrollTo(0, 0);
    if (!last) return;
    const band = $('#band'), gap = Deck.px('--sp-4');
    const r = last.getBoundingClientRect();
    // the lanes stay under the bar on a wide screen and ride up with the page on a phone
    const stays = getComputedStyle(band).position === 'sticky';
    const roof = stays ? parseFloat(getComputedStyle(band).top) + band.offsetHeight : Deck.px('--top-clear');
    const dy = Math.min(r.bottom - (footTop() - footGap()), r.top - (roof + gap));
    if (dy > 0) window.scrollTo(0, dy);
  }

  function openSession(id, held) {
    if (!S.has(id)) return;
    const fromBoard = !openId && !openEnded;
    if (fromBoard) { boardY = window.scrollY; lookAt = Date.now(); }
    if (openId && openId !== id && S.get(openId)) S.get(openId).seenAt = Date.now();
    openId = id; openEnded = null;
    doorDone = id;
    if (doorId() !== id) history.replaceState(history.state, '', '#' + enc(id));
    showView('conv');
    const s = S.get(id), p = providerOf(s);
    dockEl._k = ''; dockEl.replaceChildren();
    openReader(id, !!held);
    buildBand(s);
    buildFacts();
    if (p) composer.setModel(p.id, modelLabel(s) || (p.models[0] ? p.models[0][0] : ''));
    paint();
    requestAnimationFrame(revealNewest);
    if (!REDUCE_MOTION()) convEl.animate([{ opacity: 0, transform: 'translateY(' + Deck.px('--sp-4') + 'px)' }, { opacity: 1, transform: 'none' }], { duration: Deck.px('--dur') * 1.2, easing: 'cubic-bezier(0.2, 0.8, 0.2, 1)' });
  }
  const REDUCE_MOTION = () => matchMedia('(prefers-reduced-motion: reduce)').matches;
  function closeOpen() {
    if (!openId && !openEnded) return;
    if (openId && S.get(openId)) { S.get(openId).seenAt = Date.now(); if (S.get(openId).ended) S.delete(openId); }
    openId = null; openEnded = null; bandKey = '';
    closeReader();
    if (location.hash) history.replaceState(history.state, '', location.pathname + location.search);
    doorDone = '';
    showView('board');
    buildFleet();
    paint();
    window.scrollTo(0, boardY);
  }

  /* ---------- the composer: the open session, a @team or a @session; with nothing open, the manager ---------- */
  function contextLine() {
    if (openId && S.has(openId)) {
      const s = S.get(openId);
      return { state: s.state, text: 'To ' + s.name + ' · ' + (modelLabel(s) || 'direct') + ' · ' + (loginNameOf(s) || 'no account') };
    }
    const c = counts();
    const named = teams.filter(x => !inferred(x)).length;
    return { state: 'serving', text: 'To the manager · ' + Deck.count(c.total, 'live session') + (named ? ' in ' + named + (named === 1 ? ' team' : ' teams') : '') };
  }
  function mentionList() {
    const out = teams.filter(t => t.members.length).map(t => {
      const ms = t.members.map(i => S.get(i)).filter(Boolean);
      const st = ms.some(s => s.state === 'needs') ? 'needs' : ms.some(s => s.state === 'working') ? 'working' : 'idle';
      return { label: '@' + slug(t), sub: 'Team · ' + ms.length + (ms.length === 1 ? ' session' : ' sessions'), state: st, insert: '@' + slug(t) + ' ' };
    });
    for (const s of S.values()) if (!s.ended) out.push({ label: '@' + s.name, sub: (s.project || 'no project') + ' · ' + s.state, state: s.state, insert: '@' + s.name + ' ' });
    return out;
  }
  function targetsOf(text) {
    const ids = new Set();
    for (const m of text.matchAll(/@(\S+)/g)) {
      const tok = m[1].toLowerCase();
      const t = teams.find(x => slug(x) === tok);
      if (t) { t.members.forEach(i => { if (S.has(i)) ids.add(i); }); continue; }
      const s = liveList().find(x => x.name.toLowerCase() === tok);
      if (s) ids.add(s.id);
    }
    if (!ids.size && openId && !S.get(openId).ended) ids.add(openId);
    return [...ids];
  }
  function runCommand(text) {
    const cmd = text.slice(1).trim().split(/\s+/)[0].toLowerCase();
    if (cmd === 'help') { composer.note('serving', 'Commands: /stop, /end, /continue, /switch, /rename, /team', 6000); return 0; }
    if (!openId) { composer.note('stalled', 'Open a session first, then /' + cmd, 3000); return 0; }
    if (cmd === 'stop') stopSession(openId);
    else if (cmd === 'end') endSession(openId);
    else if (cmd === 'continue') $('#stModel').click();
    else if (cmd === 'switch') $('#stAcct').click();
    else if (cmd === 'rename') { const el = $('#rName'); if (el) el.focus(); }
    else if (cmd === 'team') pickTeam($('#stMsg'), openId);
    else composer.note('stalled', 'No command /' + cmd, 3000);
    return 0;
  }
  /** what he typed, whole, to each session it names (POST say), or to the manager's session with nothing open */
  async function sendTo(ids, text) {
    if (!ids.length) return SPLICE.manager.say(text);
    const got = await Promise.all(ids.map(id => API.post('/api/sessions/' + enc(id) + '/say', { text })));
    const bad = got.find(r => !r.ok);
    read();
    if (!bad) return { state: 'idle', said: ids.length === 1 ? 'Sent' : 'Sent to ' + ids.length + ' sessions' };
    return { state: 'stalled', said: 'Not sent: ' + why(bad) };
  }
  function onSend(msg) {
    const text = msg.text.trim();
    if (text.charAt(0) === '/') return runCommand(text);
    return sendTo(targetsOf(text), text);
  }
  const composer = Deck.composer($('#compWrap'), {
    providers: SPLICE.providers,
    selected: { provider: 'claude', model: '' },
    effort: 'high',
    modes: SPLICE.MODES,
    commands: [
      ['stop', 'Stop the open session'], ['end', 'End the open session'], ['continue', 'Continue it on another model'],
      ['switch', 'Switch its account'], ['rename', 'Rename it'], ['team', 'Move it to a team'], ['help', 'What these do'],
    ],
    mentions: mentionList,
    context: contextLine,
    placeholder: 'Message the open session, a @team or a @session. / for actions',
    workingText: 'Sending',
    onSend,
    onStop: () => { if (openId) stopSession(openId); },
  });
  SPLICE.ready.then(() => { const p = SPLICE.providers[0]; if (p && !openId) composer.setModel(p.id, p.models[0] ? p.models[0][0] : p.command); });

  /* ---------- the steering row: message, redirect, continue on another model, switch account, stop ---------- */
  $('#stMsg').addEventListener('click', () => composer.focus());
  stRedir.addEventListener('click', () => {
    const open = redir.hidden;
    redir.hidden = !open;
    stRedir.setAttribute('aria-expanded', String(open));
    if (open) redirIn.focus();
  });
  redir.addEventListener('submit', e => {
    e.preventDefault();
    const text = redirIn.value.trim();
    if (!text || !openId) return;
    redirectSession(openId, text);
    redirIn.value = '';
    redir.hidden = true;
    stRedir.setAttribute('aria-expanded', 'false');
  });
  $('#stModel').addEventListener('click', e => { const s = S.get(openId); if (s) pickModel(e.currentTarget, (p, m) => setModelOf(openId, p, m), { key: s.head, model: s.model }); });
  $('#stAcct').addEventListener('click', e => { const s = S.get(openId); if (s) pickAccount(e.currentTarget, a => setAccountOf(openId, a), s.account); });
  $('#stStop').addEventListener('click', () => { if (openId) stopSession(openId); });

  /* ---------- drag: a session between teams, a team along the header ---------- */
  let drag = null, suppress = false, scrollTimer = 0, lastY = 0;
  function laneAt(x, y) { const el = document.elementFromPoint(x, y); return el ? el.closest('.team[data-lane]') : null; }
  function hoverLane(x, y) {
    const col = laneAt(x, y);
    board.querySelectorAll('.team.is-over').forEach(c => { if (c !== col) c.classList.remove('is-over'); });
    if (col) col.classList.add('is-over');
  }
  function startGhost(e) {
    const base = drag.kind === 'row' ? drag.src : drag.src.querySelector('.team-hd');
    const r = base.getBoundingClientRect();
    const g = base.cloneNode(true);
    g.classList.add('drag-ghost');
    g.removeAttribute('tabindex');
    g.style.setProperty('width', r.width + 'px');
    document.body.append(g);
    drag.ghost = g;
    drag.ox = e.clientX - r.left;
    drag.oy = e.clientY - r.top;
    drag.src.classList.add('is-dragsrc');
  }
  function dragMove(e) {
    if (!drag) return;
    lastY = e.clientY;
    if (!drag.on) {
      if (Math.hypot(e.clientX - drag.x0, e.clientY - drag.y0) < Deck.px('--sp-2')) return;
      drag.on = true;
      startGhost(e);
      document.body.classList.add('is-dragging');
      scrollTimer = setInterval(autoScroll, 30);
    }
    drag.ghost.style.setProperty('left', (e.clientX - drag.ox) + 'px');
    drag.ghost.style.setProperty('top', (e.clientY - drag.oy) + 'px');
    if (drag.kind === 'row') hoverLane(e.clientX, e.clientY);
  }
  function dragEnd(e) {
    window.removeEventListener('pointermove', dragMove);
    window.removeEventListener('pointerup', dragEnd);
    window.removeEventListener('pointercancel', dragEnd);
    clearInterval(scrollTimer);
    const d = drag; drag = null;
    document.body.classList.remove('is-dragging');
    board.querySelectorAll('.is-over').forEach(c => c.classList.remove('is-over'));
    if (!d || !d.on) return;
    if (d.ghost) d.ghost.remove();
    d.src.classList.remove('is-dragsrc');
    suppress = true; setTimeout(() => { suppress = false; }, 80);
    if (e.type === 'pointercancel') return;
    if (d.kind === 'row') {
      const col = laneAt(e.clientX, e.clientY);
      if (!col) return;
      const lane = col.dataset.lane;
      if (lane === 'new') createTeam(d.id, true);
      else if (lane !== (teamOf(d.id) || { id: 'loose' }).id) moveSession(d.id, lane);
    } else reorderTeam(d.id, e.clientX, e.clientY);
  }
  function autoScroll() {
    if (!drag || !drag.on) return;
    const edge = Deck.px('--top-clear'), vh = window.innerHeight, step = Deck.px('--sp-4');
    if (lastY < edge) window.scrollBy(0, -step);
    else if (lastY > vh - edge) window.scrollBy(0, step);
  }
  board.addEventListener('pointerdown', e => {
    if (e.button !== 0 || e.target.closest('button, input, form, .ask')) return;
    const row = e.target.closest('.srow'), hd = e.target.closest('.team-hd');
    if (row) drag = { kind: 'row', id: row.dataset.sid, src: row, x0: e.clientX, y0: e.clientY, on: false };
    else if (hd && hd.dataset.drag === 'team') drag = { kind: 'team', id: hd.closest('.team').dataset.team, src: hd.closest('.team'), x0: e.clientX, y0: e.clientY, on: false };
    else return;
    lastY = e.clientY;
    window.addEventListener('pointermove', dragMove);
    window.addEventListener('pointerup', dragEnd);
    window.addEventListener('pointercancel', dragEnd);
  });
  board.addEventListener('click', e => {
    if (suppress) return;
    const row = e.target.closest('.srow');
    if (!row || e.target.closest('button, input, form, .ask')) return;
    openSession(row.dataset.sid);
  });
  board.addEventListener('keydown', e => {
    const row = e.target.closest && e.target.closest('.srow');
    if (!row || e.target !== row) return;
    if (e.key === 'Enter' || e.key === ' ') { e.preventDefault(); openSession(row.dataset.sid); }
    else if (e.altKey && (e.key === 'ArrowLeft' || e.key === 'ArrowRight')) { e.preventDefault(); stepLane(row.dataset.sid, e.key === 'ArrowLeft' ? -1 : 1); }
  });
  document.addEventListener('keydown', e => {
    const typing = isTyping();
    if (e.key === 'Escape' && !e.defaultPrevented && !typing && (openId || openEnded)) closeOpen();
    if (e.key === '/' && !openId && !openEnded && !typing) { e.preventDefault(); qIn.focus(); }
    if (/^[1-9]$/.test(e.key) && openId && !typing) {
      const box = dockEl.firstElementChild;
      if (box && box.classList.contains('ask')) { e.preventDefault(); box.dispatchEvent(new KeyboardEvent('keydown', { key: e.key, bubbles: false })); }
    }
  });

  /* ---------- search: names, projects, what they are on; ended sessions through splice's history ---------- */
  let searchTimer = 0;
  qIn.addEventListener('input', () => {
    filterText = qIn.value.trim().toLowerCase();
    paint();
    clearTimeout(searchTimer);
    searchTimer = setTimeout(searchPast, 400);
  });
  async function searchPast() {
    const q = filterText;
    const box = $('#pastHits') || (() => { const el = h('section', 'team loose past'); el.id = 'pastHits'; return el; })();
    if (!q) { box.remove(); return; }
    const r = await API.get('/api/sessions/history?limit=50&query=' + enc(q));
    if (q !== filterText) return;
    const rows = r.ok && r.body && Array.isArray(r.body.sessions) ? r.body.sessions.filter(x => x.session_id && !S.has(x.session_id)) : [];
    const hd = h('header', 'team-hd'), top = h('div', 'team-top'), idn = h('div', 'team-id');
    idn.append(h('div', 'eyebrow team-kick', 'Ended · matching ' + q), h('h2', 'team-name', rows.length + (rows.length === 1 ? ' session' : ' sessions')));
    top.append(h('span', 'team-lamp'), idn); hd.append(top);
    const body = h('div', 'team-rows');
    for (const row of rows) {
      const b = h('button', 'rl-row press'); b.type = 'button';
      b.append(h('span', 'rl-r-nm', names.get(row.session_id) || row.name || row.title || row.session_id.slice(0, 8)),
        h('span', 'rl-r-sub', ((row.project && row.project.name) || 'No project') + ' · ' + agoText(Date.now() - (epoch(row.updated_at) || Date.now()))));
      b.addEventListener('click', () => openGone(row));
      body.append(b);
    }
    if (!r.ok) body.append(h('p', 'faint small', 'History not read: ' + why(r)));
    box.replaceChildren(hd, body);
    if (!box.isConnected) board.append(box);
  }
  backBtn.addEventListener('click', closeOpen);
  new ResizeObserver(() => fitBoard()).observe(board);

  /* ---------- the clock, and the reads on their own clocks ---------- */
  function setClock() { setTxt($('#clock'), readOk === false && readAt ? 'last read ' + agoText(Date.now() - readAt) : Deck.clock()); }
  const once = (fn) => { let busy = false; return async () => { if (busy || document.hidden || document.body.classList.contains('locked')) return; busy = true; try { await fn(); } finally { busy = false; } }; };
  const readNow = once(read), perfNow = once(async () => { await readPerf(); await readAccounts(); });
  function tick() {
    tickN++;
    if (tickN % 60 === 0) events = events.filter(e => e.t > Date.now() - 130 * MIN);
    paint();
    drawBand();
    setClock();
  }

  /* ---------- a door to one session by its address (sessions.html#<id>, as Teams and Activity link it) ---------- */
  const doorId = () => decodeURIComponent(location.hash.replace(/^#/, ''));
  let doorDone = '';
  async function openDoor() {
    const id = doorId();
    if (!id || id === doorDone || readOk === null) return;
    doorDone = id;
    if (S.has(id)) return openSession(id);
    const past = GONE.get(id) || (await (async () => {
      const r = await API.get('/api/sessions/history?limit=5&query=' + enc(id));
      return r.ok && r.body && Array.isArray(r.body.sessions) ? r.body.sessions.find(x => x.session_id === id) : null;
    })());
    if (past) return openGone(past);
    // splice knows no session by this id: the conversation's place says so, and Back leads to the board
    const s = sessionOf({ session_id: id, name: null, title: null, availability: 'stale', status: 'idle' }, new Map(), new Map());
    s.ended = true; s.name = id.slice(0, 8);
    S.set(id, s);
    openSession(id, true);
  }
  addEventListener('hashchange', () => { doorDone = ''; openDoor(); });

  /* ---------- start ---------- */
  buildFleet();
  renderBoard();
  paint();
  setClock();
  (async () => { await SPLICE.ready; await readNow(); openDoor(); await perfNow(); readWindows(); paint(); })();
  setInterval(tick, 1000);
  setInterval(readNow, READ_MS);
  setInterval(perfNow, PERF_MS);
  addEventListener('splice:signed-in', () => { readNow(); perfNow(); });
})();
