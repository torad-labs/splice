/* Activity: the approved Night Deck page (prototype activity/final), on splice's live reads. The last 7 days of requests
   splice sent to providers: five figures that open where each came from, the week by hour (one colour per provider,
   failures under each hour), where it went (providers, accounts, models, projects, sessions), and the latest requests,
   each opening its glass with its timing on one bar. Read, not driven: no rails, no composer.

   Reads, once a minute while the page is shown, as today's Usage and Requests read them: per command, GET
   /api/perf/turns?head=<key>&since=<7 days>&n=1 (the daemon's folded totals per account, model, session and day, so the
   week's figures are never a count of the newest rows) and ?n=60&usage=0 (the newest requests), both through the
   console's performance queue; /api/economics (requests, failed, rate limited and value per command per hour);
   /api/sessions (a session's name and project; Projects fold the per-session totals). Commands and model names come
   from deck-common's SPLICE. A figure nobody reported is a dash.

   Its address: activity.html?session=<id>, ?project=<name>, ?account=<label>, ?model=<id>, ?provider=<command> (one
   filter, shown in .ac-filter) and #request=<turn id> (that request's glass). */
(() => {
  'use strict';
  const { $, $$, h } = Deck, SP = window.SPLICE;
  const ac = $('#ac'), ins = Deck.inspector(ac);
  const ZONE = Intl.DateTimeFormat().resolvedOptions().timeZone, HOUR = 3600e3, ROWS_N = 60, READ_MS = 60000;
  /* the window every figure covers, Usage's three: drawn by hour for 5 hours and 7 days, by day for 30 days, since 720
     hourly columns would not read. It rides the address (?win=5h|7d|30d, 7 days when absent). */
  const WINS = { '5h': { label: '5 hours', cols: 5, byDay: false }, '7d': { label: '7 days', cols: 168, byDay: false }, '30d': { label: '30 days', cols: 30, byDay: true } };
  let win = WINS[new URLSearchParams(location.search).get('win')] ? new URLSearchParams(location.search).get('win') : '7d';
  /** the column edges of the window ending now: whole hours, or this browser's days */
  function edgesFor(now) {
    const W = WINS[win];
    if (!W.byDay) { const top = Math.floor(now / HOUR) * HOUR + HOUR; return Array.from({ length: W.cols + 1 }, (_, i) => top - (W.cols - i) * HOUR); }
    const d = new Date(now); d.setHours(0, 0, 0, 0); d.setDate(d.getDate() - (W.cols - 1));
    const out = []; for (let i = 0; i <= W.cols; i++) { out.push(+d); d.setDate(d.getDate() + 1); }
    return out;
  }
  /** where the window's reads start: the last 5 hours exactly, or the first column's start */
  const sinceOf = (now, edges) => (win === '5h' ? now - 5 * HOUR : edges[0]);

  /* ---------- words and numbers ---------- */
  const n0 = n => n == null ? '—' : Math.round(n).toLocaleString('en-US');
  const big = n => n == null ? '—' : n >= 1e9 ? `${(n / 1e9).toFixed(1)} B` : n >= 1e6 ? `${Math.round(n / 1e6)} M` : n >= 1e4 ? `${Math.round(n / 1e3)} K` : n0(n);
  const usd = v => v == null ? '—' : v >= 100 ? `$${n0(v)}` : v >= 1 ? `$${v.toFixed(2)}` : v >= 0.001 ? `$${v.toFixed(3)}` : v > 0 ? '<$0.001' : '$0';
  // a share too small to show in tenths is said as under a tenth, never as 0.0% (which reads as none)
  const pct = u => u == null || !isFinite(u) ? '—' : u >= 0.1 ? `${Math.round(u * 100)}%` : u > 0 && u < 0.001 ? '<0.1%' : `${(u * 100).toFixed(1)}%`;
  const secs = ms => { if (ms == null) return ''; if (ms < 1000) return `${ms} ms`; if (ms < 9950) return `${(ms / 1000).toFixed(1)} s`; const s0 = Math.round(ms / 1000); if (s0 < 60) return `${s0} s`; const m = Math.round(s0 / 60); if (s0 < 3600) return `${Math.floor(s0 / 60)} min${s0 % 60 ? ` ${s0 % 60} s` : ''}`; return `${Math.floor(m / 60)} h${m % 60 ? ` ${m % 60} min` : ''}`; };
  const bytes = b => !b ? '' : b >= 1e6 ? `${(b / 1e6).toFixed(1)} MB` : b >= 1e3 ? `${Math.round(b / 1e3)} KB` : `${b} B`;
  const dayOf = ts => new Date(ts).toLocaleDateString('en-US', { timeZone: ZONE, weekday: 'short' });
  const clock = ts => new Date(ts).toLocaleTimeString('en-US', { timeZone: ZONE, hour: 'numeric', minute: '2-digit' });
  const clockS = ts => new Date(ts).toLocaleTimeString('en-US', { timeZone: ZONE, hour: 'numeric', minute: '2-digit', second: '2-digit' });
  const ymd = ts => new Date(ts).toLocaleDateString('en-CA', { timeZone: ZONE });
  const when = ts => ymd(ts) === ymd(A.readAt) ? clockS(ts) : `${dayOf(ts)} ${clock(ts)}`;
  const hourWord = ts => (WINS[win].byDay ? `${dayOf(ts)} ${new Date(ts).toLocaleDateString('en-US', { timeZone: ZONE, month: 'short', day: 'numeric' })}` : `${dayOf(ts)} ${new Date(ts).toLocaleTimeString('en-US', { timeZone: ZONE, hour: 'numeric' })}`);

  const provOf = cmd => SP.providerOf(cmd);
  const pc = cmd => (provOf(cmd) || {}).color || 'var(--ink-faint)';
  // a provider is named alone, as the approved pages name it; its command is added only where two commands share the provider
  const pname = cmd => { const p = provOf(cmd); return p ? (p.name === p.command || SP.providers.filter(x => x.name === p.name).length < 2 ? p.name : `${p.name} · ${p.command}`) : retired.has(cmd) ? `${cmd} · removed` : cmd; };
  const mainCmd = o => Object.entries(o.by || {}).sort((a, b) => b[1] - a[1])[0]?.[0] || o.cmd;
  const RANK_A = ['var(--seg-a)', 'var(--seg-b)', 'var(--seg-c)', 'var(--seg-d)', 'var(--seg-e)'];
  const modelName = key => (key ? SP.modelName(key) : 'Model not recorded');

  const OUT = {
    ok: ['OK', 'ok'], empty_message: ['Empty response', 'bad'], client_abort: ['Cancelled', 'quiet'], 'error:stopped': ['Cancelled', 'quiet'],
    'error:restarted': ['Cut by restart', 'bad'], 'error:rate-limited': ['Rate limited', 'bad'], 'error:conn-reset': ['Connection reset', 'bad'],
  };
  // every other ending is Failed, in NAMES.md's words; the daemon's own tag stays in the request's glass
  const outcome = o => OUT[o] || ['Failed', 'bad'];
  const outLamp = o => { const k = outcome(o)[1]; return Deck.lamp(k === 'ok' ? 'ready' : k === 'quiet' ? 'next' : 'limit', k === 'ok' ? 'var(--verdigris)' : k === 'quiet' ? 'var(--ink-faint)' : 'var(--vermilion)'); };
  const isFailed = o => outcome(o)[1] === 'bad';
  const isCancelled = o => outcome(o)[1] === 'quiet';

  const GO = '<svg class="ico" viewBox="0 0 24 24"><path d="M8 16L16 8M10 8h6v6"/></svg>';
  const go = () => { const s = h('span', 'pt-go'); s.innerHTML = GO; return s; };
  const dot = c => { const i = h('i', 'dot'); i.style.setProperty('--c', c); return i; };

  /* ---------- the live read, in the page's shape ---------- */
  let A = { readAt: Date.now(), edges: edgesFor(Date.now()), since: 0, totals: {}, providers: [], accounts: [], models: [], sessions: [], projects: [], rows: [], sessionCount: null, projectCount: null, modelCount: null, accountCount: null };
  let readOk = null, gates = new Map(), retired = new Map();
  /* the doors other pages open the list with (?why=), each asked of the daemon the way Requests asked it (TurnsFilter.kt),
     so it narrows the whole window and not only the newest rows; ?order=price lists each command's highest priced first */
  const WHY = {
    queued: { word: 'Waited in line', ask: { queued_ms: '0' } }, waited: { word: 'Waited out', ask: { silence_ms: 'tier' } },
    restarted: { word: 'splice restarted', ask: { outcome: 'error:restarted' } }, overloaded: { word: 'Overloaded', ask: { outcome: 'failure:overloaded_error' } },
    gaveup: { word: 'Given up', ask: { outcome: 'failed' } }, resumed: { word: 'Resumed', ask: { resumed: '1', started_over: '0' } },
    startedover: { word: 'Started over', ask: { started_over: '1' } },
  };
  /** a door's ask for one command; a silence floor is that command's own setting, and one it does not report asks nothing */
  /** what the daemon is asked for the filter in force: a model by its id, a login by its label, a session by its tag */
  function filterAsk() {
    const f = ui.filter; if (!f) return {};
    if (f.kind === 'model') return { model: f.key };
    if (f.kind === 'account' && f.key !== 'No login') return { account: f.key };
    if (f.kind === 'session') return { session: shortOf(f.key) };
    return {};
  }
  function whyAsk(key) {
    const w = WHY[ui.why]; if (!w) return {};
    const out = {};
    for (const [k, v] of Object.entries(w.ask)) {
      if (v !== 'tier') { out[k] = v; continue; }
      const g = gates.get(key) || {}, tier = g.stream_idle_ms ?? g.stall_reanchor_ms;
      if (Number.isFinite(tier)) out[k] = String(Math.round(tier));
    }
    return out;
  }
  const num = x => (typeof x === 'number' ? x : 0);
  const shortOf = id => String(id || '').slice(0, 8);
  async function read() {
    await SP.ready;
    const now = Date.now(), edges = edgesFor(now), since = sinceOf(now, edges), COLS = edges.length - 1, asked = win;
    const colOf = ts => { if (ts < edges[0] || ts >= edges[COLS]) return -1; let k = 0; while (edges[k + 1] <= ts) k++; return k; };
    // commands removed from Providers keep their history: their requests are read and listed under their own name
    const [hd, rt] = await Promise.all([API.get('/api/heads'), API.get('/api/heads/retired')]);
    if (hd.ok) gates = new Map(((hd.body && hd.body.heads) || []).map(x => [x.key, x.gate || {}]));
    retired = new Map(((rt.ok && rt.body && rt.body.retired) || []).filter(x => !SP.providers.some(p => p.key === x.key)).map(x => [x.command || x.key, x.key]));
    // a filter the daemon can apply narrows every figure, the week and Where it went with the list, as Usage's did: a
    // command reads that command alone, a model, a login or a session is asked of the daemon (TurnsFilter.kt)
    const all = [...SP.providers, ...[...retired].map(([command, key]) => ({ key, command }))];
    const heads = ui.filter && ui.filter.kind === 'provider' ? all.filter(p => p.command === ui.filter.key) : all;
    const narrow = filterAsk();
    const q = (key, extra) => '/api/perf/turns?' + new URLSearchParams({ head: key, since: String(since), time_zone: ZONE, local: '0', ...extra });
    const rowAsk = key => { const a = { n: String(ROWS_N), usage: '0', ...filterAsk() }; if (ui.order === 'price') a.order = 'price'; return Object.assign(a, whyAsk(key)); };
    const [ec, ss, ...perf] = await Promise.all([
      API.getReady('/api/economics'), API.get('/api/sessions'),
      ...heads.flatMap(p => [
        API.perfRequest('perf:activity:totals:' + p.key, 'GET', q(p.key, { n: '1', ...narrow }), undefined, () => true, { ready: true }).then(r => r || { ok: false }),
        API.perfRequest('perf:activity:rows:' + p.key, 'GET', q(p.key, rowAsk(p.key))).then(r => r || { ok: false }),
      ]),
    ]);
    readOk = perf.some(r => r.ok) || ec.ok;
    const econ = Object.fromEntries(((ec.ok && ec.body && ec.body.heads) || []).map(x => [x.key, x]));
    const sess = new Map(((ss.ok && ss.body && ss.body.sessions) || []).map(s => [s.session_id, s]));
    const byShort = new Map([...sess.values()].map(s => [shortOf(s.session_id), s]));
    const sessionRow = id => sess.get(id) || byShort.get(shortOf(id)) || null;
    const sessName = (s, id) => (s && (s.name || (s.title ? String(s.title).slice(0, 60) : null))) || shortOf(id);
    const projOf = s => (s && s.project && s.project.name) || null;

    const providers = [], accounts = new Map(), models = new Map(), sessions = new Map(), projects = new Map();
    const add = (m, key, base, u, cmd) => {
      const o = m.get(key) || { ...base, requests: 0, out: 0, in: 0, cached: 0, value: 0, failed: 0, limited: 0, by: {} };
      o.requests += num(u.requests); o.out += num(u.output_tokens); o.in += num(u.input_tokens); o.cached += num(u.cached_tokens);
      o.value += num(u.plan_value_usd) + num(u.cost_usd); o.failed += num(u.failed_requests); o.limited += num(u.rate_limited_requests);
      o.by[cmd] = (o.by[cmd] || 0) + num(u.requests);
      m.set(key, o);
    };
    const rows = [];
    heads.forEach((p, i) => {
      const cmd = p.command, tot = perf[2 * i], got = perf[2 * i + 1];
      const hd = tot.ok && tot.body && (tot.body.heads || [])[0], u = hd && hd.usage, t = u && u.totals;
      const e = econ[p.key];
      const hours = Array(COLS).fill(0), hfail = Array(COLS).fill(0), hval = Array(COLS).fill(0);
      let eReq = null, eFail = null, eLim = null, eVal = null, ePaid = null;
      if (e) {
        eReq = eFail = eLim = eVal = ePaid = 0;
        for (const b of e.buckets || []) {
          if (b.hour < since) continue;
          const k = colOf(b.hour); if (k < 0) continue;
          hours[k] += num(b.turns); hfail[k] += num(b.failed_turns); hval[k] += num(b.plan_value_usd) + num(b.cost_usd);
          eReq += num(b.turns); eFail += num(b.failed_turns); eLim += num(b.rate_limited); eVal += num(b.plan_value_usd) + num(b.cost_usd); ePaid += num(b.cost_usd);
        }
      }
      // the week's figures are the daemon's folded totals; where it gave none, the hourly economics stand in for the
      // counts they hold, and the tokens read as a dash
      // its newest requests are listed whatever its totals say
      for (const r of (got.ok && got.body && (got.body.heads || [])[0] || {}).rows || []) {
        const s = sessionRow(r.session_id || r.session);
        rows.push({
          id: r.turn_id || r.turn, turn: r.turn_id || r.turn, head: p.key, ts: r.ts, cmd, model: r.model, session: (s && s.session_id) || r.session_id || r.session || null, sessionName: sessName(s, r.session_id || r.session || ''),
          project: (r.project && r.project.name) || projOf(s), title: r.title, account: r.account_label || null, outcome: r.outcome, tag: r.outcome, door: r.door,
          up: r.arrival_to_upstream_write_ms ?? null, firstByte: r.first_byte ?? null, total: r.total ?? null,
          in: r.in_tokens ?? null, out: r.out_tokens ?? null, cached: r.cached_tokens ?? null, cacheWrite: r.cache_write_tokens ?? null,
          value: num(r.plan_value_usd) + num(r.cost_usd), paid: r.cost_usd ?? null, retries: r.retries ?? 0, reqBytes: r.req_bytes ?? null, compact: !!r.compact,
          provMsg: r.provider_message ?? null, cause: r.cause ?? null, onPlan: r.cost_reason === 'plan' || (r.plan_value_usd != null && !r.cost_usd),
          heldMs: r.admit_held_ms ?? null, inflight: r.inflight ?? null, cutRounds: r.cut_source_rounds ?? 0,
        });
      }
      const has = t && num(t.requests) > 0;
      const requests = has ? t.requests : eReq;
      if (!requests) return;
      providers.push({
        cmd, key: p.key, name: pname(cmd), requests, out: has ? num(t.output_tokens) : null, in: has ? num(t.input_tokens) : null, cached: has ? num(t.cached_tokens) : null,
        value: has ? num(t.plan_value_usd) + num(t.cost_usd) : eVal, paid: has ? num(t.cost_usd) : ePaid, failed: has ? num(t.failed_requests) : eFail,
        limited: has ? num(t.rate_limited_requests) : eLim, hours, hfail, hval,
      });
      if (u) {
        for (const a of u.accounts || []) add(accounts, a.label || 'No login', { label: a.label || 'No login', cmd }, a, cmd);
        for (const m of u.models || []) add(models, m.key, { key: m.key, cmd, name: modelName(m.key) }, m, cmd);
        for (const s of u.sessions || []) {
          const row = sessionRow(s.key), id = row ? row.session_id : s.key;
          add(sessions, id, { id, name: sessName(row, s.key), project: projOf(row) }, s, cmd);
          add(projects, projOf(row) || 'No project', { name: projOf(row) || 'No project' }, s, cmd);
        }
      }
    });
    // a request names its login by its label; the page names it as the folded figures do, by the name splice gives it
    const loginNames = new Map(heads.flatMap((_, i) => (((perf[2 * i].ok && perf[2 * i].body && (perf[2 * i].body.heads || [])[0]) || {}).usage || {}).accounts || []).filter(a => a.key && a.label).map(a => [a.key, a.label]));
    for (const r of rows) if (r.account) r.account = loginNames.get(r.account) || r.account;
    if (asked !== win) return;           // the window changed while this read was out
    providers.sort((a, b) => b.requests - a.requests);
    rows.sort(ui.order === 'price' ? (a, b) => (b.value || 0) - (a.value || 0) || b.ts - a.ts : (a, b) => b.ts - a.ts);
    const top = (m, k) => [...m.values()].sort((a, b) => b.requests - a.requests).slice(0, k);
    const sum = f => (providers.length ? providers.reduce((s, p) => (s == null || f(p) == null ? null : s + f(p)), 0) : null);
    const folded = perf.some((r, i) => i % 2 === 0 && r.ok);
    A = {
      readAt: now, edges, since,
      totals: { requests: sum(p => p.requests), out: sum(p => p.out), in: sum(p => p.in), cached: sum(p => p.cached), value: sum(p => p.value), paid: sum(p => p.paid), failed: sum(p => p.failed), limited: sum(p => p.limited) },
      providers, accounts: top(accounts, 14), models: top(models, 16), sessions: top(sessions, 40), projects: top(projects, 16), rows,
      sessionCount: folded ? sessions.size : null, projectCount: folded ? projects.size : null, modelCount: folded ? models.size : null, accountCount: folded ? accounts.size : null,
    };
    paint();
  }

  /* ---------- the five figures ---------- */
  const figs = () => {
    const T = A.totals;
    return [
      { id: 'requests', name: 'Requests', fig: n0(T.requests), cap: `Last ${WINS[win].label}`, share: A.providers.map(p => [p.requests, pc(p.cmd)]) },
      { id: 'tokens', name: 'Output tokens', fig: big(T.out), cap: `Input ${big(T.in)} · ${T.in ? pct(T.cached / T.in) : '—'} cached`, share: A.providers.map(p => [p.out, pc(p.cmd)]) },
      { id: 'value', name: 'API value', fig: usd(T.value), cap: `Paid ${usd(T.paid)}`, share: A.accounts.map((a, i) => [a.value, pc(mainCmd(a)), RANK_A[i % 5]]) },
      { id: 'failed', name: 'Failed', fig: n0(T.failed), cap: `${T.requests ? pct(T.failed / T.requests) : '—'} of requests · ${n0(T.limited)} rate limited`, share: A.providers.filter(p => p.failed).map(p => [p.failed, pc(p.cmd)]) },
      { id: 'sessions', name: 'Sessions', fig: n0(A.sessionCount), cap: Deck.count(A.projectCount, 'project'), share: A.projects.map((p, i) => [p.requests, pc(mainCmd(p)), RANK_A[i % 5]]) },
    ];
  };
  const vbar = parts => { const b = h('span', 'pt-vbar'); parts.filter(p => p[0] > 0).forEach(([v, c, a]) => { const i = h('i'); i.style.setProperty('--share', v); i.style.setProperty('--c', c); if (a) i.style.opacity = a; b.append(i); }); return b; };
  function drawFigs() {
    const box = $('#figs'); box.replaceChildren();
    figs().forEach(f => {
      const b = h('button', 'pt press'); b.type = 'button'; b.dataset.fig = f.id;
      b.append(go(), h('span', 'pt-name', f.name), h('span', 'pt-fig', f.fig), h('span', 'pt-cap', f.cap), vbar(f.share));
      box.append(b);
    });
  }

  /* ---------- the week by hour ---------- */
  const provs = () => A.providers.filter(p => p.requests > 0);
  const hourTotal = i => provs().reduce((s, p) => s + p.hours[i], 0);
  const hourFail = i => provs().reduce((s, p) => s + p.hfail[i], 0);
  const hourVal = i => provs().reduce((s, p) => s + p.hval[i], 0);
  const tsOf = i => A.edges[i];
  const COLS = () => A.edges.length - 1;
  function drawStrip() {
    const cols = $('#cols'); cols.replaceChildren();
    const days = $('#days'); days.replaceChildren();
    const ps = provs(), maxHour = Math.max(1, ...Array.from({ length: COLS() }, (_, i) => hourTotal(i))), maxFail = Math.max(1, ...Array.from({ length: COLS() }, (_, i) => hourFail(i)));
    for (let i = 0; i < COLS(); i++) {
      const c = h('button', 'ac-col'); c.type = 'button'; c.dataset.hour = i; c.setAttribute('aria-label', `${hourWord(tsOf(i))}: ${Deck.count(hourTotal(i), 'request')}`);
      [...ps].reverse().forEach(p => { if (!p.hours[i]) return; const s = h('i'); s.style.setProperty('--h', p.hours[i] / maxHour); s.style.setProperty('--c', pc(p.cmd)); c.append(s); });
      const f = hourFail(i); if (f) { const b = h('b'); b.style.setProperty('--f', Math.max(0.35, f / maxFail)); c.append(b); }
      const hr = Number(new Date(tsOf(i)).toLocaleString('en-US', { timeZone: ZONE, hour: 'numeric', hour12: false }));
      if (WINS[win].byDay ? (new Date(tsOf(i)).getDay() === 1 || i === 0) : (hr === 0 || i === 0 || COLS() < 24)) {
        if (hr === 0) c.classList.add('is-day');
        const today = ymd(tsOf(i)) === ymd(A.readAt);
        const word = COLS() < 24 ? clock(tsOf(i)) : today ? 'Today' : `${dayOf(tsOf(i))} ${new Date(tsOf(i)).toLocaleDateString('en-US', { timeZone: ZONE, day: 'numeric' })}`;
        const s = h('span', today && COLS() >= 24 ? 'is-today' : '', word);
        s.style.setProperty('--x', i / COLS()); if (!(i === 0 && hr > 12 && !WINS[win].byDay && COLS() >= 24)) days.append(s);
      }
      cols.append(c);
    }
    // a day that began in the axis's last hours ends its label at the axis's end, never past the page's edge
    for (const s of days.children) if (s.offsetLeft + s.offsetWidth > days.clientWidth) s.classList.add('at-end');
    const keys = $('#keys'); keys.replaceChildren();
    ps.forEach(p => { const s = h('span'); s.append(dot(pc(p.cmd)), p.name); keys.append(s); });
    readHour(null);
  }
  function readHour(i) {
    const r = $('#read'); r.replaceChildren();
    if (i == null) {
      if (!provs().length) { r.append(readOk === false ? 'splice is not answering' : `No requests in the last ${WINS[win].label}`); return; }
      const busiest = Array.from({ length: COLS() }, (_, k) => k).sort((a, b) => hourTotal(b) - hourTotal(a))[0];
      r.append(WINS[win].byDay ? 'Busiest day ' : 'Busiest hour ', h('b', '', hourWord(tsOf(busiest))), ` · ${Deck.count(hourTotal(busiest), 'request')}`);
      return;
    }
    r.append(h('b', '', hourWord(tsOf(i))), ` · ${Deck.count(hourTotal(i), 'request')} · ${n0(hourFail(i))} failed · ${usd(hourVal(i))} API value`);
  }
  $('#cols').addEventListener('pointerover', e => { const c = e.target.closest('.ac-col'); if (c) readHour(+c.dataset.hour); });
  $('#cols').addEventListener('pointerleave', () => { if (!ins.open || !ins.open.classList.contains('ac-col')) readHour(null); });

  /* ---------- where it went ---------- */
  const WHERE = [
    { id: 'provider', name: 'Providers', items: () => A.providers.map(p => ({ key: p.cmd, label: p.name, c: pc(p.cmd), ...p })), count: () => A.providers.length },
    { id: 'account', name: 'Accounts', items: () => A.accounts.map((a, i) => ({ key: a.label, c: pc(mainCmd(a)), a: RANK_A[i % 5], ...a })), count: () => A.accountCount },
    { id: 'model', name: 'Models', items: () => A.models.map((m, i) => ({ label: m.name, c: pc(m.cmd), a: RANK_A[i % 5], ...m })), count: () => A.modelCount },
    { id: 'project', name: 'Projects', items: () => A.projects.map((p, i) => ({ key: p.name, label: p.name, c: pc(mainCmd(p)), a: RANK_A[i % 5], ...p })), count: () => A.projectCount },
    { id: 'session', name: 'Sessions', items: () => A.sessions.map((s, i) => ({ key: s.id, label: s.name, sub: s.project, c: pc(mainCmd(s)), a: RANK_A[i % 5], ...s })), count: () => A.sessionCount },
  ];
  function drawWhere() {
    const box = $('#where'); box.replaceChildren();
    WHERE.forEach(w => {
      const items = w.items();
      const b = h('button', 'pt press'); b.type = 'button'; b.dataset.where = w.id;
      b.append(go(), h('span', 'pt-name', w.name), h('span', 'pt-fig', n0(w.count())));
      b.append(vbar(items.slice(0, 8).map(x => [x.requests, x.c, x.a])));
      const wl = h('div', 'wl');
      items.slice(0, 3).forEach(x => { const d = h('div'), i = h('i'); i.style.setProperty('--c', x.c); if (x.a) i.style.setProperty('--a', x.a); d.append(i, h('span', '', x.label), h('b', '', A.totals.requests ? pct(x.requests / A.totals.requests) : '—')); wl.append(d); });
      b.append(wl); box.append(b);
    });
  }

  /* ---------- latest requests ---------- */
  const UQ = new URLSearchParams(location.search);
  const ui = { filter: null, outcome: 'all', order: UQ.get('order') === 'price' ? 'price' : 'newest', why: WHY[UQ.get('why')] ? UQ.get('why') : null };
  /** the order and the door ride the address with the window and the filter */
  function keepAddress() {
    const q = new URLSearchParams(location.search);
    if (ui.order === 'price') q.set('order', 'price'); else q.delete('order');
    if (ui.why) q.set('why', ui.why); else q.delete('why');
    history.replaceState(history.state, '', location.pathname + (q.toString() ? '?' + q : '') + location.hash);
  }
  const sameSession = (a, b) => !!a && !!b && (a === b || shortOf(a) === shortOf(b));
  const MATCH = {
    provider: (r, k) => r.cmd === k, account: (r, k) => (r.account || 'No login') === k, model: (r, k) => r.model === k,
    project: (r, k) => (r.project || 'No project') === k, session: (r, k) => sameSession(r.session, k),
    text: r => found.has(r.id),
  };
  /* ---------- the search over what was sent and what came back (GET /api/heads/{head}/trace/search, as Requests read it):
     every command, a removed one through its retired record; a hit the newest rows do not hold is listed as its own row ---------- */
  let found = new Map(), foundFor = '';
  async function searchText(q) {
    foundFor = q;
    const heads = [...SP.providers.map(p => [p.key, p.command]), ...[...retired].map(([command, key]) => [key, command])];
    const since = A.since || A.edges[0];
    const got = await Promise.all(heads.map(([key]) => API.get('/api/heads/' + encodeURIComponent(key) + '/trace/search?' + new URLSearchParams({ q, since: String(since), limit: '100' }))));
    if (foundFor !== q) return;
    found = new Map();
    heads.forEach(([key, cmd], i) => {
      const res = got[i];
      if (!res.ok) { console.warn('search ' + cmd + ': ' + ((res.body && res.body.error) || res.status)); return; }
      for (const hit of (res.body && res.body.hits) || []) {
        const id = hit.turn_id || hit.turn;
        found.set(id, hit.text ? String(hit.text).replace(/\s+/g, ' ').slice(0, 160) : '');
        if (!A.rows.some(r => r.id === id)) A.rows.push({ id, turn: id, head: key, cmd, ts: hit.ts, model: hit.model, session: hit.session || null, sessionName: shortOf(hit.session || ''),
          outcome: hit.outcome || 'ok', tag: hit.outcome, total: hit.total ?? null, firstByte: hit.first_byte ?? null, up: null, in: hit.in_tokens ?? null, out: hit.out_tokens ?? null,
          cached: hit.cached_tokens ?? null, cacheWrite: hit.cache_write_tokens ?? null, value: num(hit.cost_usd), paid: hit.cost_usd ?? null, retries: 0, heldMs: hit.admit_held_ms ?? null });
      }
    });
    A.rows.sort((a, b) => b.ts - a.ts);
    drawRows();
  }
  const shown = () => A.rows.filter(r => (!ui.filter || MATCH[ui.filter.kind](r, ui.filter.key)) && (ui.outcome === 'all' || (ui.outcome === 'failed' ? isFailed(r.outcome) : isCancelled(r.outcome))));
  const outChoice = Deck.choice([{ v: 'all', label: 'All' }, { v: 'failed', label: 'Failed' }, { v: 'cancelled', label: 'Cancelled' }], 'all', v => { ui.outcome = v; drawRows(); }, { label: 'Outcome' });
  const orderChoice = Deck.choice([{ v: 'newest', label: 'Newest' }, { v: 'price', label: 'Highest price' }], ui.order, v => { ui.order = v; keepAddress(); readNow(); }, { label: 'Order' });
  function drawTools() {
    const t = $('#tools'); t.replaceChildren();
    if (ui.filter) {
      const f = h('span', 'ac-filter'); f.append(dot(ui.filter.c || 'var(--ink-faint)'), ui.filter.label);
      const x = Deck.iconBtn('close', 'Clear filter'); x.addEventListener('click', () => setFilter(null)); f.append(x);
      t.append(f);
    }
    if (ui.why) {
      const f = h('span', 'ac-filter'); f.append(dot('var(--ink-faint)'), WHY[ui.why].word);
      const x = Deck.iconBtn('close', 'Clear'); x.addEventListener('click', () => { ui.why = null; keepAddress(); drawTools(); readNow(); }); f.append(x);
      t.append(f);
    }
    const s = h('button', 'btn quiet press', 'Search'); s.type = 'button'; s.title = 'Search ( / )'; s.addEventListener('click', openFind);
    t.append(orderChoice, outChoice, s);
  }
  const maxDur = () => { const t = A.rows.filter(r => r.total).map(r => r.total).sort((a, b) => a - b); return Math.max(1, t[Math.floor(t.length * 0.9)] || 1); };
  function drawRows() {
    const rows = shown(), box = $('#rows'), md = maxDur();
    $('#count').textContent = `${n0(rows.length)} of the ${n0(A.rows.length)} newest`;
    box.replaceChildren();
    const hd = h('div', 'rw-head'); [['Time'], [''], ['Model'], ['Session'], ['First token', 'r'], ['Duration'], ['Output', 'r'], ['API value', 'r'], ['']].forEach(([w, c]) => hd.append(h('span', c || '', w))); box.append(hd);
    if (!rows.length) { box.append(h('div', 'ac-empty', readOk === false ? 'splice is not answering' : 'None')); return; }
    rows.forEach(r => {
      const b = h('button', 'rw press' + (isFailed(r.outcome) ? ' is-bad' : '')); b.type = 'button'; b.dataset.row = A.rows.indexOf(r); b.dataset.request = r.id || ''; b.setAttribute('role', 'listitem');
      const m = h('span', 'rw-m'); m.append(dot(pc(r.cmd)), modelName(r.model));
      const s = h('span', 'rw-s'); s.append(h('span', '', r.sessionName), h('small', '', ui.filter && ui.filter.kind === 'text' && found.get(r.id) ? found.get(r.id) : [r.project, r.account].filter(Boolean).join(' · ')));
      const bar = h('span', 'rw-bar'), wait = h('i', 'wait'), stream = h('i', 'stream');
      const tot = Math.min(1, (r.total || 0) / md), w = r.total ? Math.min(tot, ((r.firstByte || 0) / md)) : 0;
      wait.style.setProperty('--w', w); stream.style.setProperty('--w', w); stream.style.setProperty('--s', Math.max(0, tot - w)); stream.style.setProperty('--c', pc(r.cmd));
      bar.append(wait, stream);
      const durCell = h('span', 'rw-dur'); durCell.append(bar, h('span', 'rw-n', secs(r.total)));
      b.append(h('span', 'rw-t', when(r.ts)), outLamp(r.outcome), m, s, h('span', 'rw-n', secs(r.firstByte)), durCell, h('span', 'rw-n is-ink', r.out != null ? n0(r.out) : ''), h('span', 'rw-n', r.value ? usd(r.value) : ''), go());
      // a request that did not answer says how it ended, in the page's words, where it is listed
      if (isFailed(r.outcome) || isCancelled(r.outcome)) b.querySelector('.rw-s small').prepend(outcome(r.outcome)[0] + (r.project || r.account ? ' · ' : ''));
      b.title = outcome(r.outcome)[0];
      box.append(b);
    });
  }
  const KEY_OF = { provider: 'provider', account: 'account', model: 'model', project: 'project', session: 'session', text: 'q' };
  /** one filter on the latest requests, carried in the address so a link and a reload land on it */
  function setFilter(f, quiet) {
    ui.filter = f;
    const q = new URLSearchParams(location.search);
    Object.values(KEY_OF).forEach(k => q.delete(k));
    if (f) q.set(KEY_OF[f.kind], f.key);
    history.replaceState(history.state, '', location.pathname + (q.toString() ? '?' + q : '') + location.hash);
    if (quiet) return;
    ins.close(); drawTools(); drawRows();
    if (!f || ['provider', 'model', 'account', 'session'].includes(f.kind)) readNow();
    $('#latestH').scrollIntoView({ behavior: matchMedia('(prefers-reduced-motion: reduce)').matches ? 'auto' : 'smooth', block: 'start' });
  }

  /* ---------- lenses ---------- */
  function listLens(body, items, { value = x => x.requests, fmt = n0, sub = () => '', kind, head = ['', 'Requests'], max } = {}) {
    const top = max || Math.max(1, ...items.map(value));
    const hd = h('div', 'lx-head'); hd.append(h('span', '', head[0]), h('span'), h('span', '', head[1])); body.append(hd);
    const list = h('div', 'lx-list');
    if (!items.length) list.append(h('div', 'ac-empty', readOk === false ? 'splice is not answering' : 'None'));
    items.forEach(x => {
      const r = h(kind ? 'button' : 'div', 'lx-row'); if (kind) { r.type = 'button'; r.addEventListener('click', () => setFilter({ kind, key: x.key, label: x.label, c: x.c })); r.title = 'Show its latest requests'; }
      const nm = h('span', 'lx-nm'), d = dot(x.c); if (x.a) d.style.opacity = x.a; const words = h('span', 'lx-words'); words.append(h('span', '', x.label)); const sb = sub(x); if (sb) words.append(h('small', '', sb)); nm.append(d, words);
      const bar = h('span', 'lx-bar'), i = h('i'); i.style.setProperty('--u', (value(x) || 0) / top); i.style.setProperty('--c', x.c); if (x.a) i.style.opacity = x.a; bar.append(i);
      r.append(nm, bar, h('span', 'lx-v', fmt(value(x)))); list.append(r);
    });
    body.append(list);
  }
  const figsLens = f => ({ title: f.name, wide: true, fill: body => {
    const T = A.totals, P = A.providers.map(p => ({ key: p.cmd, label: p.name, c: pc(p.cmd), ...p }));
    if (f.id === 'requests') listLens(body, P, { kind: 'provider', head: ['Provider', 'Requests'], sub: x => (T.requests ? pct(x.requests / T.requests) : '') });
    if (f.id === 'tokens') listLens(body, P.filter(p => p.out), { value: x => x.out, fmt: big, head: ['Provider', 'Output'], sub: x => `in ${big(x.in)} · ${pct(x.cached / Math.max(1, x.in))} cached`, kind: 'provider' });
    if (f.id === 'value') {
      const fg = h('div', 'lx-figs'); [[usd(T.value), 'API value'], [usd(T.paid), 'Paid']].forEach(([v, w]) => { const d = h('div', 'lx-fig'); d.append(h('b', '', v), h('span', '', w)); fg.append(d); }); body.append(fg);
      listLens(body, A.accounts.map((a, i) => ({ key: a.label, c: pc(mainCmd(a)), a: RANK_A[i % 5], ...a })), { value: x => x.value, fmt: usd, head: ['Account', 'API value'], sub: x => Deck.count(x.requests, 'request'), kind: 'account' });
    }
    if (f.id === 'failed') listLens(body, P.filter(p => p.failed), { value: x => x.failed, head: ['Provider', 'Failed'], sub: x => `${pct(x.failed / x.requests)} · ${n0(x.limited)} rate limited`, kind: 'provider' });
    if (f.id === 'sessions') listLens(body, A.sessions.slice(0, 20).map((s, i) => ({ key: s.id, label: s.name, c: pc(mainCmd(s)), a: RANK_A[i % 5], ...s })), { head: ['Session', 'Requests'], sub: x => x.project || '', kind: 'session' });
  } });
  /** a breakdown, ranked by requests or, when asked, by API value, so a costly key with few requests leads */
  let rankBy = 'requests';
  const whereLens = w => ({ title: w.name, wide: true, fill: body => {
    const list = h('div');
    const paintList = () => {
      list.replaceChildren();
      const items = w.items().slice().sort((x, y) => (rankBy === 'value' ? (y.value || 0) - (x.value || 0) : y.requests - x.requests)).slice(0, 20);
      listLens(list, items, { kind: w.id, value: x => (rankBy === 'value' ? x.value : x.requests), fmt: rankBy === 'value' ? usd : n0, head: [w.name.replace(/s$/, ''), rankBy === 'value' ? 'API value' : 'Requests'],
        sub: x => `${rankBy === 'value' ? Deck.count(x.requests, 'request') : usd(x.value)} · ${big(x.out)} out${x.failed ? ` · ${n0(x.failed)} failed` : ''}` });
    };
    body.append(Deck.choice([{ v: 'requests', label: 'Requests' }, { v: 'value', label: 'API value' }], rankBy, v => { rankBy = v; paintList(); }, { label: 'Rank by' }), list);
    paintList();
  } });
  const hourLens = i => ({ title: hourWord(tsOf(i)), wide: true, fill: body => {
    const fg = h('div', 'lx-figs'); [[n0(hourTotal(i)), 'Requests'], [n0(hourFail(i)), 'Failed'], [usd(hourVal(i)), 'API value']].forEach(([v, w]) => { const d = h('div', 'lx-fig'); d.append(h('b', '', v), h('span', '', w)); fg.append(d); }); body.append(fg);
    listLens(body, provs().filter(p => p.hours[i]).map(p => ({ key: p.cmd, label: p.name, c: pc(p.cmd), requests: p.hours[i], failed: p.hfail[i], value: p.hval[i] })), { head: ['Provider', 'Requests'], sub: x => `${x.failed ? `${n0(x.failed)} failed · ` : ''}${usd(x.value)}` });
  } });
  const requestLens = r => ({ title: modelName(r.model), dotC: pc(r.cmd), wide: true, fill: body => {
    const [word] = outcome(r.outcome);
    const o = h('div', 'lx-out' + (isFailed(r.outcome) ? ' is-bad' : '')); o.append(outLamp(r.outcome), word, h('span', 'faint', ` · ${when(r.ts)}`)); body.append(o);
    // why a failed request failed: the provider's message where it sent one, else how splice recorded the ending
    if (isFailed(r.outcome)) body.append(h('div', 'lx-title', String(r.provMsg || r.cause || r.tag || '').slice(0, 200)));
    if (r.title) body.append(h('div', 'lx-title', String(r.title).slice(0, 160)));
    {
      const rail = h('div', 'rq-rail'), bar = h('div', 'rail-bar');
      const up = r.up || 0, fb = Math.max(up, r.firstByte || up), tot = Math.max(fb, r.total || fb);
      [[up, 'var(--prussian)'], [fb - up, 'var(--ink-faint)'], [tot - fb, pc(r.cmd)]].forEach(([v, c]) => { const i = h('i'); i.style.setProperty('--share', Math.max(v, Math.max(1, tot) * 0.004)); i.style.setProperty('--c', c); bar.append(i); });
      const keys = h('div', 'rail-keys'); [['Sent to provider', r.up == null ? '—' : secs(r.up)], ['Time to first token', r.firstByte == null ? '—' : secs(r.firstByte)], ['Duration', r.total == null ? '—' : secs(r.total)]].forEach(([w, v]) => { const s = h('span'); s.append(h('b', '', v), w); keys.append(s); });
      rail.append(bar, keys); body.append(rail);
    }
    if (r.in != null) {
      body.append(h('div', 'lx-sep'));
      const tok = h('div', 'tok'); [[r.cached || 0, 'var(--prussian)', 'var(--seg-c)'], [(r.in || 0) - (r.cached || 0), 'var(--prussian)', ''], [r.out || 0, pc(r.cmd), '']].forEach(([v, c, a]) => { const i = h('i'); i.style.setProperty('--share', Math.max(v, 1)); i.style.setProperty('--c', c); if (a) i.style.opacity = a; tok.append(i); });
      body.append(tok);
    }
    const kv = h('div', 'lx-kv');
    const rowsKV = [
      ['Input tokens', r.in != null ? n0(r.in) : null], ['Cached', r.in && r.cached ? `${n0(r.cached)} · ${pct(r.cached / r.in)}` : null], ['Cache written', r.cacheWrite ? n0(r.cacheWrite) : null], ['Output tokens', r.out != null ? n0(r.out) : null],
      [r.onPlan ? 'API value · on your plan' : 'API value', r.value ? usd(r.value) : null], ['Paid', r.paid ? usd(r.paid) : null],
      ['Waited in line', r.heldMs > 0 ? secs(r.heldMs) : null],
      // a round splice cut (code mode) carries no tokens; the next request of its session carries them
      ['Tokens', r.in == null && r.outcome === 'ok' ? (A.rows.some(x => x.ts > r.ts && x.cutRounds > 0 && sameSession(x.session, r.session)) ? 'Counted on the next request' : 'No usage reported') : null],
      ['Counts earlier requests', r.cutRounds > 0 ? n0(r.cutRounds) : null], ['Running when it arrived', r.inflight != null ? n0(r.inflight) : null],
      ['Provider', pname(r.cmd)], ['Account', r.account], ['Session', r.sessionName], ['Project', r.project], ['From', r.door === 'desktop' ? 'Claude Desktop' : r.door === 'terminal' ? 'Terminal' : null],
      ['Request size', bytes(r.reqBytes)], ['Retries', r.retries ? String(r.retries) : null], ['Compaction', r.compact ? 'Yes' : null],
    ];
    const KEYC = { Cached: ['var(--prussian)', 'var(--seg-c)'], 'Input tokens': ['var(--prussian)', ''], 'Output tokens': [pc(r.cmd), ''] };
    rowsKV.filter(([, v]) => v).forEach(([k, v]) => { const d = h('div'), l = h('span'); if (KEYC[k] && r.in != null) { const i = dot(KEYC[k][0]); if (KEYC[k][1]) i.style.opacity = KEYC[k][1]; l.append(i, ' '); } l.append(k); d.append(l, h('b', '', v)); kv.append(d); });
    body.append(h('div', 'lx-sep'), kv);
    const acts = h('div', 'lx-acts');
    // the document splice kept of what it sent and what came back, in an extra-wide glass, read only once opened
    const full = h('button', 'btn press', 'Full request'); full.type = 'button';
    full.addEventListener('click', () => { const a = ins.open; open(a, { title: modelName(r.model), dotC: pc(r.cmd), xwide: true, fill: b => DeckRequest.fill(b, r) }); });
    acts.append(full);
    if (r.session) {
      const btn = h('button', 'btn quiet press', 'Show this session'); btn.type = 'button';
      btn.addEventListener('click', () => setFilter({ kind: 'session', key: r.session, label: r.sessionName, c: pc(r.cmd) }));
      acts.append(btn);
    }
    body.append(acts);
  } });

  function open(anchor, spec) {
    $$('[aria-expanded="true"]').forEach(x => x.setAttribute('aria-expanded', 'false'));
    anchor.setAttribute('aria-expanded', 'true');
    ins.openBeside(anchor, el => {
      el.classList.toggle('wide', !!spec.wide && !spec.xwide);
      el.classList.toggle('xwide', !!spec.xwide);
      const hd = h('div', 'hd'); if (spec.dotC) hd.append(Deck.lamp('ready', spec.dotC));
      hd.append(h('h3', '', spec.title));
      const x = Deck.iconBtn('close', 'Close'); x.addEventListener('click', () => ins.close()); hd.append(x);
      const body = h('div', 'lx'); el.append(hd, body); spec.fill(body);
    }, onScreen());
  }
  /** the part of the frame on screen, below the bar: a lens is placed inside it */
  const onScreen = () => ({ getBoundingClientRect: () => { const r = ac.getBoundingClientRect(); return { left: r.left, right: r.right, top: document.querySelector('.nav').getBoundingClientRect().bottom + Deck.px('--sp-4'), bottom: innerHeight }; } });
  new MutationObserver(() => {
    if (!ins.el.hidden) return;
    $$('[aria-expanded="true"]').forEach(x => x.setAttribute('aria-expanded', 'false')); $$('.ac-col.is-on').forEach(c => c.classList.remove('is-on')); readHour(null);
    if (location.hash.startsWith('#request=')) history.replaceState(history.state, '', location.pathname + location.search);
  }).observe(ins.el, { attributes: true, attributeFilter: ['hidden'] });
  ac.addEventListener('click', e => {
    const f = e.target.closest('[data-fig]'), w = e.target.closest('[data-where]'), c = e.target.closest('.ac-col'), r = e.target.closest('.rw');
    const t = f || w || c || r; if (!t) return;
    if (ins.open === t) { ins.close(); return; }
    if (f) open(f, figsLens(figs().find(x => x.id === f.dataset.fig)));
    else if (w) open(w, whereLens(WHERE.find(x => x.id === w.dataset.where)));
    else if (c) { $$('.ac-col.is-on').forEach(x => x.classList.remove('is-on')); c.classList.add('is-on'); readHour(+c.dataset.hour); open(c, hourLens(+c.dataset.hour)); }
    else if (r) openRequest(r);
  });
  /** a request's glass, its turn id in the address so the link can be shared */
  function openRequest(el) {
    const r = A.rows[+el.dataset.row]; if (!r) return;
    if (r.id) history.replaceState(history.state, '', location.pathname + location.search + '#request=' + encodeURIComponent(r.id));
    open(el, requestLens(r));
  }

  /* ---------- "/" finds a provider, account, model, project or session and filters the list to it ---------- */
  const find = Deck.glass('lens find', document.body), fin = h('input'), flist = h('div', 'find-list');
  fin.placeholder = 'Search'; fin.setAttribute('aria-label', 'Search providers, accounts, models, projects, sessions'); find.append(fin, flist);
  let fOff = null, fSel = 0, fHits = [];
  const KIND_WORD = { provider: 'Provider', account: 'Account', model: 'Model', project: 'Project', session: 'Session', text: 'Prompts and answers' };
  const index = () => WHERE.flatMap(w => w.items().map(x => ({ kind: w.id, key: x.key, label: x.label, c: x.c, words: `${x.label} ${x.key} ${x.sub || x.project || ''}`.toLowerCase() })));
  function paintFind() {
    const q = fin.value.trim().toLowerCase();
    fHits = q ? index().filter(x => x.words.includes(q)).slice(0, 12) : [];
    // what was said: three letters or more searches the prompts and answers splice kept
    if (q.length >= 3) fHits.push({ kind: 'text', key: fin.value.trim(), label: `Sent or returned: “${fin.value.trim()}”`, c: 'var(--ink-faint)' });
    fSel = Math.min(fSel, Math.max(0, fHits.length - 1));
    flist.replaceChildren(...fHits.map((x, i) => { const b = h('button', i === fSel ? 'is-on' : ''); b.type = 'button'; const nm = h('span'); nm.append(dot(x.c), ' ', x.label); b.append(nm, h('b', '', KIND_WORD[x.kind])); b.addEventListener('click', () => pick(x)); return b; }));
  }
  const closeFind = () => { if (find.hidden) return; fOff && fOff(); fOff = null; Deck.hide(find); };
  const pick = x => { closeFind(); if (x.kind === 'text') { found = new Map(); setFilter({ kind: 'text', key: x.key, label: `“${x.key}”`, c: x.c }); searchText(x.key); return; } setFilter({ kind: x.kind, key: x.key, label: x.label, c: x.c }); };
  function openFind() { ins.close(); fin.value = ''; fSel = 0; paintFind(); Deck.show(find, [find.offsetWidth / 2, 0]); fOff = Deck.layer(find, closeFind); fin.focus(); }
  fin.addEventListener('input', () => { fSel = 0; paintFind(); });
  fin.addEventListener('keydown', e => {
    if (e.key === 'ArrowDown' || e.key === 'ArrowUp') { e.preventDefault(); fSel = Math.max(0, Math.min(fHits.length - 1, fSel + (e.key === 'ArrowDown' ? 1 : -1))); paintFind(); }
    if (e.key === 'Enter' && fHits[fSel]) { e.preventDefault(); pick(fHits[fSel]); }
  });
  addEventListener('keydown', e => { if (e.key === '/' && !/INPUT|TEXTAREA/.test(document.activeElement.tagName)) { e.preventDefault(); openFind(); } });

  /* ---------- the address: one filter by name, and a request to open ---------- */
  function takeAddress() {
    const q = new URLSearchParams(location.search);
    for (const kind of Object.keys(KEY_OF)) {
      const key = q.get(KEY_OF[kind]); if (!key) continue;
      if (kind === 'text') { ui.filter = { kind, key, label: `“${key}”`, c: 'var(--ink-faint)' }; break; }
      const w = WHERE.find(x => x.id === kind), it = w.items().find(x => kind === 'session' ? sameSession(x.key, key) : x.key === key);
      const row = A.rows.find(r => MATCH[kind](r, key));
      const label = it ? it.label : kind === 'session' ? (row ? row.sessionName : shortOf(key)) : kind === 'model' ? modelName(key) : kind === 'provider' ? pname(key) : key;
      ui.filter = { kind, key, label, c: it ? it.c : row ? pc(row.cmd) : null };
      break;
    }
  }
  let opened = '';
  function openFromHash() {
    const m = /^#request=(.+)$/.exec(location.hash); if (!m) return;
    const id = decodeURIComponent(m[1]); if (id === opened) return;
    const el = $(`.rw[data-request="${CSS.escape(id)}"]`);
    if (el) { opened = id; el.scrollIntoView({ block: 'center' }); openRequest(el); }
  }
  addEventListener('hashchange', () => { opened = ''; openFromHash(); });

  /* ---------- header and the reads on their clock ---------- */
  function paint() {
    $('#acRange').textContent = `Last ${WINS[win].label} · ${new Date(A.since || A.edges[0]).toLocaleDateString('en-US', { timeZone: ZONE, month: 'short', day: 'numeric' })} to ${new Date(A.readAt).toLocaleDateString('en-US', { timeZone: ZONE, month: 'short', day: 'numeric' })}`;
    const running = [...gates.values()].reduce((n, g) => n + (g.inflight || 0), 0);
    // the clock beside it says the time now; the read's own time shows only once it is no longer now, when splice stops answering
    $('#acRunning').textContent = readOk === false ? (A.readAt ? `Not answering · last read ${clock(A.readAt)}` : 'Not answering') : `${Deck.count(running, 'request')} running`;
    if (!ins.open) { drawFigs(); drawStrip(); drawWhere(); }
    drawTools(); drawRows();
    openFromHash();
  }
  const tick = () => { $('#acClock').textContent = Deck.clock(); };
  tick(); setInterval(tick, 30000);
  let reading = false, first = true, again = false;
  const readNow = async () => {
    if (reading) { again = true; return; }           // a change made while a read is out is read once it lands
    if (document.hidden || document.body.classList.contains('locked')) return;
    reading = true;
    try {
      if (first) { first = false; await SP.ready; takeAddress(); }
      await read();
      // a search over what was said is asked again over the new rows
      if (ui.filter && ui.filter.kind === 'text') await searchText(ui.filter.key);
    } finally { reading = false; }
    if (again) { again = false; readNow(); }
  };
  /* the window choice, in the header beside its clock */
  const winChoice = Deck.choice(Object.entries(WINS).map(([v, w]) => ({ v, label: w.label })), win, v => {
    win = v;
    const q = new URLSearchParams(location.search); if (v === '7d') q.delete('win'); else q.set('win', v);
    history.replaceState(history.state, '', location.pathname + (q.toString() ? '?' + q : '') + location.hash);
    ins.close();
    A = Object.assign({}, A, { edges: edgesFor(Date.now()), since: 0, providers: [], accounts: [], models: [], sessions: [], projects: [], totals: {}, sessionCount: null, projectCount: null, modelCount: null, accountCount: null });
    paint(); readNow();
  }, { label: 'Window' });
  { const st = $('.ac-head .status'), r = h('div', 'ac-head-r'); st.before(r); r.append(winChoice, st); }
  takeAddress(); drawFigs(); drawStrip(); drawWhere(); drawTools(); drawRows();
  readNow();
  setInterval(readNow, READ_MS);
  addEventListener('splice:signed-in', readNow);
})();
