/* Providers: the rows a command's glass holds for what the old Models page showed and changed about one command, for
   deck-providers.js (window.PROVIDER_COMMAND). Each is a panel row of the kit (.lr), saved at once where it is a
   choice and by its own Save where it is typed, saying what splice answered (Saving, Applied, At restart, Not saved).

   - What the command needs (doctor's check for it: not started, signed out, no key, command not found) with the fix
     splice offers: Start (POST /api/heads/{key}/start or /api/doctor/fix/{id}), Sign in (api.js's SIGNIN steps on
     /api/auth/{key}/login), Add key (PUT /api/keys/{env}), Check again.
   - Context window: per model the command serves, the provider's own row in splice.toml (which every command on that
     provider shares), else the command's own window; with the window Claude Code's sessions on it count against.
   - System prompt: Append, Replace or Strip, with its text here or the file it is read from.
   - Prices: per model, input, output and cached input per 1M tokens, the command's own card ([heads.KEY.rates]).
   - Projects: the system prompt the command uses inside one project ([projects."ROOT".heads.KEY]).
   - Where it goes: the provider's address and the command's port.
   - Sign-in, what it shares with Claude Code, its behaviours, whether it runs on this computer, and a test request
     (deck-providers-setup.js).
   - Logs: the command's own lines of splice's log (GET /api/logs/{key}), searched and paged, never cut off.
   Every splice.toml change is one PUT /api/topology of the whole tree with only that change in it, as Models wrote it.

   Reads, once a minute and when a glass opens: /api/doctor, /api/projects, /api/sessions. */
