/* Server: Night Deck console page for team server mode (docs/specs/server-mode.md, F16).
   Reads /api/server/keys, /api/server/budgets, /api/server/upstreams, /api/server/usage?by=person&since=7d,
   and /api/server/audit?tail=50. Writes keys (create/revoke), people, teams, budgets, and on-demand backups. */
(() => {
  'use strict';
  const { $, h } = Deck, SP = window.SPLICE;
  const enc = encodeURIComponent;
  const READ_MS = 5000;
  const why = res => (res.status === 0 ? 'splice is not answering' : (res.body && (res.body.error || res.body.reason)) || 'splice answered ' + res.status);
  const usd = n => n == null ? '—' : '$' + Number(n).toLocaleString('en-US', { minimumFractionDigits: 2, maximumFractionDigits: 2 });
  const kfmt = n => n == null ? '0' : n >= 1e6 ? `${(n / 1e6).toFixed(1)}M` : n >= 1e3 ? `${(n / 1e3).toFixed(1)}k` : String(Math.round(n));
  const typing = () => { const a = document.activeElement; return a && /^(INPUT|TEXTAREA|SELECT)$/.test(a.tagName); };

  let state = { enabled: null, offReason: '', keys: [], budgets: [], upstreams: [], usage: [], audit: [], readOk: null, readAt: 0 };
  let mintedSecret = '', feedback = { key: '', person: '', budget: '', backup: '' }, usageBy = 'person', drawnDigest = '';

  async function read() {
    const kr = await API.get('/api/server/keys');
    state.readOk = kr.ok || kr.status === 409;
    if (!kr.ok) {
      if (kr.status === 409) { state.enabled = false; state.offReason = why(kr); }
      paintHead(); drawIfChanged(); return;
    }
    const [br, ur, us, ar] = await Promise.all([
      API.get('/api/server/budgets'),
      API.get('/api/server/upstreams'),
      API.get('/api/server/usage?by=' + enc(usageBy) + '&since=7d'),
      API.get('/api/server/audit?tail=50'),
    ]);
    state.enabled = true;
    state.keys = (kr.body && kr.body.keys) || [];
    state.budgets = (br.ok && br.body && br.body.budgets) || [];
    state.upstreams = (ur.ok && ur.body && ur.body.upstreams) || [];
    state.usage = (us.ok && us.body && us.body.groups) || [];
    state.audit = (ar.ok && ar.body && ar.body.entries) || [];
    state.readAt = Date.now();
    paintHead(); drawIfChanged();
  }

  function say(el, v) { if (el.textContent !== v) el.textContent = v; }

  function paintHead() {
    const ey = $('#srvEyebrow'), st = $('#srvStatus');
    const liveKeys = state.keys.filter(k => !k.revoked_at).length;
    const healthy = state.upstreams.filter(u => u.healthy).length;
    say(ey, state.enabled === false ? 'Server mode off' : state.readOk === false && !state.readAt ? 'splice is not answering' : `${Deck.count(liveKeys, 'live key')} · ${healthy}/${state.upstreams.length} upstreams`);
    const lampState = state.readOk === false ? 'stalled' : state.enabled === false ? 'idle' : 'working';
    if (st._state !== lampState) { st._state = lampState; st.replaceChildren(Deck.lamp(lampState), h('span'), h('span', 'faint', '·'), h('span')); }
    say(st.children[1], state.readOk === false ? 'Not answering' : state.enabled === false ? 'Not configured' : 'Serving');
    say(st.lastElementChild, Deck.clock());
  }

  function drawIfChanged() {
    const digest = JSON.stringify({
      enabled: state.enabled, offReason: state.offReason, readOk: state.readOk,
      keys: state.keys, budgets: state.budgets, upstreams: state.upstreams,
      usage: state.usage, audit: state.audit, mintedSecret, feedback, usageBy,
    });
    if (digest === drawnDigest) return;
    drawnDigest = digest;
    draw();
  }

  function draw() {
    const body = $('#srvBody');
    if (state.enabled === false) {
      const card = h('section', 'srv-off');
      card.append(h('h2', '', 'Team server mode is not active'), h('p', '', state.offReason || 'Add a [server] table to splice.toml and restart splice to serve a team.'));
      body.replaceChildren(card); return;
    }
    if (state.enabled !== true) {
      body.replaceChildren(h('p', 'faint', state.readOk === false ? 'splice is not answering' : 'Loading server state…'));
      return;
    }
    const grid = h('div', 'srv-grid');
    grid.append(keysPanel(), peoplePanel(), budgetsPanel(), upstreamsPanel(), usagePanel(), auditPanel());
    body.replaceChildren(summaryRow(), grid);
  }

  function summaryRow() {
    const row = h('div', 'srv-summary');
    const liveKeys = state.keys.filter(k => !k.revoked_at).length;
    const healthy = state.upstreams.filter(u => u.healthy).length;
    const over = state.budgets.filter(b => b.over).length;
    const cost = state.usage.reduce((n, g) => n + (Number(g.cost_usd) || 0), 0);
    [[String(liveKeys), 'Live keys'], [`${healthy}/${state.upstreams.length}`, 'Healthy upstreams'], [usd(cost), '7d spend'], [String(over), 'Budgets over']].forEach(([v, label]) => {
      const box = h('div', 'srv-stat'); box.append(h('b', '', v), h('span', '', label)); row.append(box);
    });
    return row;
  }

  function keysPanel() {
    const p = h('section', 'srv-panel');
    const head = h('div', 'srv-panel-head'); head.append(h('h2', '', 'Keys'));
    const bk = h('button', 'btn quiet press', 'Backup now'); bk.type = 'button';
    bk.addEventListener('click', async () => {
      const res = await API.post('/api/server/backup', {});
      feedback.backup = res.ok ? `Backed up ${res.body.files || 0} files` : why(res);
      drawIfChanged();
    });
    head.append(bk); p.append(head);
    if (feedback.backup) p.append(h('div', 'srv-note', feedback.backup));

    const form = h('form', 'srv-form');
    const email = h('input', 'srv-in'); email.placeholder = 'person@team.example'; email.setAttribute('aria-label', 'Person email');
    const label = h('input', 'srv-in'); label.placeholder = 'Key label (optional)'; label.setAttribute('aria-label', 'Key label');
    const create = h('button', 'btn primary press', 'Mint key'); create.type = 'submit';
    form.addEventListener('submit', async e => {
      e.preventDefault();
      if (!email.value.trim()) return;
      const payload = { person: email.value.trim() };
      if (label.value.trim()) payload.name = label.value.trim();
      const res = await API.post('/api/server/keys', payload);
      if (res.ok && res.body && res.body.key) { mintedSecret = res.body.key; feedback.key = ''; await read(); }
      else { feedback.key = why(res); drawIfChanged(); }
    });
    form.append(email, label, create); p.append(form);

    if (mintedSecret) {
      const ban = h('div', 'srv-banner');
      const dismiss = h('button', 'btn quiet press', 'Dismiss'); dismiss.type = 'button';
      dismiss.addEventListener('click', () => { mintedSecret = ''; drawIfChanged(); });
      ban.append(h('span', '', `New key (shown once): ${mintedSecret}`), dismiss); p.append(ban);
    }
    if (feedback.key) p.append(h('div', 'srv-note is-err', feedback.key));

    const list = h('div', 'srv-list');
    if (!state.keys.length) list.append(h('p', 'faint', 'No keys minted yet'));
    state.keys.forEach(k => {
      const r = h('div', 'srv-row'), main = h('div', 'srv-row-main'), acts = h('div', 'srv-row-acts');
      const title = h('div', 'srv-row-title'); title.append(Deck.lamp(k.revoked_at ? 'ended' : 'ready'), `${k.person} · ${k.prefix}`);
      main.append(title, h('div', 'srv-row-sub', [k.name, k.source, k.revoked_at ? 'revoked' : 'active'].filter(Boolean).join(' · ')));
      if (!k.revoked_at) {
        const rev = h('button', 'btn quiet press', 'Revoke'); rev.type = 'button';
        rev.addEventListener('click', async () => { await API.del('/api/server/keys/' + enc(k.prefix)); await read(); });
        acts.append(rev);
      } else acts.append(h('span', 'srv-badge', 'Revoked'));
      r.append(main, acts); list.append(r);
    });
    p.append(list); return p;
  }

  function peoplePanel() {
    const p = h('section', 'srv-panel');
    const head = h('div', 'srv-panel-head'); head.append(h('h2', '', 'People & Teams')); p.append(head);
    const pf = h('form', 'srv-form');
    const person = h('input', 'srv-in'); person.placeholder = 'person@team.example'; person.setAttribute('aria-label', 'Member email');
    const team = h('input', 'srv-in'); team.placeholder = 'Team'; team.setAttribute('aria-label', 'Member team');
    const inflight = h('input', 'srv-in'); inflight.placeholder = 'In-flight cap'; inflight.setAttribute('aria-label', 'In-flight cap');
    const saveP = h('button', 'btn press', 'Set person'); saveP.type = 'submit';
    pf.addEventListener('submit', async e => {
      e.preventDefault();
      if (!person.value.trim()) return;
      const body = {};
      if (team.value.trim()) body.team = team.value.trim();
      if (inflight.value.trim()) body.inflight = Number(inflight.value.trim());
      const res = await API.put('/api/server/people/' + enc(person.value.trim()), body);
      feedback.person = res.ok ? `Updated ${person.value.trim()}` : why(res);
      await read();
    });
    pf.append(person, team, inflight, saveP); p.append(pf);

    const tf = h('form', 'srv-form');
    const tName = h('input', 'srv-in'); tName.placeholder = 'Team name'; tName.setAttribute('aria-label', 'Team name');
    const cmds = h('input', 'srv-in'); cmds.placeholder = 'Commands (comma-separated)'; cmds.setAttribute('aria-label', 'Team commands');
    const saveT = h('button', 'btn quiet press', 'Set team'); saveT.type = 'submit';
    tf.addEventListener('submit', async e => {
      e.preventDefault();
      if (!tName.value.trim()) return;
      const list = cmds.value.split(',').map(x => x.trim()).filter(Boolean);
      const res = await API.put('/api/server/teams/' + enc(tName.value.trim()), { commands: list });
      feedback.person = res.ok ? `Updated team ${tName.value.trim()}` : why(res);
      await read();
    });
    tf.append(tName, cmds, saveT); p.append(tf);
    if (feedback.person) p.append(h('div', 'srv-note', feedback.person));

    const people = [...new Set(state.keys.map(k => k.person))].sort();
    const list = h('div', 'srv-list');
    if (!people.length) list.append(h('p', 'faint', 'No members yet'));
    people.forEach(email => {
      const active = state.keys.filter(k => k.person === email && !k.revoked_at).length;
      const r = h('div', 'srv-row'), main = h('div', 'srv-row-main'), acts = h('div', 'srv-row-acts');
      main.append(h('div', 'srv-row-title', email), h('div', 'srv-row-sub', Deck.count(active, 'active key')));
      if (active > 0) {
        const off = h('button', 'btn quiet press', 'Offboard'); off.type = 'button';
        off.addEventListener('click', async () => { await API.del('/api/server/people/' + enc(email) + '/keys'); await read(); });
        acts.append(off);
      }
      r.append(main, acts); list.append(r);
    });
    p.append(list); return p;
  }

  function budgetsPanel() {
    const p = h('section', 'srv-panel');
    const head = h('div', 'srv-panel-head'); head.append(h('h2', '', 'Budgets')); p.append(head);
    const form = h('form', 'srv-form');
    const scope = h('select', 'srv-in'); scope.setAttribute('aria-label', 'Budget scope');
    ['person', 'team'].forEach(v => { const o = h('option', '', v); o.value = v; scope.append(o); });
    const name = h('input', 'srv-in'); name.placeholder = 'Email or team'; name.setAttribute('aria-label', 'Budget target');
    const daily = h('input', 'srv-in'); daily.placeholder = 'Daily USD'; daily.setAttribute('aria-label', 'Daily USD');
    const save = h('button', 'btn press', 'Set budget'); save.type = 'submit';
    form.addEventListener('submit', async e => {
      e.preventDefault();
      if (!name.value.trim() || !daily.value.trim()) return;
      const res = await API.put('/api/server/budgets', { scope: scope.value, name: name.value.trim(), daily_usd: Number(daily.value.trim()), action: 'block' });
      feedback.budget = res.ok ? `Saved budget for ${name.value.trim()}` : why(res);
      await read();
    });
    form.append(scope, name, daily, save); p.append(form);
    if (feedback.budget) p.append(h('div', 'srv-note', feedback.budget));

    const list = h('div', 'srv-list');
    if (!state.budgets.length) list.append(h('p', 'faint', 'No budgets configured'));
    state.budgets.forEach(b => {
      const r = h('div', 'srv-row'), main = h('div', 'srv-row-main');
      main.append(h('div', 'srv-row-title', `${b.scope} · ${b.name}`), h('div', 'srv-row-sub', `Today ${usd(b.spent_today_usd)} / ${usd(b.daily_usd)} · Month ${usd(b.spent_month_usd)} / ${usd(b.monthly_usd)} · ${b.action}`));
      const badge = h('span', 'srv-badge' + (b.over ? ' is-over' : ''), b.over ? 'Over' : 'Within');
      r.append(main, badge); list.append(r);
    });
    p.append(list); return p;
  }

  function upstreamsPanel() {
    const p = h('section', 'srv-panel');
    const head = h('div', 'srv-panel-head'); head.append(h('h2', '', 'Upstreams')); p.append(head);
    const list = h('div', 'srv-list');
    if (!state.upstreams.length) list.append(h('p', 'faint', 'No pooled upstreams configured'));
    state.upstreams.forEach(u => {
      const r = h('div', 'srv-row'), main = h('div', 'srv-row-main');
      const title = h('div', 'srv-row-title'); title.append(Deck.lamp(u.healthy ? 'serving' : 'stalled'), `${u.provider} / ${u.name}`);
      main.append(title, h('div', 'srv-row-sub', u.url || ''));
      r.append(main, h('span', 'srv-badge', `${u.inflight || 0} in flight`)); list.append(r);
    });
    p.append(list); return p;
  }

  function usagePanel() {
    const p = h('section', 'srv-panel');
    const head = h('div', 'srv-panel-head'); head.append(h('h2', '', 'Usage · 7 d'));
    const sw = h('div', 'srv-row-acts');
    ['person', 'team', 'model'].forEach(by => {
      const b = h('button', 'btn ' + (usageBy === by ? 'primary' : 'quiet') + ' press', by); b.type = 'button';
      b.addEventListener('click', () => { usageBy = by; read(); }); sw.append(b);
    });
    head.append(sw); p.append(head);
    const list = h('div', 'srv-list');
    if (!state.usage.length) list.append(h('p', 'faint', 'No usage recorded in the last 7 days'));
    state.usage.forEach(g => {
      const r = h('div', 'srv-row'), main = h('div', 'srv-row-main');
      const toks = (g.prompt_tokens || 0) + (g.completion_tokens || 0);
      main.append(h('div', 'srv-row-title', g.group || '—'), h('div', 'srv-row-sub', `${Deck.count(g.requests || 0, 'request')} · ${kfmt(toks)} tok`));
      r.append(main, h('span', 'srv-badge', usd(g.cost_usd))); list.append(r);
    });
    p.append(list); return p;
  }

  function auditPanel() {
    const p = h('section', 'srv-panel');
    const head = h('div', 'srv-panel-head'); head.append(h('h2', '', 'Admin audit log')); p.append(head);
    const list = h('div', 'srv-list');
    if (!state.audit.length) list.append(h('p', 'faint', 'No admin actions recorded yet'));
    [...state.audit].reverse().forEach(a => {
      const r = h('div', 'srv-row'), main = h('div', 'srv-row-main');
      main.append(h('div', 'srv-row-title', `${a.action} · ${a.target}`), h('div', 'srv-row-sub', `${a.actor} · ${a.ts}`));
      r.append(main); list.append(r);
    });
    p.append(list); return p;
  }

  let reading = false;
  const tick = async () => { if (reading || typing() || document.hidden || document.body.classList.contains('locked')) return; reading = true; try { await read(); } finally { reading = false; } };
  SP.ready.then(tick);
  setInterval(tick, READ_MS);
  addEventListener('splice:signed-in', tick);
})();
