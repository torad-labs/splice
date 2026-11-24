/* Accounts: the approved Night Deck page (prototype accounts/final), on splice's live reads. The field is a time axis:
   each Claude account is a lane sitting where it becomes usable again (now, the days ahead, or needs sign-in). Drag a
   lane to change the order splice moves through them; press its name to inspect it in glass. The left rail holds the
   account serving now, today on Claude and who comes back next; the right rail the other providers, whose logins open
   the same glass (Nov 21, 2026: use now, rename, sign out and Add login are there for every provider that signs in
   with a login, as the old page had them, and not for Claude alone). The composer asks the manager.

   Reads, every minute as the old page read them and again after each act: /api/accounts, /api/auth/{head}/order,
   /api/sessions (who is on which account, and the moves onto it), /api/keys, /api/economics (today by hour). Writes go
   where the old console sent them: the order (PUT …/order), Use now (POST …/switch), a name (PUT /api/accounts/names/
   {ref}, or PATCH …/accounts/{label} for a login with no ref), Sign out (DELETE …/accounts/{label}), and a sign-in
   through api.js's SIGNIN steps (POST …/login). */
(() => {
  'use strict';
  const { $, h, fmt, money, pct, px, iconBtn } = Deck, DH = window.DESKTOP_HARNESSES;
  const READ_MS = 60000, HOUR = 3600000, DAY = 24 * HOUR;
  const AXIS_T = 0.8;                                 /* share of the centre given to time; the rest is "needs sign-in" */
  const enc = encodeURIComponent;
  const why = res => (res.status === 0 ? 'splice is not answering' : (res.body && (res.body.error || res.body.reason)) || 'splice answered ' + res.status);
  const STATE = Deck.STATES;
  const lamp = st => Deck.lamp(st);
  const clockOf = d => d.toLocaleTimeString('en-US', { hour: 'numeric', minute: '2-digit' }).replace(':00', '');
  const whenOf = d => d.toLocaleDateString('en-US', { weekday: 'short' }) + ' ' + clockOf(d);
  const stateWord = a => a.state === 'limit' ? (a.until ? 'back ' + whenOf(a.until) : 'at a limit') : STATE[a.state].word;
  const lim = (a, w) => (a.limits || []).find(l => l.window === w) || null;

  /* ---------- the live read: Claude's accounts in the page's shape ---------- */
  let rows = [], orderRead = null, sessions = [], keys = [], econ = [], readOk = null, head = null;
  let claude = { accounts: [], today: { requests: 0, dollars: 0, value: 0, hours: [] } };
  let byId = {}, order = [];
  /* the other providers' logins, by their place in the right rail (command/login), and the row each is drawn as */
  let railById = {};
  const railRefs = {};
  const acct = id => byId[id] || railById[id];
  const headOf = a => a.head || head;
  const adding = id => (id === 'add' ? { p: null } : typeof id === 'string' && id.startsWith('add:') ? { p: SPLICE.providers.find(x => x.key === id.slice(4)) || null } : null);
  const isClaude = r => r.provider === 'anthropic' || (SPLICE.providerOf((r.heads || [])[0]) || {}).family === 'anthropic';
  const labelOn = (r, hd) => (r.account_labels && r.account_labels[hd]) || r.label;
  function windows(r) {
    const out = [], at = s => (s ? new Date(s * 1000) : null);
    if (r.five_hour_used_percent != null) out.push({ window: '5 hours', used: r.five_hour_used_percent / 100, reset: at(r.five_hour_reset_epoch_seconds) });
    if (r.seven_day_used_percent != null) out.push({ window: 'Week', used: r.seven_day_used_percent / 100, reset: at(r.seven_day_reset_epoch_seconds) });
    for (const m of r.seven_day_models || []) out.push({ window: m.model, used: m.used_percent / 100, reset: at(m.resets_at) });
    return out;
  }
  function accountOf(r, serving, next) {
    const id = r.selector_key || r.label, limits = windows(r);
    const full = limits.filter(l => l.used >= 1 && l.reset && l.reset > new Date());
    const blocked = r.blocked_until_epoch_seconds ? new Date(r.blocked_until_epoch_seconds * 1000) : null;
    const signedOut = r.credential_present === false || (r.last_read_failure && r.last_read_failure.reason === 'signed_out');
    const until = full.length ? new Date(Math.max(...full.map(l => +l.reset))) : blocked && blocked > new Date() ? blocked : null;
    const state = signedOut ? 'out' : until || r.available === false ? 'limit' : id === serving ? 'serving' : id === next ? 'next' : 'ready';
    const on = sessions.filter(x => (r.heads || []).includes(x.head) && x.account === labelOn(r, x.head));
    const today = new Date(); today.setHours(0, 0, 0, 0);
    const moves = sessions.filter(x => x.account_switch && x.account_switch.to === labelOn(r, x.head) && (x.account_switch.at_epoch_millis || 0) >= +today)
      .map(x => [clockOf(new Date(x.account_switch.at_epoch_millis)), x.name || x.title || x.session_id.slice(0, 8), 'from ' + nameOfLabel(x.head, x.account_switch.from)]);
    return { id, row: r, name: nameOf(r), login: r.email || r.label, state, until, limits,
      sessions: on.length, sessionNames: on.map(x => x.name || x.title || x.session_id.slice(0, 8)), moves: moves.length ? moves : null };
  }
  /** a login as /api/accounts names it; logins nobody named, when there are two or more of them anywhere in the list, are
   *  told apart by one ordinal in the list's order (Account 1, Account 2), the same on every page */
  function nameOf(r) {
    const base = r.display_name || r.email || r.label;
    if (r.label_default !== true) return base;
    const unnamed = rows.filter(x => x.label_default === true);
    return unnamed.length > 1 ? base + ' ' + (unnamed.indexOf(r) + 1) : base;
  }
  function nameOfLabel(hd, label) {
    const r = rows.find(x => (x.heads || []).includes(hd) && labelOn(x, hd) === label);
    return r ? nameOf(r) : String(label || '').startsWith('native:') ? "Claude Code's own sign-in" : 'another login';
  }
  async function read() {
    const [ac, ss, ks, ec] = await Promise.all([API.get('/api/accounts'), API.get('/api/sessions'), API.get('/api/keys'), API.getReady('/api/economics'), DH.read()]);
    readOk = ac.ok;
    if (!ac.ok) { paintHead(); return; }
    rows = (ac.body && ac.body.accounts) || [];
    if (ss.ok) sessions = ((ss.body && ss.body.sessions) || []).filter(x => x.availability === 'live' && x.account && x.head);
    if (ks.ok) keys = (ks.body && ks.body.keys) || [];
    if (ec.ok) econ = (ec.body && ec.body.heads) || [];
    const mine = rows.filter(isClaude);
    head = mine.length ? (mine[0].heads || [])[0] : null;
    // a command with one login has no pool to order, and its order route refuses that with a 409
    orderRead = null;
    if (head && mine.length > 1) { const o = await API.get('/api/auth/' + enc(head) + '/order'); if (o.ok) orderRead = o.body; }
    // the order names logins by their label on this command; the page knows them by id
    const idOf = label => { const r = mine.find(x => labelOn(x, head) === label); return r ? r.selector_key || r.label : null; };
    const sel = mine.find(r => r.selected === true);
    const picked = orderRead && idOf(orderRead.next_target), after = orderRead && idOf(orderRead.following_target);
    const serving = sel ? sel.selector_key || sel.label : picked;
    const next = sel && picked !== serving ? picked : after;
    claude = { accounts: mine.map(r => accountOf(r, serving, next)), today: todayOf(heads => heads.some(k => (SPLICE.providerOf(k) || {}).family === 'anthropic')) };
    byId = Object.fromEntries(claude.accounts.map(a => [a.id, a]));
    // the lanes stand in the order splice takes them (effective_order); one it does not name yet comes after
    const ranked = ((orderRead && orderRead.effective_order) || []).map(idOf).filter(id => id && byId[id]);
    order = [...new Set(ranked), ...claude.accounts.map(a => a.id).filter(id => !ranked.includes(id))];
    // where splice names no login to serve, the one serving is the first in its order with room, as the old page read it
    if (!claude.accounts.some(a => a.state === 'serving')) {
      const first = order.map(id => byId[id]).find(a => a.state === 'ready' || a.state === 'next');
      if (first) first.state = 'serving';
    }
    if (!drag) rebuild();
  }
  /** today since this browser's midnight, by hour, on the commands [pick] chooses: requests, what they cost, their value */
  function todayOf(pick) {
    const from = new Date(); from.setHours(0, 0, 0, 0);
    const hours = new Array(24).fill(0);
    let requests = 0, dollars = 0, value = 0, notRead = false;
    for (const hd of econ) {
      if (!pick([hd.key])) continue;
      if ((hd.not_read_hours || []).some(t => t >= +from)) notRead = true;
      for (const b of hd.buckets || []) {
        if (b.hour < +from) continue;
        const i = Math.floor((b.hour - +from) / HOUR);
        if (i >= 0 && i < 24) hours[i] += b.turns || 0;
        requests += b.turns || 0; dollars += b.cost_usd || 0; value += b.plan_value_usd || 0;
      }
    }
    const max = Math.max(1, ...hours);
    return { requests, dollars, value, notRead, counts: hours, hours: hours.map(v => v / max), upTo: Math.floor((Date.now() - +from) / HOUR) + 1 };
  }

  /* ---------- time: the field is a time axis, from now to the end of the furthest day an account comes back ---------- */
  let AXIS_H = 96;
  function axis() {
    const now = Date.now(), backs = claude.accounts.filter(a => a.state === 'limit' && a.until).map(a => +a.until);
    const end = new Date(Math.max(now + 4 * DAY, ...backs)); end.setHours(23, 59, 0, 0);
    AXIS_H = (end - now) / HOUR;
  }
  const hoursUntil = d => (d - Date.now()) / HOUR;
  const tOfHours = hr => (hr / AXIS_H) * AXIS_T;
  const tOf = a => a.state === 'out' ? AXIS_T + 0.025 : a.state === 'limit' && a.until ? tOfHours(hoursUntil(a.until)) : 0;
  const nowHour = () => { const d = new Date(); return d.getHours() + d.getMinutes() / 60; };

  const refs = {};
  const field = $('#field'), centre = $('#centre'), lanesEl = $('#lanes'), marks = $('#marks');

  /* ---------- the field's marks: what the glass bends ---------- */
  function drawMarks() {
    marks.textContent = '';
    const f = field.getBoundingClientRect(), c = centre.getBoundingClientRect();
    const x0 = c.left - f.left, W = c.width, NOWH = nowHour();
    const pph = (AXIS_T * W) / AXIS_H;
    const xAt = hrs => x0 + hrs * pph;
    const add = (cls, x, text) => { const n = h('span', cls, text); n.style.setProperty('--x', x + 'px'); marks.append(n); return n; };
    const first = Math.ceil((-x0 / pph + NOWH) / 6) * 6 - NOWH, last = (f.width - x0) / pph;
    for (let hr = first; hr < last; hr += 6) {
      const clock = Math.round(NOWH + hr) % 24;
      const x = xAt(hr);
      if (hr > AXIS_H && x < x0 + W) continue;          /* the sign-in zone carries no time */
      add(clock === 0 ? 'mk day' : 'mk', x);
    }
    /* the past, left of now: today's requests by hour, drawn on the field under the rail's edge */
    const base = lanesEl.offsetTop + centre.offsetTop + lanesEl.offsetHeight;
    const done = claude.today.hours.slice(0, claude.today.upTo);
    done.forEach((v, i, all) => {
      const hr = i - all.length + (NOWH % 1), b = h('span', 'past');
      b.style.setProperty('--x', (xAt(hr) + 1) + 'px'); b.style.setProperty('--w', Math.max(1, pph - 2) + 'px');
      b.style.setProperty('--y', base + 'px'); b.style.setProperty('--v', v); marks.append(b);
    });
    add('mk now', x0);
    add('zone', x0 + AXIS_T * W);
    const lt = lanesEl.offsetTop + centre.offsetTop, lh = px('--lane-h');
    order.forEach((id, i) => { const n = h('span', 'laneline'); n.style.setProperty('--y', (lt + i * lh + lh / 2) + 'px'); marks.append(n); });
    /* the lines matter only where the accounts are read: fade them above and below, and soften them under the rails */
    const sc = $('#scale').offsetTop + centre.offsetTop;
    marks.style.setProperty('--fade-top', sc + 'px');
    marks.style.setProperty('--fade-bot', (lt + order.length * lh) + 'px');
    marks.style.setProperty('--c-left', x0 + 'px');
    marks.style.setProperty('--c-right', (x0 + W) + 'px');
  }
  function drawScale() {
    const s = $('#scale'); s.textContent = '';
    const put = (t, text, cls) => { const n = h('span', cls || '', text); n.style.setProperty('--t', t); s.append(n); };
    put(0, 'now');
    const day = new Date(); day.setHours(24, 0, 0, 0);
    for (; hoursUntil(day) < AXIS_H; day.setDate(day.getDate() + 1)) put(tOfHours(hoursUntil(day)), day.toLocaleDateString('en-US', { weekday: 'short' }));
    put(AXIS_T, 'needs sign-in', 'zone-label');
  }

  /* ---------- lanes ---------- */
  function figure(label, l, pc) {
    const f = h('div', 'fig' + (l.used >= 1 ? ' full' : ''));
    const row = h('div', 'row'), m = h('span', 'meter'), i = h('i');
    row.append(h('span', 'lbl', label));
    i.style.setProperty('--u', Math.min(1, l.used)); if (pc) m.style.setProperty('--pc', pc);
    m.append(i); row.append(m, h('span', 'pct', pct(l.used)));
    f.append(row);
    return { el: f, fill: i, pct: row.lastChild };
  }
  function buildLane(a) {
    const lane = h('div', 'lane'); lane.dataset.id = a.id; lane.tabIndex = 0; lane.setAttribute('role', 'listitem');
    lane.setAttribute('aria-label', a.name + ', ' + stateWord(a));
    const mark = h('div', 'mark'); mark.style.setProperty('--t', tOf(a));
    const lp = lamp(a.state), who = h('div', 'who'), nm = h('span', 'nm', a.name), st = h('span', 'st', stateWord(a));
    st.style.setProperty('--c', STATE[a.state].c);
    who.append(nm, st);
    mark.append(lp, who);
    const r = { lane, mark, nm, st, lamp: lp };
    if (a.state === 'out') {
      const b = h('button', 'btn press', 'Sign in'); b.dataset.act = 'signin';
      mark.append(b);
    } else {
      const figs = h('div', 'figs');
      const w = lim(a, 'Week'), model = a.limits.find(l => l.window !== 'Week' && l.window !== '5 hours');
      if (w) { r.week = figure('Week', w); figs.append(r.week.el); }
      if (model) { r.model = figure(model.window, model); figs.append(r.model.el); }
      mark.append(figs);
    }
    const tools = h('div', 'lane-tools');
    /* what a change on this lane did: Saving, then what splice answered, in the kit's one place for it */
    r.sv = Deck.saveState({ rest: () => '' }); r.sv.classList.add('lane-sv');
    const told = TOLD.get(a.id);
    if (told && (!told.until || told.until > Date.now())) { r.sv.set(told.state, told.word); lane.classList.add('is-saving'); }
    tools.append(r.sv);
    if (a.state === 'next' || a.state === 'ready') { const u = h('button', 'btn press', 'Use now'); u.dataset.act = 'use'; tools.append(u); }
    const more = iconBtn('more', 'More for ' + a.name); more.dataset.act = 'menu';
    const grip = iconBtn('grip', 'Drag to reorder'); grip.classList.add('grip'); grip.dataset.act = 'grip';
    tools.append(more, grip);
    lane.append(mark, tools);
    refs[a.id] = r;
    return lane;
  }
  function placeFlips() {
    const W = centre.clientWidth;
    for (const id of order) {
      const r = refs[id]; if (!r) continue; r.mark.classList.remove('flip');
      const x = tOf(byId[id]) * W, w = r.mark.getBoundingClientRect().width;
      if (x + w > W) r.mark.classList.add('flip');
    }
  }
  function renderLanes() {
    lanesEl.textContent = '';
    order.forEach(id => lanesEl.append(buildLane(byId[id])));
  }
  /** a change on a lane says what splice answered: Saving, then Applied, or Not saved with why */
  // what splice last answered for each lane, kept across the redraws a read makes until it has been read
  const TOLD = new Map();
  async function said(id, send) {
    const r = refs[id];
    TOLD.set(id, { state: 'saving' });
    if (r) { r.lane.classList.add('is-saving'); r.sv.set('saving'); }
    const res = await send();
    const ms = px('--dur') * (res.ok ? 8 : 24);
    TOLD.set(id, res.ok ? { state: 'applied', until: Date.now() + ms } : { state: 'refused', word: why(res), until: Date.now() + ms });
    const now = refs[id] || r;
    if (now && now.sv) {
      if (res.ok) now.sv.set('applied'); else now.sv.set('refused', why(res));
      setTimeout(() => { const l = refs[id]; l && l.lane.classList.remove('is-saving'); }, ms);
    } else if (railById[id]) {
      // a login in the right rail has no lane: the composer's line says what splice answered
      composer.note(res.ok ? 'idle' : 'stalled', res.ok ? 'Applied to ' + railById[id].name : 'Not saved: ' + why(res), ms);
    }
    return res;
  }

  /* ---------- left rail ---------- */
  const left = {};
  function renderServing() {
    const a = claude.accounts.find(x => x.state === 'serving'), s = $('#serving');
    s.textContent = '';
    s.append(h('h2', 'eyebrow', 'Serving now'));
    if (!a) { s.append(h('p', 'faint small', readOk === false ? 'splice is not answering' : 'No Claude account is serving.')); return; }
    const lede = h('div', 'lede'); lede.append(lamp('serving'), h('span', '', a.name)); s.append(lede);
    s.append(h('div', 'mono small quiet', a.login));
    left.lims = {};
    for (const l of a.limits) {
      const box = h('div', 'lim' + (l.used >= 1 ? ' full' : ''));
      const top = h('div', 'top'); top.append(h('span', '', l.window + (l.reset ? ' · resets ' + whenOf(l.reset) : '')), h('span', 'mono', pct(l.used)));
      const m = h('span', 'meter'), i = h('i'); i.style.setProperty('--u', Math.min(1, l.used)); m.append(i);
      box.append(top, m); s.append(box);
      left.lims[l.window] = { box, pct: top.lastChild, fill: i };
    }
    const kv = h('div', 'kv'); kv.append(h('span', 'quiet', 'Sessions on it'), h('span', 'mono', String(a.sessions)));
    s.append(kv);
  }
  function renderToday() {
    const s = $('#today'), t = claude.today, n = claude.accounts.length;
    s.textContent = '';
    s.append(h('h2', 'eyebrow', 'Today on Claude'));
    left.req = h('div', 'big', econ.length && !t.notRead ? fmt(t.requests) : '—');
    left.spend = h('div', 'mono small quiet', !econ.length ? 'Today’s usage is still loading' : t.notRead ? 'Part of today’s history could not be read' : money(t.dollars) + ' spent' + (t.value ? ' · ' + money(t.value) + ' at API prices' : ''));
    const bars = h('div', 'bars'); bars.setAttribute('aria-label', 'Requests by hour');
    left.bars = [];
    for (let i = 0; i < 24; i++) { const b = h('i'); b.style.setProperty('--v', t.hours[i] ?? 0); bars.append(b); left.bars.push(b); }
    const atLim = claude.accounts.filter(a => a.state === 'limit').length, out = claude.accounts.filter(a => a.state === 'out').length;
    const k1 = h('div', 'kv'); k1.append(h('span', 'quiet', 'Logins at a limit'), h('span', 'mono', atLim + ' of ' + n));
    const k2 = h('div', 'kv'); k2.append(h('span', 'quiet', 'Signed out'), h('span', 'mono', out + ' of ' + n));
    s.append(left.req, left.spend, bars, h('div'), k1, k2);
  }
  function renderBack() {
    const ul = $('#backList'); ul.textContent = '';
    const rank = a => a.state === 'serving' ? -2 : a.state === 'next' ? -1 : a.state === 'ready' ? 0 : a.state === 'limit' ? (a.until ? hoursUntil(a.until) : 1e8) : 1e9;
    [...claude.accounts].sort((x, y) => rank(x) - rank(y)).forEach(a => {
      const li = h('li'); li.dataset.id = a.id; li.tabIndex = 0;
      li.append(lamp(a.state), h('span', '', a.name), h('span', 'when', a.state === 'limit' && a.until ? whenOf(a.until) : STATE[a.state].word));
      ul.append(li);
    });
  }

  /* ---------- right rail: other providers ---------- */
  function renderProviders() {
    const rail = $('.rail-r'); rail.textContent = '';
    railById = {}; for (const k of Object.keys(railRefs)) delete railRefs[k];
    const others = SPLICE.providers.filter(p => p.family !== 'anthropic');
    const agy = antigravity();
    if (!others.length && !agy) { const s = h('section', 'sec'); s.append(h('p', 'faint small', 'No other provider.')); rail.append(s); return; }
    for (const p of others) {
      const s = h('section', 'sec prov'); s.style.setProperty('--pc', p.color);
      const hd = h('div', 'hd'); hd.append(h('span', 'lamp'), h('b', '', p.name), h('span', 'cmd', p.command));
      s.append(hd);
      const t = todayOf(ks => ks.some(k => SPLICE.providerOf(k) === p));
      s.append(h('div', 'mono small quiet', econ.length && !t.notRead ? Deck.count(t.requests, 'request') + ' today' + (t.dollars ? ' · ' + money(t.dollars) : '') : !econ.length ? 'Today’s usage is still loading' : 'Part of today’s history could not be read'));
      const mine = rows.filter(r => (r.heads || []).includes(p.key));
      const sel = mine.length > 1 ? mine.find(r => r.selected === true) : null;
      for (const r of mine) {
        const a = accountOf(r, sel ? sel.selector_key || sel.label : null, null), row = h('div', 'acct');
        // a login here is one of this command's: its glass and its acts speak to that command, never to Claude's
        a.id = p.key + '/' + a.id; a.head = p.key; a.pool = mine.length; a.of = p;
        railById[a.id] = a;
        row.dataset.id = a.id; row.tabIndex = 0; row.setAttribute('role', 'button'); row.setAttribute('aria-label', 'Inspect ' + a.name);
        const word = a.state === 'limit' && a.until ? 'back ' + whenOf(a.until) : a.state === 'out' ? 'needs sign-in' : a.state === 'serving' ? 'serving' : 'ready';
        const st = h('span', 'st', word); st.style.setProperty('--c', STATE[a.state === 'ready' || a.state === 'next' ? 'ready' : a.state].c);
        const who = h('span', '', a.name + (r.plan ? ' · ' + r.plan : '')); who.title = who.textContent; row.append(who, st);
        railRefs[a.id] = { row, nm: who, plan: r.plan ? ' · ' + r.plan : '' };
        if (openId === a.id) row.classList.add('is-open');
        s.append(row);
        if (!a.limits.length) s.append(h('div', 'small faint', 'No limits reported'));
        for (const l of a.limits) {
          const box = h('div', 'lim' + (l.used >= 1 ? ' full' : ''));
          const top = h('div', 'top'); top.append(h('span', 'small quiet', l.window + (l.reset ? ' · ' + whenOf(l.reset) : '')), h('span', 'mono', pct(l.used)));
          const m = h('span', 'meter'), i = h('i'); i.style.setProperty('--u', Math.min(1, l.used)); m.append(i);
          box.append(top, m); s.append(box);
        }
      }
      for (const k of keys.filter(x => (x.heads || []).includes(p.key) || x.head === p.key)) {
        const row = h('div', 'acct'), st = h('span', 'st', k.source === 'missing' ? 'no key' : 'key set');
        st.style.setProperty('--c', STATE[k.source === 'missing' ? 'out' : 'ready'].c);
        row.append(h('span', '', 'API key · ' + k.name), st);
        s.append(row);
      }
      const dh = DH.of(p.key, p.family);
      if (dh) s.append(harnessRow(dh, dh.family));
      // a provider that signs in with a login takes another one here; a key is added on Providers
      if (p.auth && !/key/i.test(p.auth) && p.auth !== 'client') {
        const add = h('button', 'btn quiet press rail-add', 'Add login'); add.type = 'button'; add.dataset.add = p.key;
        add.setAttribute('aria-label', 'Add a ' + p.name + ' login to ' + p.command);
        railRefs['add:' + p.key] = { row: add };
        s.append(add);
      }
      rail.append(s);
    }
    if (agy) rail.append(agy);
  }

  /* ---------- a provider's Desktop harness (deck-desktop-harnesses.js) ----------
     Each provider says which program its Claude Desktop sessions run on: Claude Code through Splice, Codex CLI where the
     person opted GPT into it, or agy for Antigravity. GPT's alternative and Antigravity's switch are one press here; the
     rest is in Settings. While a change waits for splice's answer every harness button waits with it. */
  let harnessSaving = false;
  function harnessRow(dh, fam) {
    const box = h('div', 'harness');
    const row = h('div', 'acct'), st = h('span', 'st', dh.enabled ? dh.name : 'Off');
    st.style.setProperty('--c', dh.enabled ? 'var(--ink-quiet)' : STATE.out.c);
    row.append(h('span', 'quiet small', 'Desktop sessions'), st);
    box.append(row);
    const acts = h('div', 'harness-acts');
    if (fam && fam.toggleable) acts.append(harnessBtn(fam.family, { enabled: !fam.enabled }, fam.enabled ? 'Turn off' : 'Turn on', fam.title));
    else if (fam && fam.choices.length > 1) {
      const other = fam.choices.find(id => id !== fam.harness);
      acts.append(harnessBtn(fam.family, { harness: other }, 'Use ' + DH.name(other), fam.title));
    }
    const go = h('a', 'quiet small', 'Desktop harnesses'); go.href = 'settings.html#harnesses';
    acts.append(go);
    box.append(acts);
    const said = fam && DH.error(fam.family);
    if (said) box.append(h('div', 'small harness-why', said));
    else if (fam && fam.toggleable && DH.data && DH.data.restart_required) box.append(h('div', 'small faint', 'Restart splice to apply, then reopen Claude Desktop'));
    return box;
  }
  function harnessBtn(family, change, word, title) {
    const b = h('button', 'btn quiet press', harnessSaving ? 'Saving…' : word); b.type = 'button';
    b.dataset.harness = family; b.dataset.change = JSON.stringify(change);
    b.setAttribute('aria-label', word + ' · ' + title + ' in Claude Desktop');
    b.disabled = harnessSaving || DH.busy();
    return b;
  }
  /** Antigravity's own section: a provider only Claude Desktop runs, drawn while agy serves it or while it is off */
  function antigravity() {
    const fam = DH.family('antigravity');
    if (!fam || !(fam.configured === true || !fam.enabled)) return null;
    const s = h('section', 'sec prov'); s.style.setProperty('--pc', 'var(--ink-faint)');
    const hd = h('div', 'hd'); hd.append(h('span', 'lamp'), h('b', '', fam.title), h('span', 'cmd', 'Desktop only'));
    s.append(hd, harnessRow({ harness: fam.harness, name: DH.name(fam.harness), enabled: fam.enabled }, fam));
    return s;
  }
  async function pressHarness(b) {
    if (harnessSaving || DH.busy()) return;
    harnessSaving = true; rebuild();
    try { await DH.save({ [b.dataset.harness]: JSON.parse(b.dataset.change) }); } finally { harnessSaving = false; rebuild(); }
  }

  /* ---------- inspection lens: the kit's glass, placed beside the account it opened from ---------- */
  const lens = Deck.glass('lens', field); lens.setAttribute('aria-label', 'Account'); lens.setAttribute('role', 'dialog');
  let openId = null, lensOff = null;
  const signs = new Map();             /* the sign-in each account is in, as api.js's SIGNIN holds it */
  /* ---------- a Claude login's limit resets ----------
     Anthropic gives some plans a few resets to spend, and splice lists and spends them (GET …/resets, POST …/reset).
     The glass reads a login's resets from the provider when it opens and again after RESET_REASK_MS, because that read
     is rate limited. A spend cannot be taken back, so it takes two presses, and the second says what it clears. */
  const RESET_REASK_MS = 10 * 60000, RESET_RETRY_MS = 60000;
  const resetsOf = new Map();          /* account id -> { state, login, why, arm, spending, said, again } */
  const hasResets = a => byId[a.id] === a && a.state !== 'out';
  async function readResets(a, said, force) {
    const seen = resetsOf.get(a.id);
    if (seen && (seen.state === 'reading' || seen.spending || (!force && Date.now() < seen.again))) return;
    resetsOf.set(a.id, { state: 'reading' });
    const res = await API.get('/api/auth/' + enc(head) + '/resets?label=' + enc(labelOn(a.row, head)));
    // a daemon that answers and lists nothing for this login has no resets to show for it
    const login = res.ok ? (res.body && (res.body.logins || [])[0]) || { kinds: [] } : null, now = Date.now();
    resetsOf.set(a.id, login ? { state: 'read', login, said, again: now + (login.refusal ? RESET_RETRY_MS : RESET_REASK_MS) }
      : { state: 'failed', why: why(res), said, again: now + RESET_RETRY_MS });
    if (openId === a.id) refill();
  }
  function resetsPart(a) {
    const r = resetsOf.get(a.id), part = h('div', 'resets');
    part.append(h('div', 'eyebrow', 'Limit resets'));
    if (!r || r.state === 'reading') { part.append(h('div', 'mono small faint', 'Reading')); return part; }
    if (r.said) part.append(h('div', r.said.ok ? 'rs-said' : 'lr-why', r.said.text));
    if (r.state === 'failed') { part.append(h('div', 'lr-why', 'Not read: ' + r.why)); return part; }
    const kinds = r.login.kinds || [];
    if (!kinds.length) part.append(h('div', 'mono small faint', r.login.refusal || r.login.not_offered || 'None on this login'));
    for (const k of kinds) {
      // splice names a kind "Fable reset", "weekly reset", "full reset" or "5-hour reset" (ResetKinds.name)
      const row = h('div', 'rs');
      row.append(h('span', 'rs-nm', k.name.charAt(0).toUpperCase() + k.name.slice(1)), h('span', 'rs-left', k.left > 0 ? k.left + ' of ' + k.total + ' left' : 'None left'));
      const can = k.left > 0 && k.usable_now, spending = r.spending === k.kind, asking = can && r.arm === k.kind && !spending;
      if (can && !asking) {
        const b = h('button', 'btn press', spending ? 'Resetting' : 'Use one'); b.type = 'button'; b.dataset.act = 'reset-arm'; b.dataset.k = k.kind;
        b.disabled = !!r.spending; row.append(b);
      } else if (k.left > 0 && !can) row.append(h('div', 'rs-why', k.not_now || 'Not usable now'));
      if (asking) {
        const ask = h('div', 'lr-ask'), limit = k.kind === 'full' ? 'every limit' : 'the ' + k.name.replace(/ reset$/i, '') + ' limit';
        ask.append(h('span', '', 'Clears ' + limit + ' now, ' + (k.left - 1) + ' left after. It cannot be undone.'));
        const no = h('button', 'btn quiet press', 'Cancel'), yes = h('button', 'btn primary press', 'Reset now');
        no.type = yes.type = 'button'; no.dataset.act = 'reset-keep'; yes.dataset.act = 'reset-use'; yes.dataset.k = k.kind;
        ask.append(no, yes); row.append(ask);
      }
      part.append(row);
    }
    return part;
  }
  async function useReset(id, kind) {
    const a = acct(id), r = resetsOf.get(id);
    if (!a || !r || r.state !== 'read' || r.spending || r.arm !== kind) return;
    r.spending = kind; refill();
    const res = await API.post('/api/auth/' + enc(head) + '/reset', { label: labelOn(a.row, head), kind });
    const text = res.body && typeof res.body.said === 'string' && res.body.said ? res.body.said : res.ok ? 'Reset' : 'Not reset: ' + why(res);
    r.spending = null;
    await readResets(acct(id) || a, { text, ok: res.ok }, true);
    if (res.ok) read();
  }

  function fillLens(a) {
    lens.textContent = '';
    const hd = h('div', 'hd'), title = h('div');
    title.append(h('h3', '', a.name));
    const st = h('div', 'mono small', stateWord(a)); st.style.setProperty('color', STATE[a.state].c); title.append(st);
    const x = iconBtn('close', 'Close'); x.dataset.act = 'close';
    hd.append(lamp(a.state), title, x);
    lens.append(hd, h('div', 'login', a.login));
    // while splice signs the login in, its glass holds the sign-in's steps and nothing to press twice: the windows and
    // sessions of a login that is signed out wait until it is back
    const s = signs.get(a.id), signing = !!s && s.state !== 'left' && s.state !== 'done';
    if (signing) {
      lens.append(Deck.signRows(s));
      const acts = h('div', 'acts');
      const mk = (t, act) => { const b = h('button', 'btn press', t); b.dataset.act = act; return b; };
      acts.append(mk('Rename', 'rename'), mk('Copy login', 'copy'), mk('Sign out', 'signout'));
      lens.append(acts);
      return;
    }
    if (a.limits.length) {
      const two = h('div', 'two');
      for (const l of a.limits) {
        const f = h('div', 'fig2' + (l.used >= 1 ? ' full' : ''));
        f.append(h('div', 'eyebrow', l.window), h('div', 'v', pct(l.used)));
        const m = h('span', 'meter'), i = h('i'); i.style.setProperty('--u', Math.min(1, l.used)); m.append(i); m.classList.add('full-w');
        f.append(m, h('div', 'mono small faint', l.reset ? 'resets ' + whenOf(l.reset) : 'reset not reported'));
        two.append(f);
      }
      lens.append(two);
    }
    if (hasResets(a)) lens.append(resetsPart(a));
    const ses = h('div'); ses.append(h('div', 'eyebrow', 'Sessions on it · ' + a.sessions));
    const ul = h('ul');
    a.sessionNames.slice(0, 5).forEach(n => { const li = h('li'); li.append(h('span', '', n)); ul.append(li); });
    if (!a.sessions) ul.append(h('li', '', 'None running'));
    else if (a.sessionNames.length > 5) { const li = h('li'); li.append(h('span', 'faint', '+ ' + (a.sessionNames.length - 5) + ' more')); ul.append(li); }
    ses.append(ul); lens.append(ses);
    if (a.moves) {
      const mv = h('div'); mv.append(h('div', 'eyebrow', 'Moved onto it today'));
      const ul2 = h('ul');
      a.moves.forEach(m => { const li = h('li'); li.append(h('span', '', m[1] + ' ' + m[2]), h('span', 'faint', m[0])); ul2.append(li); });
      mv.append(ul2); lens.append(mv);
    }
    const acts = h('div', 'acts');
    const mk = (t, act, cls) => { const b = h('button', 'btn press' + (cls ? ' ' + cls : ''), t); b.dataset.act = act; return b; };
    if (a.state === 'out') acts.append(mk('Sign in', 'signin', 'primary'));
    // a command with one login has nothing to switch to: Use now is for a pool of two or more
    else if ((a.state === 'ready' || a.state === 'next') && (!a.head || a.pool > 1)) acts.append(mk('Use now', 'use', 'primary'));
    acts.append(mk('Rename', 'rename'), mk('Copy login', 'copy'));
    // every login can be signed out on one press, one that needs a sign-in included: removing it asks for none
    acts.append(mk('Sign out', 'signout', 'quiet'));
    lens.append(acts);
  }
  function openLens(id) {
    const a = acct(id); if (!a) return;
    unmark();
    openId = id; lens.dataset.id = id;
    mark(id);
    if (hasResets(a)) readResets(a);
    fillLens(a);
    lens.hidden = false;
    Deck.show(lens, placeLens());
    lensOff && lensOff();
    lensOff = Deck.layer(lens, closeLens, [a.head ? $('.rail-r') : lanesEl]);
  }
  /** the open glass's anchor: its account's mark, its row in the right rail, or the Add it opened from */
  const anchorOf = () => (openId === 'add' ? addBtn : refs[openId] ? refs[openId].mark : railRefs[openId] && railRefs[openId].row);
  /** the lane or the rail row the glass is open for says so, and stops saying so */
  const marked = id => (refs[id] ? refs[id].lane : railRefs[id] && railRefs[id].row);
  function mark(id) { const el = marked(id); el && el.classList.add('is-open'); }
  function unmark() { const el = openId && marked(openId); el && el.classList.remove('is-open'); }
  /** places the glass by what it opened from, and answers the point it grows out of; called again when its content
   *  changes size (a sign-in's steps), so it never runs under the composer */
  function placeLens() {
    const an = anchorOf(); if (!an) return [0, 0];
    // the glass bends what lies behind it, so it stays over the time field: never above the day scale, where the
    // heading's words would bend into its rim. A glass with too little room below the scale first scrolls the page
    // up (as the approved page does), and only past that scrolls inside itself.
    const bottom = innerHeight - px('--comp-clear');
    const floor = () => Math.max(px('--top-clear'), $('#scale').getBoundingClientRect().top);
    lens.style.setProperty('--lens-room', (bottom - px('--top-clear')) + 'px');
    const need = lens.offsetHeight - (bottom - floor());
    if (need > 0) scrollBy({ top: Math.min(need, Math.max(0, document.documentElement.scrollHeight - innerHeight - scrollY)), behavior: 'instant' });
    lens.style.setProperty('--lens-room', (bottom - floor()) + 'px');
    const f = field.getBoundingClientRect(), c = centre.getBoundingClientRect(), m = an.getBoundingClientRect();
    const lw = lens.offsetWidth, lh = lens.offsetHeight, gap = px('--gap');
    // beside the account where the centre has room, else above it, else under it; always whole on the screen, between
    // the day scale and the composer
    const right = m.right + gap, left = m.left - gap - lw, top = floor();
    const inView = v => Math.max(top, Math.min(v, bottom - lh));
    let x, y, from;
    if (right + lw <= c.right || left >= c.left) {
      x = right + lw <= c.right ? right : left;
      y = inView(m.top + m.height / 2 - lh / 2);
      from = [x > m.left ? m.right - x : m.left - x, m.top + m.height / 2 - y];
    } else {
      x = Math.min(c.right - lw, Math.max(c.left, m.left));
      y = m.top - gap - lh >= top ? m.top - gap - lh : m.bottom + gap + lh <= bottom ? m.bottom + gap : inView(m.top - gap - lh);
      from = [m.left + m.width / 2 - x, (y > m.top ? m.bottom : m.top) - y];
    }
    lens.style.setProperty('left', (x - f.left) + 'px'); lens.style.setProperty('top', (y - f.top) + 'px');
    return from;
  }
  function closeLens() {
    if (!openId) return;
    unmark();
    const id = openId; openId = null;
    if (signs.get(id)?.state === 'done') signs.delete(id);
    lensOff && lensOff(); lensOff = null;
    Deck.hide(lens).then(() => { const el = marked(id); el && el.isConnected && el.focus({ preventScroll: true }); });
  }

  /** starts splice's sign-in for [a] (a new login when [a] is null: Claude's, or [p]'s when a provider is named), held
   *  in the account's glass while it runs */
  function signIn(a, p) {
    const account = a || null;
    const key = a ? a.id : p ? 'add:' + p.key : 'add';
    const s = signs.get(key) || {};
    const hd = a ? headOf(a) : p ? p.key : head;
    s.how = { start: '/api/auth/' + enc(hd || (SPLICE.providers.find(x => x.family === 'anthropic') || {}).key || 'claude') + '/login', body: a ? { label: labelOn(a.row, hd) } : {}, poll: hd };
    s.on = {
      held: () => signs.get(key) === s,
      draw: () => { if (openId === key) refill(); },
      // an added login that came back through a restart closes the Add glass onto its row;
      // a sign-in that landed directly keeps "Signed in as <label>" visible without a reload
      done: () => {
        if (!account && s.back) {
          signs.delete(key);
          read().then(() => (openId === key ? closeLens() : refill()));
          return;
        }
        read().then(() => refill());
      },
      failed: () => refill(),
    };
    signs.set(key, s);
    SIGNIN.start(s, s.on);
  }
  function refill() { if (openId && acct(openId)) fillLens(acct(openId)); else if (adding(openId)) fillAdd(adding(openId).p); else return; placeLens(); }
  /** the field's add part: a new login, Claude's or [p]'s, signed in the same way */
  function fillAdd(p) {
    lens.textContent = '';
    const hd = h('div', 'hd'), title = h('div'), x = iconBtn('close', 'Close'); x.dataset.act = 'close';
    title.append(h('h3', '', p ? 'Add login' : 'Add account'), h('div', 'mono small quiet', p ? 'A ' + p.name + ' login for ' + p.command : 'A Claude login splice can move to'));
    hd.append(lamp('out'), title, x); lens.append(hd);
    const s = signs.get(p ? 'add:' + p.key : 'add');
    if (s && s.state !== 'left') lens.append(Deck.signRows(s));
    else { const acts = h('div', 'acts'), b = h('button', 'btn press primary', 'Sign in'); b.dataset.act = 'signin'; acts.append(b); lens.append(acts); }
  }
  function openAdd(p) {
    unmark();
    openId = p ? 'add:' + p.key : 'add'; lens.dataset.id = openId;
    fillAdd(p); lens.hidden = false;
    Deck.show(lens, placeLens());
    lensOff && lensOff();
    lensOff = Deck.layer(lens, closeLens, [p ? $('.rail-r') : addBtn]);
  }


  /* ---------- actions, each written to splice and said on its lane ---------- */
  async function setServing(id) {
    const a = acct(id); if (!a || a.state === 'serving' || a.state === 'limit' || a.state === 'out') return;
    await said(id, () => API.post('/api/auth/' + enc(headOf(a)) + '/switch', { label: labelOn(a.row, headOf(a)) }));
    read();
  }
  async function saveOrder(id, before) {
    const res = await said(id, () => API.put('/api/auth/' + enc(head) + '/order', { order: order.map(x => labelOn(byId[x].row, head)) }));
    if (!res.ok) { order = before; renderLanes(); placeFlips(); drawMarks(); }
    read();
  }
  function moveTop(id) { const before = order.slice(); order = [id, ...order.filter(x => x !== id)]; rebuild(); saveOrder(id, before); }
  function rename(id) {
    const r = refs[id] || railRefs[id]; if (!r) return;
    // a rail row shows the login's plan after its name: the name alone is what is typed over
    if (r.plan != null) r.nm.textContent = acct(id).name;
    r.nm.contentEditable = 'true'; r.nm.focus();
    document.getSelection().selectAllChildren(r.nm);
    const done = e => {
      if (e.type === 'keydown' && e.key !== 'Enter' && e.key !== 'Escape') return;
      if (e.type === 'keydown') { e.preventDefault(); e.stopPropagation(); }
      r.nm.removeEventListener('keydown', done); r.nm.removeEventListener('blur', done);
      r.nm.contentEditable = 'false';
      const a = acct(id), v = r.nm.textContent.trim();
      if (!a) return;
      if (e.type === 'keydown' && e.key === 'Escape' || !v || v === a.name) { r.nm.textContent = a.name + (r.plan || ''); return; }
      // a login splice knows by its ref is named against that ref; one with none is renamed where it is kept
      const t = a.row.edit_target, path = '/api/auth/' + enc(a.head || (a.row.heads || [])[0] || head) + '/accounts/' + enc(t ? t.id : a.row.label)
        + (t ? '?target_kind=' + enc(t.kind) + '&target_id=' + enc(t.id) : '');
      said(id, () => (a.row.ref ? API.put('/api/accounts/names/' + enc(a.row.ref), { name: v }) : API.patch(path, { label: v }))).then(res => {
        if (!res.ok) r.nm.textContent = a.name + (r.plan || '');
        read();
      });
    };
    r.nm.addEventListener('keydown', done); r.nm.addEventListener('blur', done);
  }
  async function signOut(id) {
    const a = acct(id), t = a.row.edit_target;
    const path = '/api/auth/' + enc(a.head || (a.row.heads || [])[0] || head) + '/accounts/' + enc(t ? t.id : a.row.label) + (t ? '?target_kind=' + enc(t.kind) + '&target_id=' + enc(t.id) : '');
    const res = await said(id, () => API.del(path));
    if (res.ok) closeLens();
    read();
  }
  const copyLogin = id => navigator.clipboard && navigator.clipboard.writeText(acct(id).login).then(() => composer.note('idle', 'Copied ' + acct(id).login, 2500), () => composer.note('stalled', 'Could not copy here', 2500));
  function laneMenu(btn, id) {
    const a = byId[id];
    const items = [];
    if (a.state === 'out') items.push({ label: 'Sign in', run: () => { openLens(id); signIn(a); } });
    else if (a.state !== 'serving' && a.state !== 'limit') items.push({ label: 'Use now', run: () => setServing(id) });
    items.push({ label: 'Inspect', run: () => openLens(id) }, { label: 'Rename', run: () => rename(id) }, { label: 'Move to the top', run: () => moveTop(id) },
      { label: 'Copy login', sub: a.login, run: () => copyLogin(id) }, '-',
      { label: 'Sign out', danger: true, run: () => { openLens(id); signOut(id); } });
    Deck.openMenu(btn, items);
  }

  /* ---------- drag to reorder (a lane moves along the vertical, never off its time) ---------- */
  let drag = null;
  lanesEl.addEventListener('pointerdown', e => {
    const g = e.target.closest('[data-act="grip"]'); if (!g || e.button !== 0) return;
    const lane = g.closest('.lane'); e.preventDefault();
    closeLens(); Deck.closeMenu();
    drag = { lane, id: lane.dataset.id, y0: e.clientY, from: order.indexOf(lane.dataset.id), to: order.indexOf(lane.dataset.id), lh: lane.offsetHeight };
    lane.classList.add('is-dragging'); g.setPointerCapture(e.pointerId);
  });
  lanesEl.addEventListener('pointermove', e => {
    if (!drag) return;
    const dy = e.clientY - drag.y0;
    drag.lane.style.setProperty('transform', 'translateY(' + dy + 'px)');
    drag.to = Math.max(0, Math.min(order.length - 1, drag.from + Math.round(dy / drag.lh)));
    order.forEach((id, i) => {
      if (id === drag.id) return;
      let s = 0;
      if (drag.from < drag.to && i > drag.from && i <= drag.to) s = -1;
      if (drag.from > drag.to && i < drag.from && i >= drag.to) s = 1;
      refs[id].lane.style.setProperty('transform', s ? 'translateY(' + s * drag.lh + 'px)' : '');
    });
  });
  const endDrag = () => {
    if (!drag) return;
    const { id, to, from } = drag, before = order.slice();
    drag.lane.classList.remove('is-dragging');
    order = order.filter(x => x !== id); order.splice(to, 0, id);
    for (const k of order) { refs[k].lane.style.setProperty('transition', 'none'); refs[k].lane.style.removeProperty('transform'); }
    order.forEach(k => lanesEl.append(refs[k].lane));
    void lanesEl.offsetWidth;
    for (const k of order) refs[k].lane.style.removeProperty('transition');
    drag = null;
    refs[id].lane.focus({ preventScroll: true });
    if (to !== from) saveOrder(id, before);
  };
  lanesEl.addEventListener('pointerup', endDrag);
  lanesEl.addEventListener('pointercancel', endDrag);

  /* ---------- events ---------- */
  lanesEl.addEventListener('click', e => {
    const lane = e.target.closest('.lane'); if (!lane) return;
    const id = lane.dataset.id, act = e.target.closest('[data-act]');
    if (act) {
      const k = act.dataset.act;
      if (k === 'menu') return laneMenu(act, id);
      if (k === 'use') return setServing(id);
      if (k === 'signin') { openLens(id); return signIn(byId[id]); }
      if (k === 'grip') return;
    }
    if (refs[id].nm.isContentEditable) return;
    if (e.target.closest('.mark')) openId === id ? closeLens() : openLens(id);
  });
  lanesEl.addEventListener('keydown', e => {
    const lane = e.target.closest('.lane'); if (!lane || e.target !== lane) return;
    const id = lane.dataset.id, i = order.indexOf(id);
    if (e.key === 'Enter' || e.key === ' ') { e.preventDefault(); openLens(id); }
    if (e.altKey && (e.key === 'ArrowUp' || e.key === 'ArrowDown')) {
      e.preventDefault(); const j = Math.max(0, Math.min(order.length - 1, i + (e.key === 'ArrowUp' ? -1 : 1)));
      const before = order.slice();
      order.splice(i, 1); order.splice(j, 0, id); order.forEach(k => lanesEl.append(refs[k].lane)); lane.focus();
      if (j !== i) saveOrder(id, before);
    } else if (e.key === 'ArrowDown' || e.key === 'ArrowUp') {
      e.preventDefault(); const n = refs[order[i + (e.key === 'ArrowUp' ? -1 : 1)]]; n && n.lane.focus();
    }
  });
  lens.addEventListener('click', e => {
    const act = e.target.closest('[data-act]'); if (!act) return;
    const k = act.dataset.act, id = lens.dataset.id;
    if (k === 'close') closeLens();
    if (k === 'use') { closeLens(); setServing(id); }
    if (k === 'rename') rename(id);
    if (k === 'copy') copyLogin(id);
    if (k === 'signout') signOut(id);
    if (k === 'signin') { const add = adding(id); add ? signIn(null, add.p) : signIn(acct(id)); }
    if (k === 'reset-arm' || k === 'reset-keep') {
      const r = resetsOf.get(id);
      if (r && r.state === 'read' && !r.spending) { r.arm = k === 'reset-arm' ? act.dataset.k : null; r.said = null; refill(); const yes = lens.querySelector('[data-act="reset-use"]'); yes && yes.focus(); }
    }
    if (k === 'reset-use') useReset(id, act.dataset.k);
    if (k === 'reopen-signin') { const s = signs.get(id); if (s) SIGNIN.reopen(s, s.on); }
    if (k === 'cancel-signin') { const s = signs.get(id); if (s) SIGNIN.cancel(s, s.on).then(over => { if (over) signs.delete(id); refill(); }); }
  });
  $('#backList').addEventListener('click', e => { const li = e.target.closest('li'); if (li && refs[li.dataset.id]) { refs[li.dataset.id].lane.scrollIntoView({ block: 'center', behavior: 'smooth' }); setTimeout(() => openLens(li.dataset.id), 350); } });
  $('#backList').addEventListener('keydown', e => { if (e.key === 'Enter') { const li = e.target.closest('li'); li && openLens(li.dataset.id); } });
  const foot = h('div', 'lanes-foot'), addBtn = h('button', 'btn quiet press', 'Add account'); addBtn.type = 'button'; addBtn.dataset.act = 'add';
  addBtn.addEventListener('click', () => openAdd(null));
  /* the right rail: a login opens its glass, and Add login starts a sign-in for that provider's command */
  const railEl = $('.rail-r');
  const railPress = e => {
    const hb = e.target.closest('button[data-harness]');
    if (hb) { if (!hb.disabled) pressHarness(hb); return true; }
    const add = e.target.closest('[data-add]');
    if (add) { const p = SPLICE.providers.find(x => x.key === add.dataset.add); if (p) openId === 'add:' + p.key ? closeLens() : openAdd(p); return true; }
    const row = e.target.closest('.acct[data-id]'); if (!row) return false;
    if (railRefs[row.dataset.id] && railRefs[row.dataset.id].nm.isContentEditable) return false;
    openId === row.dataset.id ? closeLens() : openLens(row.dataset.id);
    return true;
  };
  railEl.addEventListener('click', railPress);
  railEl.addEventListener('keydown', e => { if ((e.key === 'Enter' || e.key === ' ') && e.target.matches('.acct[data-id]') && railPress(e)) e.preventDefault(); });
  foot.append(addBtn); lanesEl.after(foot);

  /* the floating alert: the accounts that need him; pressing it dims the lanes that don't */
  let dimmed = false;
  function paintNeed() {
    const need = a => ['limit', 'out'].includes(a.state);
    Deck.setNeed(claude.accounts.filter(need).length, 'need you', on => { dimmed = on; paintDim(); });
  }
  function paintDim() { for (const id of order) if (refs[id]) refs[id].lane.classList.toggle('is-dim', dimmed && !['limit', 'out'].includes(byId[id].state)); }

  /* ---------- composer: the kit's, asking the manager ---------- */
  const CMDS = [['use', 'Serve from an account'], ['move', 'Move sessions between accounts'], ['signin', 'Sign an account back in'], ['rename', 'Rename an account'], ['order', 'Change the rotation order'], ['limits', 'Explain the current limits']];
  const composer = Deck.composer($('#compWrap'), {
    providers: SPLICE.providers,
    selected: { provider: 'claude', model: '' },
    effort: 'high',
    modes: SPLICE.MODES, mode: 'ask',
    commands: CMDS,
    mentions: () => claude.accounts.map(a => ({ label: a.name, sub: stateWord(a), state: a.state, insert: '@' + a.name + ' ' }))
      .concat(SPLICE.providers.filter(p => p.family !== 'anthropic').map(p => ({ label: p.name, sub: 'provider · ' + p.command, color: p.color, insert: '@' + p.name + ' ' }))),
    context: () => { const s = claude.accounts.find(a => a.state === 'serving'); return { state: 'serving', text: 'To the manager' + (s ? ' · runs on ' + s.name + ' · Claude' : '') }; },
    placeholder: 'Ask the manager anything. @ accounts, / commands',
    workingText: 'Sending to the manager',
    onSend: msg => SPLICE.manager.say(msg.text.trim()),
  });
  $('#compWrap .comp').setAttribute('aria-label', 'Ask the manager');
  SPLICE.ready.then(() => { const p = SPLICE.providers.find(x => x.family === 'anthropic') || SPLICE.providers[0]; if (p) composer.setModel(p.id, p.models[0] ? p.models[0][0] : p.command); });

  /* ---------- build, and the reads on their own clock ---------- */
  function paintHead() {
    const n = claude.accounts.length;
    $('#kick').textContent = 'Claude · ' + n + (n === 1 ? ' account' : ' accounts');
    const status = $('.head .status');
    status.classList.toggle('is-off', readOk === false);
    status.children[1].textContent = readOk === false ? 'Not answering' : 'Live';
  }
  function rebuild() {
    const keep = openId;
    axis();
    renderLanes(); renderServing(); renderToday(); renderBack(); renderProviders(); paintHead(); paintNeed(); paintDim();
    composer.refresh && composer.refresh();
    drawScale(); placeFlips(); drawMarks();
    const typing = lens.contains(document.activeElement) && document.activeElement.tagName === 'INPUT';
    if (adding(keep)) { if (!typing) fillAdd(adding(keep).p); }
    else if (keep && acct(keep) && marked(keep)) { mark(keep); if (!typing) fillLens(acct(keep)); }
    else if (keep) closeLens();
    if (openId) placeLens();
  }
  addEventListener('resize', () => { placeFlips(); drawMarks(); });
  const setClock = () => { $('#clock').textContent = Deck.clock(); };
  setClock(); setInterval(setClock, 15000);
  let busy = false;
  const readNow = async () => { if (busy || document.hidden || document.body.classList.contains('locked')) return; busy = true; try { await read(); } finally { busy = false; } };
  SPLICE.ready.then(readNow);
  setInterval(readNow, READ_MS);
  addEventListener('splice:signed-in', readNow);
})();
