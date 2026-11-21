/* Teams: the approved Night Deck page (prototype teams/final), on splice's live reads. Each live team is a band: the team
   on the left (goal, repo, members working, requests and API value over 7 days, its lead's login and the week it has
   used), its members on the right as a map, the lead first with a wire to each member carrying today's message count.
   Pressing a member, a wire or the team grows its glass; under the teams, the running sessions on no team, a new team
   and the ended teams.

   Reads, every five seconds while the page is shown and nothing in a glass is being typed, as the old page read them:
   /api/teams (archived included), /api/sessions, /api/accounts, and per live team /api/teams/{id}/economics (each
   member's week) and /api/teams/{id}/chat over this browser's day (the messages between members). Writes go where the
   old page sent them: PUT /api/teams (new), PUT /api/teams/{id} (name, goal, repo, and a member's role, model and login
   in its slot), PUT /api/teams/{id}/sessions (who sits in a slot), PUT …/slots/{slot}/instructions, POST
   …/slots/{slot}/start|stop|answer, POST /api/teams/{id}/end then /archive, POST /api/teams/{id}/restore, and a message
   to a member through POST /api/sessions/{session}/say, the route the console steers a session by. */
(() => {
  'use strict';
  const { $, $$, h, px } = Deck, SP = window.SPLICE;
  const tm = $('#tm'), ins = Deck.inspector(tm);
  const enc = encodeURIComponent;
  const READ_MS = 5000, DAY = 86400000, WEEK_MS = 7 * DAY;
  const why = res => (res.status === 0 ? 'splice is not answering' : (res.body && (res.body.error || res.body.reason)) || 'splice answered ' + res.status);

  /* ---------- words and figures ---------- */
  const provOf = head => SP.providerOf(head);
  const provName = head => (provOf(head) || {}).name || head || 'Direct';
  const pc = head => (provOf(head) || {}).color || 'var(--ink-faint)';
  const modelName = id => (id ? SP.modelName(id) : 'No model picked');
  const kfmt = n => n == null ? '—' : n >= 1e6 ? `${(n / 1e6).toFixed(1)}M` : n >= 1e3 ? `${(n / 1e3).toFixed(1)}k` : String(Math.round(n));
  const usd = n => n == null ? '—' : `$${Math.round(n).toLocaleString('en-US')}`;
  // past two days an age reads in days (the shell's ago), so a team ended last week says 9 d, not 216 h
  const ago = ms => (ms < 60000 ? 'now' : Deck.ago(ms));
  const ROLES = ['Lead', 'Builder', 'Reviewer', 'Cases', 'Coordinator'];
  const STATE = { working: 'Working', needs: 'Needs you', idle: 'Idle', stalled: 'Stalled', stopped: 'Stopped', starting: 'Starting' };
  const LAMP = { working: 'working', needs: 'needs', idle: 'idle', stalled: 'stalled', stopped: 'ended', starting: 'next' };
  const GO = '<svg class="ico" viewBox="0 0 24 24"><path d="M8 16L16 8M10 8h6v6"/></svg>';
  const MSG = '<svg class="ico" viewBox="0 0 24 24"><path d="M5 8h14M15 3.5L19.5 8 15 12.5M19 16H5M9 11.5L4.5 16 9 20.5"/></svg>';
  const leadOf = t => t.members.find(m => m.lead) || t.members[0];
  const sum = (t, f) => t.members.some(m => f(m) == null) && t.members.every(m => f(m) == null) ? null : t.members.reduce((n, m) => n + (f(m) || 0), 0);
  const loginUsers = label => T.teams.reduce((n, t) => n + t.members.filter(m => m.login && m.login.label === label && m.state !== 'stopped').length, 0);
  const goMark = () => { const g = h('span', 'pt-go'); g.innerHTML = GO; return g; };
  const clockNow = () => Deck.clock();

  /* ---------- the live read, in the page's shape ---------- */
  let T = { teams: [], loose: [], ended: [], repos: [] }, rows = [], sessions = new Map(), accounts = [], readOk = null, readAt = 0;
  const today = () => { const f = new Date(); f.setHours(0, 0, 0, 0); return [+f, +f + DAY]; };
  const labelOn = (r, head) => (r.account_labels && r.account_labels[head]) || r.label;
  /** a login by the label a member's session runs on, with the week it has used */
  function loginOf(head, label) {
    if (!label) return null;
    const r = accounts.find(x => (x.heads || []).includes(head) && labelOn(x, head) === label);
    const week = r && r.seven_day_used_percent != null ? Math.round(r.seven_day_used_percent) : null;
    return { label, name: r ? r.display_name || r.email || r.label : label, weekPct: week };
  }
  /** the logins a member on [head] can run on */
  const loginsFor = head => accounts.filter(x => (x.heads || []).includes(head) && x.kind !== 'api-key').map(x => loginOf(head, labelOn(x, head)));
  function stateOf(slot, s, screen) {
    if (screen) return 'needs';
    if (!slot.session || !s || s.availability !== 'live') return 'stopped';
    if (s.waiting_for) return 'needs';
    if (s.status === 'working' || s.status === 'busy') return 'working';
    if (s.status === 'stalled') return 'stalled';
    return 'idle';
  }
  /** what a member's screen asks, where its transcript carries no question: a permission prompt, or the folder trust
   *  question a start stopped on before its session registered. The words are the client's, read off its screen. */
  function screenAsk(screen, s) {
    const line = (screen.panel || []).map(p => p.text || '').find(x => x.includes('?'));
    const question = line ? line.slice(0, line.indexOf('?') + 1) : (s && s.waiting_for) || screen.asked || 'Needs you';
    return { kind: 'dialog', question, choices: screen.choices.map(c => c.label), picks: screen.choices.map(c => c.choice) };
  }
  function memberOf(t, slot, week, screen) {
    const s = slot.session ? sessions.get(slot.session) : null;
    const asked = s && s.last && Array.isArray(s.last.asks) ? s.last.asks[0] : null;
    const state = stateOf(slot, s, screen);
    return {
      id: slot.id, slot, session: slot.session || null, name: (s && (s.name || s.title)) || slot.id, role: slot.role || 'Builder', lead: !!slot.lead,
      head: slot.head, model: slot.model || (s && (s.served_model || s.model)) || null, login: loginOf(slot.head, slot.account || (s && s.account)),
      state, instructions: slot.instructions || '', last: s && s.last ? s.last.text : '',
      ask: screen ? screenAsk(screen, s) : state === 'needs' && asked && Array.isArray(asked.options) && asked.options.length ? { kind: 'input', question: asked.question || s.waiting_for, choices: asked.options.slice() } : null,
      waiting: state === 'needs' ? (s && s.waiting_for) || 'Needs you' : null,
      sinceMs: s && s.status_updated_at ? Date.now() - s.status_updated_at : 0,
      week: week ? { requests: week.turns, outputTokens: week.tokens ? week.tokens.output : null, apiValue: week.cost_usd } : { requests: null, outputTokens: null, apiValue: null },
    };
  }
  async function read() {
    const [tr, sr, ar] = await Promise.all([API.get('/api/teams'), API.get('/api/sessions'), API.get('/api/accounts')]);
    readOk = tr.ok;
    if (!tr.ok) { paintHead(); return; }
    rows = (tr.body && tr.body.teams) || [];
    if (sr.ok) sessions = new Map(((sr.body && sr.body.sessions) || []).map(s => [s.session_id, s]));
    if (ar.ok) accounts = (ar.body && ar.body.accounts) || [];
    const live = rows.filter(r => !r.archived), [from, to] = today();
    const per = await Promise.all(live.map(r => Promise.all([
      API.get('/api/teams/' + enc(r.id) + '/economics'), API.get('/api/teams/' + enc(r.id) + '/chat?from=' + from + '&to=' + to),
    ])));
    // a member waiting on what its transcript does not carry, or bound to a session that never registered, is read off
    // its screen, as the old page read it
    const unasked = live.flatMap(r => (r.slots || []).filter(slot => {
      const s = slot.session ? sessions.get(slot.session) : null;
      return slot.session && (!s || (s.waiting_for && !(s.last && Array.isArray(s.last.asks) && s.last.asks.length)));
    }).map(slot => [r, slot]));
    const screens = new Map((await Promise.all(unasked.map(([r, slot]) => API.get('/api/teams/' + enc(r.id) + '/slots/' + enc(slot.id) + '/screen'))))
      .map((res, i) => [unasked[i][0].id + '/' + unasked[i][1].id, res.ok && res.body && Array.isArray(res.body.choices) && res.body.choices.length ? res.body : null]));
    T.teams = live.map((r, i) => {
      const [ec, ch] = per[i];
      const weeks = new Map(((ec.ok && ec.body.slots) || []).map(x => [x.slot, x.week]));
      const t = { id: r.id, name: r.name, goal: r.goal || '', repo: r.repo || '', row: r, members: [], messages: (ch.ok && ch.body.messages) || [] };
      t.members = (r.slots || []).map(slot => memberOf(t, slot, weeks.get(slot.id), screens.get(r.id + '/' + slot.id)));
      return t;
    });
    const bound = new Set(live.flatMap(r => (r.slots || []).map(s => s.session).filter(Boolean)));
    T.loose = [...sessions.values()].filter(s => s.availability === 'live' && !bound.has(s.session_id)).map(s => ({
      id: s.session_id, name: s.name || s.title || s.session_id.slice(0, 8), project: s.project ? s.project.name : null, head: s.head, row: s,
    }));
    T.ended = rows.filter(r => r.archived).map(r => ({ id: r.id, name: r.name, repo: r.repo, members: (r.slots || []).length, endedMs: r.updated_epoch_millis || 0 }))
      .sort((a, b) => b.endedMs - a.endedMs);
    T.repos = [...new Set([...rows.map(r => r.repo), ...[...sessions.values()].map(s => s.repo && s.repo.root)].filter(Boolean))];
    readAt = Date.now();
    draw();
    reopen();
  }

  /* ---------- the teams ---------- */
  function paintHead() {
    const working = T.teams.reduce((n, t) => n + t.members.filter(m => m.state === 'working').length, 0);
    const members = T.teams.reduce((n, t) => n + t.members.length, 0);
    $('#tmEyebrow').textContent = readOk === false && !readAt ? 'splice is not answering' : `${Deck.count(T.teams.length, 'team')} · ${Deck.count(members, 'member')}`;
    const st = $('#tmStatus');
    st.replaceChildren(Deck.lamp(readOk === false ? 'stalled' : 'working'), h('span', '', readOk === false ? 'Not answering' : `${working} working`), h('span', 'faint', '·'), h('span', '', clockNow()));
  }
  function draw() {
    const needs = T.teams.flatMap(t => t.members.filter(m => m.state === 'needs').map(m => [t, m]));
    paintHead();
    Deck.setNeed(needs.length, 'needs you', () => { const [t, m] = needs[0]; open(t, m); });
    const order = [...T.teams].sort((a, b) => rank(b) - rank(a));
    $('#bands').replaceChildren(...(order.length ? order.map(band) : [h('p', 'faint', readOk === false ? 'splice is not answering' : 'No teams')]));
    more();
    requestAnimationFrame(wiresAll);
  }
  const rank = t => t.members.some(m => m.state === 'needs') * 100 + t.members.filter(m => m.state === 'working').length;

  function band(t) {
    const b = h('div', 'band'); b.dataset.team = t.id;
    if (fresh.has(t.id)) { b.classList.add('is-new'); fresh.delete(t.id); }
    b.append(teamCard(t), map(t));
    return b;
  }
  const fresh = new Set();
  function teamCard(t) {
    const c = h('button', 'pt tc press'); c.type = 'button'; c.dataset.open = 'team'; c.dataset.team = t.id;
    const needs = t.members.some(m => m.state === 'needs'), working = t.members.filter(m => m.state === 'working').length;
    const name = h('span', 'pt-name'); name.append(Deck.lamp(needs ? 'needs' : working ? 'working' : 'idle'), 'Team');
    const figs = h('div', 'tc-figs');
    [[t.members.length, 'Members'], [working, 'Working'], [kfmt(sum(t, m => m.week.requests)), 'Requests · 7 d'], [usd(sum(t, m => m.week.apiValue)), 'API value · 7 d']]
      .forEach(([v, w]) => { const f = h('div', 'tc-fig'); f.append(h('b', '', String(v)), h('span', '', w)); figs.append(f); });
    c.append(goMark(), name, h('span', 'tc-title', t.name), h('span', 'tc-goal', t.goal), figs);
    const lead = leadOf(t), lg = lead && lead.login;
    if (lg) {
      const row = h('div', 'tc-login'), bar = h('span', 'mtr'), i = h('i');
      i.style.setProperty('--u', (lg.weekPct || 0) / 100); bar.style.setProperty('--c', lg.weekPct >= 90 ? 'var(--vermilion)' : 'var(--prussian)'); bar.append(i);
      row.append(h('span', '', `Login ${lg.name}`), h('b', '', lg.weekPct == null ? 'week not read' : `${lg.weekPct}% of week`), bar); c.append(row);
    }
    if (t.repo) c.append(h('span', 'tc-repo', t.repo));
    return c;
  }
  function memberCard(t, m, lead) {
    const b = h('button', 'pt mb press' + (lead ? ' lead' : '') + (m.state === 'needs' ? ' is-needs' : '')); b.type = 'button';
    b.dataset.open = 'member'; b.dataset.team = t.id; b.dataset.member = m.id;
    const top = h('span', 'mb-top'); top.append(Deck.lamp(LAMP[m.state] || 'idle'), h('span', 'mb-name', m.name));
    const meta = h('span', 'mb-meta'), mod = h('span'); mod.append(!m.model ? provName(m.head) : modelName(m.model));
    meta.append(mod, h('b', '', `${kfmt(m.week.requests)} req`));
    const last = m.state === 'needs' ? `Needs you · ${m.ask ? m.ask.question : m.waiting}` : m.state === 'stopped' ? 'Stopped' : m.last || STATE[m.state];
    b.append(goMark(), h('span', 'mb-role', m.role), top, meta, h('span', 'mb-last', last));
    return b;
  }
  /** the map: lead in the first column, the other members in the third, wires drawn after layout */
  function map(t) {
    const mp = h('div', 'map'); mp.dataset.team = t.id;
    const lead = leadOf(t), rest = t.members.filter(m => m !== lead);
    mp.style.setProperty('--rows', Math.max(1, rest.length));
    const svg = document.createElementNS('http://www.w3.org/2000/svg', 'svg'); svg.setAttribute('class', 'wires'); svg.setAttribute('aria-hidden', 'true');
    mp.append(svg);
    if (lead) mp.append(memberCard(t, lead, true));
    rest.forEach((m, i) => { const c = memberCard(t, m, false); c.style.gridRow = String(i + 1); mp.append(c); });
    return mp;
  }
  /** today's messages between two members, either way */
  const between = (t, a, b) => t.messages.filter(x => (x.from_slot === a.id && x.to_slot === b.id) || (x.from_slot === b.id && x.to_slot === a.id));
  function wires(mp) {
    const t = T.teams.find(x => x.id === mp.dataset.team); if (!t || !t.members.length) return;
    const svg = mp.querySelector('.wires'), box = mp.getBoundingClientRect();
    mp.querySelectorAll('.msg').forEach(n => n.remove());
    const card = id => mp.querySelector(`[data-member="${CSS.escape(id)}"]`).getBoundingClientRect();
    const lead = leadOf(t), L = card(lead.id), rest = t.members.filter(m => m !== lead);
    let d = '';
    const label = (x, y, a, b, n) => {
      const btn = h('button', 'msg press'); btn.type = 'button'; btn.dataset.open = 'messages'; btn.dataset.team = t.id; btn.dataset.a = a.id; btn.dataset.b = b.id;
      btn.style.setProperty('--x', `${x}px`); btn.style.setProperty('--y', `${y}px`); btn.setAttribute('aria-label', `${Deck.count(n, 'message')} between ${a.name} and ${b.name} today`);
      const ic = h('span'); ic.innerHTML = MSG; btn.append(ic, String(n)); mp.append(btn);
    };
    rest.forEach(m => {
      const R = card(m.id), a = { x: L.right - box.left, y: L.top - box.top + L.height / 2 }, b = { x: R.left - box.left, y: R.top - box.top + R.height / 2 };
      const mx = (a.x + b.x) / 2, live = m.state === 'working' || lead.state === 'working';
      d += `<path class="${live ? 'live' : ''}" d="M${a.x} ${a.y} C${mx} ${a.y} ${mx} ${b.y} ${b.x} ${b.y}"/><circle cx="${b.x}" cy="${b.y}"/>`;
      const n = between(t, lead, m).length; if (n) label(mx, (a.y + b.y) / 2, lead, m, n);
    });
    // messages between two members who are not the lead: an arc on the far side of the column
    for (let i = 0; i < rest.length; i++) for (let j = i + 1; j < rest.length; j++) {
      const n = between(t, rest[i], rest[j]).length; if (!n) continue;
      const A = card(rest[i].id), B = card(rest[j].id), x = A.right - box.left, ya = A.top - box.top + A.height / 2, yb = B.top - box.top + B.height / 2, out = x + Math.max(px('--wire-out'), (yb - ya) * 0.32);
      d += `<path class="side" d="M${x} ${ya} C${out} ${ya} ${out} ${yb} ${x} ${yb}"/>`;
      label(x + (out - x) * 0.75, (ya + yb) / 2, rest[i], rest[j], n);
    }
    svg.innerHTML = d;
  }
  const wiresAll = () => $$('.map').forEach(wires);
  addEventListener('resize', () => requestAnimationFrame(wiresAll));

  /* ---------- under the teams ---------- */
  function more() {
    const box = $('#more'); box.replaceChildren();
    const loose = h('button', 'pt press'); loose.type = 'button'; loose.dataset.open = 'loose';
    const chips = h('div', 'chips');
    T.loose.slice(0, 8).forEach(s => { const c = h('span'), i = h('i'); i.style.setProperty('--pc', pc(s.head)); c.append(i, s.name.length > 22 ? s.name.slice(0, 21) + '…' : s.name); chips.append(c); });
    if (T.loose.length > 8) chips.append(h('span', '', `+${T.loose.length - 8}`));
    const f1 = h('span', 'pt-fig', String(T.loose.length)); f1.append(' ', h('small', '', T.loose.length === 1 ? 'session' : 'sessions'));
    loose.append(goMark(), h('span', 'pt-name', 'Running, not on a team'), f1, chips);
    const nw = h('button', 'pt press'); nw.type = 'button'; nw.dataset.open = 'new';
    nw.append(goMark(), h('span', 'pt-name', 'New team'), h('span', 'pt-fig', '+'), h('span', 'pt-cap', 'Name, repo, lead and builders'));
    const en = h('button', 'pt press'); en.type = 'button'; en.dataset.open = 'ended';
    const week = T.ended.filter(e => Date.now() - e.endedMs < WEEK_MS).length;
    const f3 = h('span', 'pt-fig', String(week)); f3.append(' ', h('small', '', 'this week'));
    en.append(goMark(), h('span', 'pt-name', 'Ended teams'), f3, h('span', 'pt-cap', T.ended[0] ? `Last: ${T.ended[0].name}` : 'None'));
    box.append(loose, nw, en);
  }

  /* ---------- the lens: grown out of what was pressed ---------- */
  let openKey = null, openAnchor = null;
  function open(t, m, anchor) {
    anchor = anchor || (m ? $(`.map[data-team="${CSS.escape(t.id)}"] [data-member="${CSS.escape(m.id)}"]`) : $(`.tc[data-team="${CSS.escape(t.id)}"]`));
    if (anchor) show(anchor, { kind: m ? 'member' : 'team', t, m });
  }
  function show(anchor, what) {
    $$('[aria-expanded="true"]').forEach(x => x.setAttribute('aria-expanded', 'false'));
    anchor.setAttribute('aria-expanded', 'true'); openAnchor = anchor; openKey = what;
    ins.openBeside(anchor, el => {
      el.classList.toggle('wide', what.kind !== 'messages');
      const hd = h('div', 'hd');
      if (what.kind === 'member') hd.append(Deck.lamp(LAMP[what.m.state] || 'idle'));
      hd.append(h('h3', '', titleOf(what)));
      const x = Deck.iconBtn('close', 'Close'); x.addEventListener('click', () => closeLens()); hd.append(x);
      const body = h('div'); el.append(hd, body);
      ({ member: memberLens, team: teamLens, messages: messagesLens, loose: looseLens, new: newLens, ended: endedLens })[what.kind](body, what);
    }, tm);
  }
  const titleOf = w => w.kind === 'member' ? w.m.name : w.kind === 'team' ? w.t.name : w.kind === 'messages' ? 'Messages today' : w.kind === 'loose' ? 'Running, not on a team' : w.kind === 'new' ? 'New team' : 'Ended teams';
  /** closes the glass and forgets it at once, so a read landing while it fades never opens it again */
  function closeLens() { openKey = null; if (openAnchor) openAnchor.setAttribute('aria-expanded', 'false'); openAnchor = null; ins.close(); }
  new MutationObserver(() => { if (ins.el.hidden && openAnchor) { openAnchor.setAttribute('aria-expanded', 'false'); openAnchor = null; openKey = null; } }).observe(ins.el, { attributes: true, attributeFilter: ['hidden'] });
  /** after a read that redraws the field, the same lens again on the new element, its figures from the read; one he is
   *  typing in, or whose save is on its way, is left as it is */
  function reopen() {
    if (!openKey || busy()) return;
    const w = openKey;
    const t = w.t && T.teams.find(x => x.id === w.t.id);
    if (w.t && !t) { closeLens(); return; }
    const m = w.m && t.members.find(x => x.id === w.m.id);
    if (w.m && !m) { closeLens(); return; }
    const sel = w.kind === 'member' ? `.map[data-team="${CSS.escape(t.id)}"] [data-member="${CSS.escape(m.id)}"]` : w.kind === 'team' ? `.tc[data-team="${CSS.escape(t.id)}"]`
      : w.kind === 'messages' ? `.map[data-team="${CSS.escape(t.id)}"] .msg[data-a="${CSS.escape(w.a.id)}"][data-b="${CSS.escape(w.b.id)}"]` : `[data-open="${w.kind}"]`;
    const a = $(sel);
    if (!a) { closeLens(); return; }
    show(a, Object.assign({}, w, { t, m, a: w.a && t && t.members.find(x => x.id === w.a.id), b: w.b && t && t.members.find(x => x.id === w.b.id) }));
  }
  let saving = 0;
  // a save running or a field being typed in: the open glass is not drawn again under them
  const busy = () => saving > 0 || (!ins.el.hidden && ins.el.contains(document.activeElement) && /^(INPUT|TEXTAREA)$/.test(document.activeElement.tagName));

  function row(name, sub) {
    const r = h('div', 'lr'), nm = h('div', 'lr-nm', name); if (sub) nm.append(h('small', '', sub));
    const sv = Deck.saveState({ rest: () => '' }), ctl = h('div', 'lr-ctl');
    r.append(nm, sv, ctl); return { r, sv, ctl };
  }
  /** a write, said where it was made: Saving, then what splice answered; a refusal says why */
  async function commit(sv, run, word) {
    sv.set('saving'); saving++;
    let res;
    try { res = await run(); } finally { saving--; }
    if (res.ok) sv.set('applied', word); else sv.set('refused', why(res));
    if (res.ok) await read();
    return res;
  }
  const btn = (cls, word, on) => { const b = h('button', cls + ' press', word); b.type = 'button'; if (on) b.addEventListener('click', on); return b; };

  /* ---------- writes, on the routes the console has always used ---------- */
  /** a slot as PUT /api/teams/{id} takes it */
  const slotBody = s => ({ id: s.id, role: String(s.role || '').trim() || s.id, head: s.head, model: s.model || undefined, account: s.account || undefined,
    lead: Boolean(s.lead), bind_by_rule: s.bind_by_rule || undefined, instructions: (s.instructions || '').trim() || undefined });
  /** the team saved as it stands, with [change] applied to its slots and fields */
  function putTeam(t, change = {}) {
    let slots = t.row.slots.map(s => Object.assign({}, s, (change.slots || {})[s.id] || {}));
    if (change.drop) slots = slots.filter(s => s.id !== change.drop);
    if (change.add) slots = slots.concat(change.add);
    const body = { id: t.id, name: change.name ?? t.name, goal: change.goal ?? t.goal, repo: change.repo ?? t.repo, slots: slots.map(slotBody) };
    return API.put('/api/teams/' + enc(t.id), body);
  }
  const slotPath = (t, m, act) => '/api/teams/' + enc(t.id) + '/slots/' + enc(m.id) + '/' + act;
  const once = () => 'console-' + Date.now().toString(36) + Math.random().toString(36).slice(2, 8);
  /** a slot id the team does not hold yet */
  const freeSlot = (t, base) => { let n = 1; while (t.row.slots.some(s => s.id === base + (n > 1 ? '-' + n : ''))) n++; return base + (n > 1 ? '-' + n : ''); };

  function memberLens(body, { t, m }) {
    if (m.state === 'needs') {
      const r = h('div', 'lr');
      // the answer is the key the person would press: the option's own number, with the words drawn under it
      if (m.ask) r.append(Deck.answer(m.ask, { waited: m.sinceMs ? `waiting ${ago(m.sinceMs)}` : undefined, onAnswer: (i, label) => { saving++; API.post(slotPath(t, m, 'answer'), { choice: m.ask.picks ? m.ask.picks[i] : i + 1, label }).finally(() => { saving--; read(); }); } }));
      else r.append(h('div', 'lr-nm', m.waiting || 'Needs you'), h('span'), h('div', 'last', 'Its terminal shows the choices; open it in Sessions to answer.'));
      body.append(r);
    }
    { const r = h('div', 'lr'), f = h('div', 'figs'); f.classList.add('span-all');
      [[STATE[m.state], m.state === 'working' || m.state === 'needs' ? `Status · ${ago(m.sinceMs)}` : 'Status'], [kfmt(m.week.requests), 'Requests · 7 d'], [kfmt(m.week.outputTokens), 'Output tokens · 7 d'], [usd(m.week.apiValue), 'API value · 7 d']]
        .forEach(([v, w]) => { const d = h('div'); d.append(h('b', '', v), h('span', '', w)); f.append(d); });
      r.append(f); body.append(r); }
    if (m.last) { const r = h('div', 'lr'); r.append(h('div', 'lr-nm', 'Last message'), h('span'), h('div', 'last', m.last)); body.append(r); }
    { const { r, sv, ctl } = row('Role', m.lead ? 'Messages reach the team through the lead' : '');
      const roles = ROLES.includes(m.role) ? ROLES : [...ROLES, m.role];
      ctl.append(Deck.choice(roles.map(v => ({ v, label: v })), m.role, v => commit(sv, () => {
        // the lead is one slot: naming another the lead hands it over
        const slots = { [m.id]: { role: v, lead: v === 'Lead' || (m.lead && v !== 'Lead' ? false : m.lead) } };
        if (v === 'Lead') t.members.filter(x => x.lead && x !== m).forEach(x => { slots[x.id] = { lead: false, role: x.role === 'Lead' ? 'Builder' : x.role }; });
        return putTeam(t, { slots });
      })));
      body.append(r); }
    { const { r, sv, ctl } = row('Model', 'Applies to its next request');
      ctl.append(modelPicker(m.head, m.model, (head, id) => commit(sv, () => putTeam(t, { slots: { [m.id]: { head, model: id, account: head === m.head ? m.slot.account : null } } }))));
      body.append(r); }
    const logins = loginsFor(m.head);
    if (logins.length) {
      const { r, sv, ctl } = row('Login');
      const cons = h('div', 'lr-cons');
      const say = lg => { cons.replaceChildren(); if (!lg) return; cons.append(h('span', lg.weekPct >= 90 ? 'warn' : '', lg.weekPct == null ? `${lg.name}: week not read` : `${lg.name}: ${lg.weekPct}% of the week used`), h('span', '', `${loginUsers(lg.label)} running members use it`)); };
      ctl.append(Deck.choice(logins.map(l => ({ v: l.label, label: l.name, sub: l.weekPct == null ? '' : `${l.weekPct}% of week` })), m.login && m.login.label, v => {
        say(logins.find(l => l.label === v)); commit(sv, () => putTeam(t, { slots: { [m.id]: { account: v } } }));
      }, { max: 0 }));
      r.append(cons); say(m.login); body.append(r);
    }
    { const { r, sv, ctl } = row('Instructions', 'Sent with every request it starts');
      const ta = h('textarea', 'lr-in'); ta.value = m.instructions || ''; ta.rows = 3;
      ta.addEventListener('keydown', e => e.stopPropagation());
      const save = btn('btn quiet', 'Save', () => { if (ta.value === m.instructions) return; commit(sv, () => API.put(slotPath(t, m, 'instructions'), { instructions: ta.value.trim() || null }), 'Saved'); });
      ctl.append(ta, save); body.append(r); }
    if (m.session && m.state !== 'stopped') {
      const { r, sv, ctl } = row('Message', `To ${m.name}`);
      const i = h('input', 'lr-in'); i.placeholder = 'Message'; i.spellcheck = true;
      const send = () => { const v = i.value.trim(); if (!v) return; commit(sv, async () => { const res = await API.post('/api/sessions/' + enc(m.session) + '/say', { text: v }); if (res.ok) i.value = ''; return res; }, 'Sent'); };
      i.addEventListener('keydown', e => { if (e.key === 'Enter') { e.preventDefault(); send(); } e.stopPropagation(); });
      ctl.append(i, btn('btn', 'Send', send)); body.append(r);
    }
    { const r = h('div', 'lr'), acts = h('div', 'lr-ctl'), sv = Deck.saveState({ rest: () => '' });
      if (m.session) { const link = h('a', 'btn quiet press', 'Open in Sessions'); link.href = `sessions.html#${enc(m.session)}`; acts.append(link); }
      const stopped = m.state === 'stopped';
      const ss = btn('btn quiet', stopped ? 'Start' : 'Stop', () => {
        if (stopped) { commit(sv, () => API.post(slotPath(t, m, 'start'), {}), 'Starting'); return; }
        commit(sv, () => API.post(slotPath(t, m, 'stop'), {}), 'Stopped');
      });
      // each acts on its one press; what it does is said beside the buttons
      r.append(h('div', 'small faint', stopped ? `Remove: ${m.name} leaves the team` : `Stop ends ${m.name}'s session · Remove keeps it running, on no team`));
      const rm = btn('btn quiet', 'Remove from team', () => commit(sv, async () => {
        const res = await putTeam(t, { drop: m.id, slots: m.lead && t.members.length > 1 ? { [t.members.find(x => x !== m).id]: { lead: true, role: 'Lead' } } : {} });
        if (res.ok) closeLens();
        return res;
      }));
      acts.append(ss, rm); r.append(acts, sv); body.append(r); }
  }

  /** a provider (menu), then one of its models (at most five, side by side) */
  function modelPicker(head, id, onPick) {
    const box = h('div', 'lr-ctl'); box.classList.add('auto-col');
    const prov = provOf(head) || SP.providers[0];
    if (!prov) { box.append(h('span', 'faint', modelName(id))); return box; }
    const models = h('div');
    const paintModels = (p, cur) => { models.replaceChildren(Deck.choice(p.models.slice(0, 5).map(([label, v]) => ({ v, label })), cur, v => onPick(p.key, v))); };
    box.append(Deck.choice(SP.providers.map(p => ({ v: p.key, label: p.name })), prov.key, v => { const p = provOf(v); const first = p.models[0] ? p.models[0][1] : null; paintModels(p, first); onPick(p.key, first); }, { max: 0 }), models);
    paintModels(prov, id);
    return box;
  }

  function teamLens(body, { t }) {
    const text = (name, key, ph) => {
      const { r, sv, ctl } = row(name);
      const i = h('input', 'lr-in'); i.value = t[key] || ''; i.placeholder = ph;
      const go = () => { const v = i.value.trim(); if (!v || v === t[key]) return; i.blur(); commit(sv, () => putTeam(t, { [key]: v })); };
      i.addEventListener('keydown', e => { if (e.key === 'Enter') { e.preventDefault(); go(); } e.stopPropagation(); }); i.addEventListener('change', go);
      ctl.append(i); body.append(r);
    };
    text('Name', 'name', 'Team name'); text('Goal', 'goal', 'What the team is building');
    { const { r, sv, ctl } = row('Repo', 'Where every member works');
      const opts = [...new Set([t.repo, ...T.repos].filter(Boolean))].map(v => ({ v, label: v }));
      ctl.append(Deck.choice(opts, t.repo, v => commit(sv, () => putTeam(t, { repo: v })), { max: 0 })); body.append(r); }
    { const lead = leadOf(t), { r, sv, ctl } = row('Members', `${t.members.length}${lead ? ' · lead ' + lead.name : ''}`);
      const lp = provOf((lead && lead.head) || 'claude') || SP.providers[0];
      const add = btn('btn quiet', 'Add member', () => Deck.openMenu(add, [
        ...T.loose.slice(0, 12).map(s => ({ label: s.name, sub: s.project || '', run: () => commit(sv, async () => {
          const id = freeSlot(t, 'member');
          const res = await putTeam(t, { add: { id, role: 'Builder', head: s.head, lead: false } });
          return res.ok ? API.put('/api/teams/' + enc(t.id) + '/sessions', { bindings: { [id]: s.id } }) : res;
        }, 'Added') })),
        ...(lp && lp.models[0] ? [{ label: `New session on ${lp.models[0][0]}`, run: () => commit(sv, async () => {
          const id = freeSlot(t, 'member');
          const res = await putTeam(t, { add: { id, role: 'Builder', head: lp.key, model: lp.models[0][1], lead: false } });
          return res.ok ? API.post('/api/teams/' + enc(t.id) + '/slots/' + enc(id) + '/start', {}) : res;
        }, 'Starting') }] : []),
      ], 'down'));
      ctl.append(add); body.append(r); }
    { const r = h('div', 'lr'), ctl = h('div', 'lr-ctl'), sv = Deck.saveState({ rest: () => '' }), running = t.members.filter(m => m.state !== 'stopped').length;
      ctl.append(btn('btn quiet', 'End team', () => commit(sv, async () => {
        const end = await API.post('/api/teams/' + enc(t.id) + '/end', { confirm: true });
        if (!end.ok) return end;
        const res = await API.post('/api/teams/' + enc(t.id) + '/archive', {});
        if (res.ok) closeLens();
        return res;
      }, 'Ended')));
      // one press ends it; the row says beforehand what that stops
      r.append(ctl, h('span', 'small faint', running ? `Stops ${Deck.count(running, 'session')}` : 'Nothing running'), sv); body.append(r); }
  }

  function messagesLens(body, { t, a, b }) {
    const lines = between(t, a, b).sort((x, y) => x.at - y.at);
    const r = h('div', 'lr'), fig = h('div', 'rows');
    [[a, b], [b, a]].forEach(([f, to]) => { const n = lines.filter(x => x.from_slot === f.id).length; if (!n) return; const d = h('div'); d.append(h('span', '', `${f.name} → ${to.name}`), h('b', '', String(n))); fig.append(d); });
    r.append(fig); body.append(r);
    const r2 = h('div', 'lr'), list = h('div', 'msgs'); list.classList.add('span-all');
    const name = slot => (slot === a.id ? a : b).name;
    lines.slice().reverse().forEach(x => {
      const d = h('div'), at = new Date(x.at).toLocaleTimeString('en-US', { hour: 'numeric', minute: '2-digit' });
      d.append(h('b', '', `${name(x.from_slot)} → ${name(x.to_slot)} · ${at}`), h('span', '', x.text != null ? x.text : 'Text not kept'));
      list.append(d);
    });
    r2.append(list); body.append(r2);
  }

  /** a new team from [ids], the first one leading, saved and its sessions sat in its slots */
  async function makeTeam(name, repo, ids) {
    const slots = ids.map((sid, i) => ({ id: i ? 'member-' + i : 'lead', role: i ? 'Builder' : 'Lead', head: sessions.get(sid).head, lead: !i }));
    const res = await API.put('/api/teams', { name, goal: '', repo, slots }, { 'Idempotency-Key': once() });
    if (!res.ok) return res;
    fresh.add(res.body.id);
    return API.put('/api/teams/' + enc(res.body.id) + '/sessions', { bindings: Object.fromEntries(ids.map((sid, i) => [slots[i].id, sid])) });
  }
  function looseLens(body) {
    const picked = new Set();
    const { r, sv, ctl } = row('Make a team', 'Pick the sessions; the first one leads');
    const list = h('div', 'pick-list');
    T.loose.forEach(s => {
      const p = btn('pick', ''); p.setAttribute('aria-pressed', 'false');
      p.append(h('i'), h('span', '', s.name), h('b', '', [s.project, provName(s.head)].filter(Boolean).join(' · ')));
      p.addEventListener('click', () => { picked.has(s) ? picked.delete(s) : picked.add(s); p.setAttribute('aria-pressed', String(picked.has(s))); make.disabled = !picked.size; make.textContent = picked.size ? `Make a team of ${picked.size}` : 'Make a team'; });
      list.append(p);
    });
    const name = h('input', 'lr-in'); name.placeholder = 'Team name'; name.addEventListener('keydown', e => e.stopPropagation());
    const make = btn('btn primary', 'Make a team', () => {
      const ss = [...picked];
      if (!ss.length) return;
      const first = ss[0].row, repo = (first.repo && first.repo.root) || first.cwd || '';
      commit(sv, async () => { const res = await makeTeam(name.value.trim() || ss[0].project || ss[0].name, repo, ss.map(s => s.id)); if (res.ok) closeLens(); return res; });
    });
    make.disabled = true;
    // the sessions to pick fill the row; the name and the act sit on their own line under them, never squeezed beside the list
    const go = h('div', 'lr-ctl'); go.append(name, make);
    ctl.append(list); r.append(go); body.append(r);
  }

  function newLens(body) {
    const p0 = provOf('claude') || SP.providers[0];
    const draft = { name: '', repo: T.repos[0] || '', head: p0 ? p0.key : null, model: p0 && p0.models[0] ? p0.models[0][1] : null, builders: 2 };
    const name = row('Name'); const i = h('input', 'lr-in'); i.placeholder = 'Team name'; i.addEventListener('keydown', e => e.stopPropagation()); i.addEventListener('input', () => { draft.name = i.value.trim(); }); name.ctl.append(i); name.sv.remove(); body.append(name.r);
    const repo = row('Repo'); repo.sv.remove();
    repo.ctl.append(T.repos.length ? Deck.choice(T.repos.map(v => ({ v, label: v })), draft.repo, v => { draft.repo = v; }, { max: 0 }) : h('span', 'faint', 'No repo read yet')); body.append(repo.r);
    const model = row('Model', 'For the lead and the builders'); model.sv.remove(); model.ctl.append(modelPicker(draft.head, draft.model, (head, id) => { draft.head = head; draft.model = id; })); body.append(model.r);
    const cnt = row('Builders'); cnt.sv.remove(); cnt.ctl.append(Deck.choice([1, 2, 3, 4].map(v => ({ v, label: String(v) })), draft.builders, v => { draft.builders = v; })); body.append(cnt.r);
    const go = row(''); go.r.querySelector('.lr-nm').remove();
    go.ctl.append(btn('btn primary', 'Create and start', () => commit(go.sv, async () => {
      const nm = draft.name || `team-${T.teams.length + 1}`;
      const slots = [{ id: 'lead', role: 'Lead', head: draft.head, model: draft.model, lead: true },
        ...Array.from({ length: draft.builders }, (_, k) => ({ id: 'builder-' + (k + 1), role: 'Builder', head: draft.head, model: draft.model, lead: false }))];
      const res = await API.put('/api/teams', { name: nm, goal: '', repo: draft.repo, slots }, { 'Idempotency-Key': once() });
      if (!res.ok) return res;
      fresh.add(res.body.id);
      for (const s of slots) { const st = await API.post('/api/teams/' + enc(res.body.id) + '/slots/' + enc(s.id) + '/start', {}); if (!st.ok) return st; }
      closeLens();
      return res;
    }, 'Starting')));
    body.append(go.r);
  }

  function endedLens(body) {
    if (!T.ended.length) { const r = h('div', 'lr'); r.append(h('div', 'lr-nm faint', 'None')); body.append(r); return; }
    T.ended.forEach(e => {
      const { r, sv, ctl } = row(e.name, `${Deck.count(e.members, 'member')}${e.endedMs ? ` · ended ${ago(Date.now() - e.endedMs)} ago` : ''}`);
      if (e.repo) { const f = h('div', 'rows'), d = h('div'); d.append(h('span', '', 'Repo'), h('b', '', e.repo)); f.append(d); ctl.append(f); }
      ctl.append(btn('btn quiet', 'Restore', () => commit(sv, async () => { const res = await API.post('/api/teams/' + enc(e.id) + '/restore', {}); if (res.ok) { fresh.add(e.id); closeLens(); } return res; })));
      body.append(r);
    });
  }

  /* ---------- pressing ---------- */
  tm.addEventListener('click', e => {
    const a = e.target.closest('[data-open]'); if (!a || a.tagName === 'A') return;
    if (ins.open === a) { closeLens(); return; }
    const t = T.teams.find(x => x.id === a.dataset.team);
    const kind = a.dataset.open;
    if (kind === 'member') show(a, { kind, t, m: t.members.find(m => m.id === a.dataset.member) });
    else if (kind === 'team') show(a, { kind, t });
    else if (kind === 'messages') show(a, { kind, t, a: t.members.find(m => m.id === a.dataset.a), b: t.members.find(m => m.id === a.dataset.b) });
    else show(a, { kind });
  });

  /* ---------- the reads on their own clock ---------- */
  setInterval(() => { const st = $('#tmStatus span:last-child'); if (st) st.textContent = clockNow(); }, 30000);
  let reading = false;
  const readNow = async () => {
    if (reading || document.hidden || document.body.classList.contains('locked') || busy()) return;
    reading = true;
    try { await read(); } finally { reading = false; }
  };
  paintHead();
  SP.ready.then(readNow);
  setInterval(readNow, READ_MS);
  addEventListener('splice:signed-in', readNow);
})();