window.PROVIDER_COMMAND = (() => {
  'use strict';
  const { h } = Deck, enc = encodeURIComponent;
  const MINUTE = 60000, LOG_PAGE = 40, LOG_TAIL = 10000;
  const why = res => (res.status === 0 ? 'splice is not answering' : (res.body && (res.body.error || res.body.reason)) || 'splice answered ' + res.status);
  const win = c => (c == null ? '—' : c >= 1e6 ? `${+(c / 1e6).toFixed(2)}M` : `${Math.round(c / 1000)}k`);
  const STOPS = [32768, 65536, 128000, 200000, 256000, 400000, 500000, 1000000, 2000000];
  const MODES = [{ v: 'append', label: 'Append', sub: 'Added after Claude Code’s own system prompt' },
    { v: 'replace', label: 'Replace', sub: 'Sent instead of Claude Code’s own' }, { v: 'strip', label: 'Strip', sub: 'Removes the paragraphs that match' }];
  const WIRE = { 'openai-responses': 'OpenAI Responses', 'openai-chat': 'OpenAI Chat', 'anthropic-messages': 'Anthropic Messages', 'anthropic-passthrough': 'Anthropic pass-through' };
  const AUTH = { client: 'Claude Code’s own sign-in', 'api-key': 'API key', none: 'No sign-in' };
  const homeRel = p => String(p || '').replace(/^(\/home|\/Users)\/[^/]+/, '~');
  const base = p => String(p || '').split('/').filter(Boolean).pop() || p;

  /* ---------- reads ---------- */
  let doctor = null, projects = null, sessions = null, readAt = 0;
  async function read(force) {
    if (!force && Date.now() - readAt < MINUTE) return false;
    readAt = Date.now();
    const [d, pr, se] = await Promise.all(['/api/doctor', '/api/projects', '/api/sessions'].map(p => API.get(p)));
    doctor = d.ok && d.body && Array.isArray(d.body.checks) ? d.body.checks : doctor;
    projects = pr.ok && pr.body && Array.isArray(pr.body.projects) ? pr.body.projects : projects;
    sessions = se.ok && se.body && Array.isArray(se.body.sessions) ? se.body.sessions : sessions;
    return true;
  }

  /* what splice offers for the command, from doctor's checks: the way the old Models page and every page chose it */
  function needOf(p) {
    const mine = (doctor || []).filter(x => x.id === `auth/${p.key}` || x.id === `daemon/head ${p.key}` || (x.id === 'installation/wrapper' && x.command === p.cmd));
    const bad = mine.find(x => x.status === 'fail' || x.status === 'warn');
    const keyed = /key/i.test(p.auth || '');
    if (!p.running) return { word: 'Not started', fix: bad && bad.fix_id ? { id: bad.fix_id, label: 'Start' } : { start: true, label: 'Start' } };
    if (p.server && !p.server.reachable) return { word: 'Server not reachable', fix: { recheck: true, label: 'Check again' } };
    if (!bad) return null;
    if (bad.id.startsWith('auth/')) {
      const refused = bad.cause === 'provider_refused' || bad.cause === 'login_rejected';
      return { word: refused ? 'Credentials refused' : keyed ? 'No key' : 'Signed out', fix: keyed ? { key: true, label: refused ? 'Replace key' : 'Add key' } : { signin: true, label: 'Sign in' } };
    }
    if (bad.id === 'installation/wrapper') return { word: 'Command not found', fix: bad.fix_id ? { id: bad.fix_id, label: 'Restore' } : null };
    return { word: 'Not started', fix: bad.fix_id ? { id: bad.fix_id, label: 'Start' } : null };
  }

  /* ---------- the topology, one change at a time ---------- */
  const clone = t => JSON.parse(JSON.stringify(t));
  const headOf = (t, p) => (t && t.heads && t.heads[p.key]) || null;
  const provOf = (t, p) => { const hd = headOf(t, p); return hd && t.providers ? t.providers[hd.provider] || null : null; };
  /** PUT /api/topology with [change] made to a copy of the tree: { ok, restart, why } as the kit's commit reads it */
  async function change(kit, p, what, edit) {
    const t = kit.topology();
    if (!t || !headOf(t, p)) return { ok: false, why: 'splice.toml was not read' };
    const next = clone(t);
    edit(next, headOf(next, p), provOf(next, p));
    const res = await API.put('/api/topology', { topology: next });
    if (!res.ok || (res.body && res.body.ok === false)) {
      const found = (res.body && res.body.findings) || [];
      if (found.length) console.warn(`providers: ${what} for ${p.cmd} refused: ${found.map(x => x.message).join('; ')}`);
      return { ok: false, why: found.length ? 'Config error' : why(res) };
    }
    kit.setTopology(next);
    const restart = !!(res.body && res.body.restart_required);
    if (restart) kit.remember(p, what, null);
    return { ok: true, restart };
  }
  /** the models a window or a price is shown for: what the tiers and the default select, else a short roster whole */
  function shownModels(p) {
    const ids = [p.defaultModel, ...Object.values(p.tiers || {})].filter(Boolean);
    const picked = p.models.filter(m => ids.includes(m.id));
    return picked.length ? picked.slice(0, 4) : p.models.length <= 3 ? p.models : [];
  }
  const others = (t, p) => { const hd = headOf(t, p); return hd ? Object.entries(t.heads || {}).filter(([k, x]) => k !== p.key && x && x.provider === hd.provider).map(([k]) => k) : []; };
  const notRead = (ctl, word) => ctl.append(h('span', 'faint', word || 'splice did not answer'));
  const saveBtn = (word = 'Save') => { const b = h('button', 'btn press', word); b.type = 'button'; return b; };
  const typed = (cls, value, ph) => { const i = h(cls === 'area' ? 'textarea' : 'input', 'lr-in'); i.value = value || ''; if (ph) i.placeholder = ph; i.spellcheck = false; i.autocomplete = 'off'; i.addEventListener('keydown', e => e.stopPropagation()); return i; };

  /* ---------- what the command needs, and the fix ---------- */
  const signs = new Map(), keyOpen = new Set();
  function needRow(el, p, kit) {
    const need = needOf(p), s = signs.get(p.key);
    if (!need && !s) return;
    const { r, sv, ctl } = kit.row('fix', need ? need.word : 'Signing in', need && need.fix && need.fix.signin ? 'Sign in to the provider' : '');
    r.classList.add('is-need');
    const fix = need && need.fix;
    if (fix && !s) {
      const b = saveBtn(fix.label); b.classList.add('primary'); ctl.append(b);
      b.addEventListener('click', () => {
        if (fix.signin) return signIn(p, kit);
        if (fix.key) { keyOpen.add(p.key); return kit.refill(); }
        kit.commit(sv, async () => {
          if (fix.recheck) { await kit.reread(); return { ok: !(p.server && !p.server.reachable), why: 'Still not answering' }; }
          const res = fix.id ? await API.post('/api/doctor/fix/' + enc(fix.id), {}) : await API.post('/api/heads/' + enc(p.key) + '/start', {});
          if (res.ok) { await read(true); await kit.reread(); }
          return Object.assign(res, { word: 'Done' });
        });
      });
    }
    if (fix && fix.key && keyOpen.has(p.key)) {
      const prov = provOf(kit.topology(), p), env = prov && prov.auth && prov.auth.env;
      const i = typed('in', '', env ? `Key for ${env}` : 'API key'); i.type = 'password';
      const go = saveBtn('Save key');
      const send = () => kit.commit(sv, async () => {
        if (!env) return { ok: false, why: 'No key variable in splice.toml' };
        if (!i.value.trim()) return { ok: false, why: 'Enter the key' };
        const res = await API.put('/api/keys/' + enc(env), { value: i.value.trim() });
        if (res.ok) { keyOpen.delete(p.key); await read(true); await kit.reread(); }
        return Object.assign(res, { word: 'Saved' });
      });
      go.addEventListener('click', send);
      i.addEventListener('keydown', e => { if (e.key === 'Enter') { e.preventDefault(); send(); } });
      const sub = h('div', 'lr-ctl'); sub.append(i, go); r.append(sub, h('div', 'faint small', 'Stored in the keyring, never shown again'));
    }
    el.append(r);
    if (s && s.state !== 'left' && s.state !== 'done') {
      const box = Deck.signRows(s);
      box.addEventListener('click', e => {
        const a = e.target.closest('[data-act]'); if (!a) return;
        if (a.dataset.act === 'signin') signIn(p, kit);
        if (a.dataset.act === 'cancel-signin') SIGNIN.cancel(s, s.on).then(over => { if (over) signs.delete(p.key); kit.refill(); });
      });
      el.append(box);
    }
  }
  function signIn(p, kit) {
    const s = signs.get(p.key) || {};
    s.how = { start: '/api/auth/' + enc(p.key) + '/login', body: {}, poll: p.key };
    s.on = {
      held: () => signs.get(p.key) === s,
      draw: () => kit.refill(),
      done: async () => { signs.delete(p.key); await read(true); await kit.reread(); kit.refill(); },
      failed: () => kit.refill(),
    };
    signs.set(p.key, s);
    SIGNIN.start(s, s.on);
    kit.refill();
  }

  /* ---------- context window ---------- */
  function windowRow(el, p, kit) {
    const t = kit.topology(), share = t ? others(t, p) : [];
    const { r, sv, ctl } = kit.row('window', 'Context window', share.length ? `The most tokens a request can hold · shared with ${share.join(', ')}` : 'The most tokens a request can hold');
    if (!t || !headOf(t, p)) notRead(ctl);
    else {
      ctl.remove();
      const box = h('div', 'tiers'), hd = headOf(t, p), prov = provOf(t, p), list = shownModels(p);
      const stopsFor = (now, max) => [...new Set([...STOPS.filter(v => !max || v <= max), now, max].filter(Boolean))].sort((a, b) => a - b)
        .map(v => ({ v, label: win(v), sub: v === max ? 'The most the model takes' : '' }));
      const pick = (label, now, max, save) => {
        const cell = h('div'); cell.append(h('div', 'eyebrow', label));
        let c; c = Deck.choice(stopsFor(now, max), now, v => kit.commit(sv, () => save(v), () => c.set(now)), { max: 1, label: `${label}: context window` });
        cell.append(c); box.append(cell);
      };
      if (list.length) list.forEach(m => {
        const own = prov && (prov.models || []).find(x => x.id === m.id);
        pick(m.name, m.context, m.maxContext, v => change(kit, p, `Context window: ${m.name}`, (n, nh, np) => {
          const row = np && (np.models || []).find(x => x.id === m.id);
          if (row) row.context_window = v; else nh.context_window = v;
        }));
        if (!own) box.lastChild.append(h('small', 'faint', 'The command’s own window'));
      });
      else pick('Every model', hd.context_window || null, null, v => change(kit, p, 'Context window', (n, nh) => { nh.context_window = v; }));
      r.append(box);
    }
    // the window Claude Code itself counts against, as the sessions on this command reported it
    const seen = (sessions || []).filter(x => x.head === p.key && x.client_context_window > 0);
    if (seen.length) {
      const by = new Map(); seen.forEach(x => by.set(x.client_context_window, (by.get(x.client_context_window) || 0) + 1));
      const k = h('div', 'kvs');
      [...by].sort((a, b) => b[1] - a[1]).forEach(([w, n]) => { const d = h('div'); d.append(h('span', '', `Claude Code counts against · ${n} ${n === 1 ? 'session' : 'sessions'}`), h('b', '', win(w))); k.append(d); });
      r.append(k);
    }
    el.append(r);
  }

  /* ---------- a system prompt: the command's own, and one inside a project ---------- */
  function promptCtl(r, sv, kit, p, layer, what, edit) {
    const mode = layer.system_prompt_mode || 'append', file = layer.system_prompt_file || '';
    let c; c = Deck.choice(MODES, mode, v => kit.commit(sv, () => change(kit, p, what, (n, nh) => { const l = edit(n, nh); l.system_prompt_mode = v; }), () => c.set(mode)), { label: `${what}: what it does` });
    const top = h('div', 'lr-ctl'); top.append(c); r.append(top);
    if (file) {
      const k = h('div', 'kvs'), d = h('div'); d.append(h('span', '', 'Read from'), h('b', '', homeRel(file))); k.append(d); r.append(k);
      return;
    }
    const ta = typed('area', layer.system_prompt || '', mode === 'strip' ? 'The paragraphs to remove' : 'Nothing added'); ta.rows = 3;
    const go = saveBtn();
    go.addEventListener('click', () => kit.commit(sv, () => change(kit, p, what, (n, nh) => {
      const l = edit(n, nh); const v = ta.value;
      if (v.trim()) l.system_prompt = v; else delete l.system_prompt;
    })));
    const box = h('div', 'lr-sub'); box.append(ta); const acts = h('div', 'lr-acts'); acts.append(go); r.append(box, acts);
  }
  function promptRow(el, p, kit) {
    const t = kit.topology(), hd = headOf(t, p);
    const { r, sv, ctl } = kit.row('prompt', 'System prompt', 'What this command sends Claude Code’s model first');
    if (!hd) { notRead(ctl); el.append(r); return; }
    ctl.remove();
    promptCtl(r, sv, kit, p, hd, 'System prompt', (n, nh) => nh);
    el.append(r);
  }
  function projectsRow(el, p, kit) {
    const t = kit.topology();
    const { r, sv, ctl } = kit.row('projects', 'Projects', 'A system prompt of its own inside one project');
    if (!t || !headOf(t, p)) { notRead(ctl); el.append(r); return; }
    const layers = Object.entries(t.projects || {}).filter(([, x]) => x && x.heads && x.heads[p.key]);
    const nameOf = root => { const pr = (projects || []).find(x => x.root === root); return pr ? pr.name : base(root); };
    layers.forEach(([root, x]) => {
      const sub = h('div', 'lr-sub pc-proj');
      const top = h('div', 'pc-proj-h'); top.append(h('b', '', nameOf(root)), h('span', 'faint small', homeRel(root)));
      const rm = Deck.iconBtn('close', `Remove ${nameOf(root)}`);
      rm.addEventListener('click', () => kit.commit(sv, () => change(kit, p, `Projects: ${nameOf(root)}`, n => {
        delete n.projects[root].heads[p.key];
        if (!Object.keys(n.projects[root].heads).length) delete n.projects[root].heads;
        if (!Object.keys(n.projects[root]).length) delete n.projects[root];
      })));
      top.append(rm); sub.append(top);
      promptCtl(sub, sv, kit, p, x.heads[p.key], `Projects: ${nameOf(root)}`, n => n.projects[root].heads[p.key]);
      r.append(sub);
    });
    const free = (projects || []).filter(x => x.root && !layers.some(([root]) => root === x.root));
    if (free.length) {
      const add = saveBtn('Add project'); add.classList.add('quiet'); add.setAttribute('aria-haspopup', 'menu');
      add.addEventListener('click', () => Deck.openMenu(add, free.map(x => ({ label: x.name, sub: homeRel(x.root), run: () => kit.commit(sv, () => change(kit, p, `Projects: ${x.name}`, n => {
        n.projects = n.projects || {}; n.projects[x.root] = n.projects[x.root] || {}; n.projects[x.root].heads = n.projects[x.root].heads || {};
        n.projects[x.root].heads[p.key] = { system_prompt_mode: 'append' };
      })) })), 'down'));
      ctl.append(add);
    } else if (!layers.length) notRead(ctl, projects ? 'No projects seen yet' : 'Projects did not load');
    el.append(r);
  }

  /* ---------- prices, per model, the command's own card ---------- */
  function pricesRow(el, p, kit) {
    const t = kit.topology(), list = shownModels(p);
    const { r, sv, ctl } = kit.row('prices', 'Prices', 'Per 1M tokens: input, output, cached input');
    if (!t || !headOf(t, p)) { notRead(ctl); el.append(r); return; }
    if (!list.length) { notRead(ctl, 'No models read'); el.append(r); return; }
    ctl.remove();
    list.forEach(m => {
      const card = (headOf(t, p).rates || {})[m.id] || null, now = card ? { input: card.input, output: card.output, cached: card.cache_read } : m.rates || {};
      const line = h('div', 'lr-ctl pc-price'); line.append(h('span', 'pc-price-nm', m.name));
      const ins = ['input', 'output', 'cached'].map(k => { const i = typed('in', now[k] == null ? '' : String(now[k]), k === 'input' ? 'Input' : k === 'output' ? 'Output' : 'Cached'); i.inputMode = 'decimal'; i.setAttribute('aria-label', `${m.name}: ${k} per 1M tokens`); line.append(i); return i; });
      const go = saveBtn();
      go.addEventListener('click', () => kit.commit(sv, async () => {
        const [i, o, c] = ins.map(x => x.value.trim());
        if (!(Number(i) >= 0) || !(Number(o) >= 0) || i === '' || o === '' || (c !== '' && !(Number(c) >= 0))) return { ok: false, why: 'Enter input and output as numbers' };
        return change(kit, p, `Prices: ${m.name}`, (n, nh) => {
          nh.rates = nh.rates || {};
          const was = nh.rates[m.id] || {};
          nh.rates[m.id] = { ...was, input: Number(i), output: Number(o), cache_read: c === '' ? Number(i) : Number(c) };
        });
      }));
      line.append(go); r.append(line);
    });
    el.append(r);
  }

  /* ---------- where it goes: the provider's address and the command's port (editable once splice.toml is read), and
     what the command is: its name, version, wire and sign-in ---------- */
  function whereRow(el, p, kit) {
    const t = kit.topology(), hd = headOf(t, p), prov = provOf(t, p), share = hd ? others(t, p) : [];
    const { r, sv, ctl } = kit.row('where', 'Where it goes', prov && share.length ? `The address is shared with ${share.join(', ')}` : '');
    if (hd) {
      const url = prov ? typed('in', prov.base_url || '', 'https://…') : null, port = typed('in', hd.port == null ? (p.port == null ? '' : String(p.port)) : String(hd.port), 'Port');
      if (url) url.setAttribute('aria-label', 'Provider address');
      port.setAttribute('aria-label', 'Port'); port.inputMode = 'numeric'; port.classList.add('pc-port');
      const go = saveBtn();
      go.addEventListener('click', () => kit.commit(sv, async () => {
        const u = url ? url.value.trim() : null, n = port.value.trim();
        if (url && !/^https?:\/\/[^/\s]+/.test(u)) return { ok: false, why: 'Not an http or https address' };
        if (n && !(Number.isInteger(Number(n)) && Number(n) > 0 && Number(n) < 65536)) return { ok: false, why: 'Not a port' };
        return change(kit, p, 'Where it goes', (x, nh, np) => { if (np && u) np.base_url = u; if (n) nh.port = Number(n); });
      }));
      if (url) ctl.append(url);
      ctl.append(port, go);
    } else ctl.remove();
    const k = h('div', 'kvs');
    [['Command', p.cmd], hd ? null : ['Port', p.port == null ? '—' : String(p.port)], ['Version', p.version || '—'], ['Wire', WIRE[p.dialect] || p.dialect || '—'],
      ['Signs in with', AUTH[p.auth] || p.auth || '—'], prov && prov.auth && prov.auth.env ? ['Key variable', prov.auth.env] : null,
      hd && hd.discovery_prefix ? ['Model ids start with', hd.discovery_prefix] : null]
      .filter(Boolean).forEach(([a, b]) => { const d = h('div'); d.append(h('span', '', a), h('b', '', b)); k.append(d); });
    r.append(k);
    el.append(r);
  }

  /* ---------- logs: the command's own lines, searched, paged back to the first line read ---------- */
  const logs = new Map();   // command key -> { open, lines, path, why, q, back }
  async function readLog(p, kit) {
    const L = logs.get(p.key);
    L.lines = null; kit.refill();
    const res = await API.get('/api/logs/' + enc(p.key) + '?tail=' + LOG_TAIL);
    const raw = res.ok && res.body && Array.isArray(res.body.lines) ? res.body.lines : [];
    const unread = raw.find(l => String(l).startsWith('[logs unavailable:'));
    L.path = res.ok && res.body ? res.body.path || null : null;
    L.why = !res.ok ? 'Log not read' : unread ? unread.slice(1, -1).replace(/^./, x => x.toUpperCase()) : null;
    L.lines = raw.map(l => { const m = /^\[(\d{4}-\d\d-\d\d \d\d:\d\d:\d\d)(?:\.\d{3})?\]\s*(?:\[([^\]]+)\]\s*)?(.*)$/.exec(l); return m ? { at: m[1], tag: m[2] || '', text: m[3] } : { at: '', tag: '', text: String(l) }; })
      .filter(l => l.tag === p.key);
    L.back = 0;
    kit.refill();
  }
  function logRow(el, p, kit) {
    const L = logs.get(p.key) || { open: false, lines: null, path: null, why: null, q: '', back: 0 };
    logs.set(p.key, L);
    const { r, ctl } = kit.row('logs', 'Logs', L.path ? base(L.path) : 'This command’s lines in splice’s log');
    const tog = saveBtn(L.open ? 'Hide' : 'Show'); tog.classList.add('quiet'); tog.setAttribute('aria-expanded', String(L.open));
    tog.addEventListener('click', () => { L.open = !L.open; if (L.open && !L.lines) readLog(p, kit); else kit.refill(); });
    ctl.append(tog);
    if (L.open) {
      const again = saveBtn('Read again'); again.classList.add('quiet'); again.addEventListener('click', () => readLog(p, kit)); ctl.append(again);
      const box = h('div', 'lr-sub');
      const q = typed('in', L.q, 'Search the log'); q.setAttribute('aria-label', 'Search the log');
      q.addEventListener('input', () => { L.q = q.value; L.back = 0; paint(); });
      box.append(q);
      const list = h('ol', 'pc-log'), foot = h('div', 'lr-acts');
      box.append(list, foot);
      const paint = () => {
        list.replaceChildren(); foot.replaceChildren();
        if (L.why) { list.append(h('li', 'faint', L.why)); return; }
        if (!L.lines) { list.append(h('li', 'faint', 'Reading')); return; }
        const s = L.q.trim().toLowerCase(), hits = s ? L.lines.filter(l => l.text.toLowerCase().includes(s)) : L.lines;
        if (!hits.length) { list.append(h('li', 'faint', s ? 'No lines match' : 'Nothing logged yet')); return; }
        const end = hits.length - Math.min(L.back, hits.length - 1), start = Math.max(0, end - LOG_PAGE);
        hits.slice(start, end).forEach(l => { const li = h('li'); li.append(h('time', '', l.at.slice(11)), h('span', '', l.text)); li.title = l.text; list.append(li); });
        foot.append(h('span', 'faint small', `${start + 1}–${end} of ${hits.length} lines`));
        if (start > 0) { const b = saveBtn(`${Math.min(LOG_PAGE, start)} earlier`); b.classList.add('quiet'); b.addEventListener('click', () => { L.back += LOG_PAGE; paint(); }); foot.append(b); }
        if (end < hits.length) {
          const b = saveBtn('Newer'); b.classList.add('quiet'); b.addEventListener('click', () => { L.back = Math.max(0, L.back - LOG_PAGE); paint(); }); foot.append(b);
          const z = saveBtn('Newest'); z.classList.add('quiet'); z.addEventListener('click', () => { L.back = 0; paint(); }); foot.append(z);
        }
      };
      paint();
      r.append(box);
    }
    el.append(r);
  }

  /** the rows after the plain claude command, in the order the glass holds them */
  function rows(el, p, kit) {
    windowRow(el, p, kit);
    promptRow(el, p, kit);
    pricesRow(el, p, kit);
    projectsRow(el, p, kit);
    whereRow(el, p, kit);
    if (window.PROVIDER_SETUP) PROVIDER_SETUP.rows(el, p, kit, { change, typed, saveBtn, headOf, provOf, others, notRead });
    logRow(el, p, kit);
  }
  return { read, top: needRow, rows };
})();
