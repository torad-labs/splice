/* Providers: the approved Night Deck page (prototype providers/final), on splice's live reads. Every command splice runs
   is a part on the field, named by its command ([data-key="<command>"]): its provider, status, requests by hour over 7
   days, median response time, failure rate and API value; local model servers sit apart with their URL and whether they
   answer. Pressing a part grows its glass; Add provider and Compare models are parts of the same field.

   Reads, every 15 seconds while the page is shown and no glass is being typed in: /api/heads, /api/status,
   /api/perf/summary?window=24h, /api/economics, /api/models, /api/models/local, /api/accounts, /api/topology,
   /api/claude-head, /api/add/profiles. Writes go where the old Models page sent them: the default model through
   PATCH /api/config?head=<key> (pinnedModel), the model mapping through PUT /api/topology (the command's model_slots,
   the only place splice keeps them), the plain claude command through POST /api/claude-head/wrap|unwrap, Restart and
   Stop through POST /api/heads/{key}/restart|stop, Remove through DELETE /api/heads/{key}, and a new command through
   /api/add (POST, its key PUT /api/keys/{env}, its sign-in through api.js's SIGNIN, verify, save).

   Its address: providers.html?cmd=<command> opens that command's glass, ?open=compare Compare models, ?open=add Add
   provider. */
(() => {
  'use strict';
  const { $, $$, h, px } = Deck, SP = window.SPLICE, CMD = window.PROVIDER_COMMAND, CMP = window.PROVIDER_COMPARE;
  const pv = $('#pv'), ins = Deck.inspector(pv);
  const enc = encodeURIComponent;
  const READ_MS = 15000, HOUR = 3600000, WEEK_MS = 7 * 24 * HOUR;
  const why = res => (res.status === 0 ? 'splice is not answering' : (res.body && (res.body.error || res.body.reason)) || 'splice answered ' + res.status);
  const n = v => v == null ? '—' : Math.round(v).toLocaleString('en-US');
  const money = v => v == null ? '—' : v >= 10000 ? `$${(v / 1000).toFixed(1)}k` : `$${n(v)}`;
  const secs = ms => ms == null ? '—' : ms >= 60000 ? `${(ms / 60000).toFixed(1)} min` : `${(ms / 1000).toFixed(1)} s`;
  const pct = u => u == null ? '—' : u > 0 && u < 0.01 ? `${(u * 100).toFixed(1)}%` : `${Math.round(u * 100)}%`;
  const ago = t => { const m = (Date.now() - t) / 60000; return m < 60 ? `${Math.max(1, Math.round(m))} min ago` : m < 1440 ? `${Math.round(m / 60)} h ago` : `${Math.round(m / 1440)} d ago`; };
  const ctx = c => c == null ? '' : c >= 1e6 ? `${+(c / 1e6).toFixed(1)}M` : `${Math.round(c / 1000)}k`;
  const modelName = (p, id) => (p.models.find(m => m.id === id) || {}).name || id;
  const GO = '<svg class="ico" viewBox="0 0 24 24"><path d="M8 16L16 8M10 8h6v6"/></svg>';
  const TIERS = ['opus', 'sonnet', 'haiku', 'fable'];
  // the words for why a request failed, by the outcome tag splice records (OutcomeTags)
  const CAUSE = { 'failure:api_error': 'provider error', 'error:auth-missing': 'not signed in', 'error:plan-limit': 'plan limit', 'error:restarted': 'splice restarted', 'error:upstream-failed': 'provider failed', 'failure:overloaded_error': 'overloaded', 'error:rate-limited': 'rate limited' };
  const ADD_NAME = { codex: 'ChatGPT', grok: 'Grok', kimi: 'Kimi', muse: 'Muse', deepseek: 'DeepSeek', openrouter: 'OpenRouter', claude: 'Claude', local: 'Local server', 'api-key': 'Other API' };

  /* ---------- the live read, in the page's shape ---------- */
  let P = { providers: [], totals: {}, add: [], routeClaude: null, claude: null }, topology = null, vocab = null, readOk = null, readAt = 0;
  const pending = new Map();          // command key -> [{ what, to }] saved, in effect once that command restarts
  const local = fam => fam === 'local' || fam === 'vast';
  async function read() {
    const got = await Promise.all(['/api/heads', '/api/status', '/api/perf/summary?window=24h', '/api/economics', '/api/models', '/api/models/local', '/api/accounts', '/api/topology', '/api/claude-head', '/api/add/profiles']
      .map(p => (p === '/api/economics' ? API.getReady(p) : API.get(p))));
    const [hs, st, sm, ec, md, lo, ac, tp, ch, pf] = got.map(r => (r.ok ? r.body : null));
    readOk = got[0].ok;
    if (!hs) { paintHead(); return; }
    topology = tp && tp.topology ? tp.topology : topology;
    vocab = tp && tp.vocab ? tp.vocab : vocab;
    const reg = Object.fromEntries(((st && st.registry) || []).map(r => [r.key, r]));
    const sum = Object.fromEntries(((sm && sm.heads) || []).map(x => [x.key, x]));
    const econ = Object.fromEntries(((ec && ec.heads) || []).map(x => [x.key, x]));
    const mods = Object.fromEntries(((md && md.heads) || []).map(x => [x.head, x]));
    const srvs = Object.fromEntries(((lo && lo.providers) || []).map(x => [x.provider, x]));
    const accounts = (ac && ac.accounts) || [];
    const week0 = Date.now() - WEEK_MS, h0 = Math.floor(week0 / HOUR) * HOUR;
    P.providers = (hs.heads || []).map(hd => {
      const fam = (reg[hd.key] || {}).family || 'local', s = sum[hd.key] || null, e = econ[hd.key] || null, m = mods[hd.key] || { models: [] };
      const sp = SP.providerOf(hd.key);
      const wk = e ? (e.buckets || []).filter(b => b.hour >= week0) : null;
      const tot = k => (wk ? wk.reduce((a, b) => a + (b[k] || 0), 0) : null);
      const hourly = Array.from({ length: 168 }, (_, i) => (wk ? wk.filter(b => b.hour >= h0 + i * HOUR && b.hour < h0 + (i + 1) * HOUR).reduce((a, b) => a + (b.turns || 0), 0) : 0));
      const models = (m.models || []).filter(x => x.resolved !== false).map(x => {
        const u = s && s.models ? s.models[x.id] : null;
        return { id: x.id, name: x.label || x.id, context: x.context_window || null, maxContext: x.max_context_window || null, slot: x.slot || null,
          rates: x.rates ? { input: x.rates.input, output: x.rates.output, cached: x.rates.cache_read ?? null } : null,
          used: u ? { requests: u.count, failure: u.failure_share, time: u.total_ms || null } : null };
      });
      const cfg = (topology && topology.heads && topology.heads[hd.key]) || {};
      const tiers = { opus: null, sonnet: null, haiku: null, fable: null };
      models.forEach(x => { if (x.slot && x.slot in tiers && !tiers[x.slot]) tiers[x.slot] = x.id; });
      Object.entries(cfg.model_slots || {}).forEach(([k, v]) => { const t = String(k).toLowerCase(); if (t in tiers) tiers[t] = v; });
      const causes = s ? Object.entries(s.failure_shares || {}).map(([k, v]) => ({ cause: CAUSE[k] || k.replace(/^(failure|error):/, '').replace(/[_-]/g, ' '), n: Math.round(v * (s.count || 0)) })).sort((a, b) => b.n - a.n) : [];
      const last = hd.last_provider_answer, srv = srvs[cfg.provider] || srvs[hd.key];
      const planValue = tot('plan_value_usd'), cost = tot('cost_usd');
      return {
        key: hd.key, cmd: hd.label || hd.key, provider: local(fam) ? (fam === 'vast' ? 'Rented GPU' : 'Local') : (sp && sp.name) || fam, color: sp ? sp.color : 'var(--ink-faint)',
        pid: fam, local: local(fam) || !!srv, dialect: hd.dialect || null, auth: hd.authKind || (reg[hd.key] || {}).authKind || null, port: hd.port ?? null, version: hd.version || (st && st.version) || null,
        running: hd.running !== false && hd.state !== 'stopped', last: last ? { status: last.status, at: last.observed_at_epoch_ms } : null,
        inflight: (hd.gate && hd.gate.inflight) || 0,
        accounts: accounts.filter(a => (a.heads || []).includes(hd.key)).map(a => ({ name: a.display_name || a.email || a.label, email: a.email || null, plan: a.plan || null })),
        day: { requests: s ? s.count || 0 : null, failure: s ? s.failure_share : null, causes, time: s && s.total_ms, firstToken: s && s.time_before_first_byte_ms, cache: s ? s.cache_hit_ratio ?? null : null },
        week: { requests: tot('turns'), rateLimited: tot('rate_limited'), apiValue: wk ? Math.round((planValue || 0) + (cost || 0)) : null, onPlan: (planValue || 0) > 0, hourly },
        canSend: hd.running !== false && hd.state !== 'stopped' && (((hd.authKind || (reg[hd.key] || {}).authKind) !== 'client') || accounts.some(a => (a.heads || []).includes(hd.key) && a.credential_present && a.kind !== 'client')),
        cantSend: hd.running === false || hd.state === 'stopped' ? 'The command is not started' : 'It uses Claude Code’s own sign-in, which splice holds no key for',
        defaultModel: m.pinned_model || null, nativeTiers: hd.dialect === 'anthropic-passthrough' && !models.some(x => x.slot), models, tiers,
        server: srv ? { url: String(srv.url || '').replace(/^https?:\/\//, ''), reachable: !!srv.models, runtime: srv.runtime || null, version: srv.version || null,
          tools: Object.fromEntries((srv.models || []).filter(x => typeof x.tools === 'boolean').map(x => [x.id, x.tools])) } : null,
      };
    });
    const all = P.providers, day = all.reduce((a, p) => a + (p.day.requests || 0), 0);
    const timed = all.filter(p => p.day.time && p.day.time.p50), timedN = timed.reduce((a, p) => a + p.day.requests, 0);
    P.totals = {
      week: ec ? all.reduce((a, p) => a + (p.week.requests || 0), 0) : null,
      apiValue: ec ? all.reduce((a, p) => a + (p.week.apiValue || 0), 0) : null,
      dayFailure: sm && day ? all.reduce((a, p) => a + (p.day.failure || 0) * (p.day.requests || 0), 0) / day : null,
      dayMedianMs: timedN ? Math.round(timed.reduce((a, p) => a + p.day.time.p50 * p.day.requests, 0) / timedN) : null,
    };
    P.claude = ch;
    P.add = ((pf && pf.profiles) || []).filter(p => p.name !== 'nvidia').map(p => ({
      id: p.name, profile: p, name: ADD_NAME[p.name] || p.name,
      method: (p.asks || []).length ? 'server' : p.requires_key ? 'key' : 'signin', command: p.command || null,
      installed: all.some(x => x.cmd === p.command || x.key === p.head_key),
    }));
    readAt = Date.now();
    draw();
  }

  /* ---------- status in words ---------- */
  function status(p) {
    if (!p.running) return { word: 'Stopped', c: 'var(--ink-faint)', ring: true };
    if (p.server && !p.server.reachable) return { word: 'Server not reachable', c: 'var(--ormolu)', ring: true };
    if (p.inflight) return { word: `${p.inflight} running`, c: 'var(--verdigris)', live: true };
    if (p.last && p.last.status === 429) return { word: `Rate limited ${ago(p.last.at)}`, c: 'var(--ormolu)', ring: true };
    if (p.last && p.last.status === 402) return { word: `Payment required ${ago(p.last.at)}`, c: 'var(--vermilion)' };
    return { word: 'Ready', c: 'var(--verdigris)' };
  }
  const stateEl = p => { const s = status(p), e = h('span', 'pt-state'), l = h('span', 'lamp' + (s.ring ? ' ring' : '') + (s.live ? ' live' : '')); l.style.setProperty('--c', s.c); e.append(l, s.word); return e; };
  const fours = hrs => { const o = []; for (let i = 0; i < hrs.length; i += 4) o.push(hrs.slice(i, i + 4).reduce((a, b) => a + b, 0)); return o; };

  /* ---------- the field ---------- */
  function paintHead() {
    $('#pvEyebrow').textContent = readOk === false && !readAt ? 'splice is not answering' : `${Deck.count(P.providers.length, 'provider')} · last 7 days`;
    $('#pvRunning').textContent = readOk === false ? 'Not answering' : `${Deck.count(P.providers.reduce((a, p) => a + p.inflight, 0), 'request')} running`;
  }
  function draw() {
    const T = P.totals, cloud = P.providers.filter(p => !p.local).sort((a, b) => (b.week.requests || 0) - (a.week.requests || 0)), loc = P.providers.filter(p => p.local);
    paintHead();
    const hero = $('#hero'); hero.replaceChildren();
    const figs = h('div', 'pv-figs');
    [[n(T.week), 'Requests'], [money(T.apiValue), 'At API prices'], [pct(T.dayFailure), 'Failure rate, 24 h'], [secs(T.dayMedianMs), 'Median response time, 24 h']].forEach(([v, w]) => { const f = h('div', 'pv-fig'); f.append(h('b', '', v), h('span', '', w)); figs.append(f); });
    hero.append(figs);
    const used = cloud.filter(p => p.week.requests > 0);
    if (used.length && T.week) {
      const share = h('div', 'pv-share'), strip = Deck.strip(used.map(p => ({ share: p.week.requests / T.week })));
      [...strip.children].forEach((seg, i) => seg.style.setProperty('--c', used[i].color));
      const key = h('div', 'pv-key');
      used.forEach(p => { const k = h('span'), d = h('i'); d.style.setProperty('--pc', p.color); const u = p.week.requests / T.week; k.append(d, used.filter(x => x.provider === p.provider).length < 2 ? p.provider : `${p.provider} · ${p.cmd}`, h('b', '', u < 0.01 ? '<1%' : pct(u))); key.append(k); });
      share.append(strip, key); hero.append(share);
    }
    $('#cloud').replaceChildren(...cloud.map(card));
    $('#local').replaceChildren(...loc.map(localCard));
    $('#localSec').hidden = !loc.length;
    tool($('[data-part="add"]'), 'Add provider', `${P.add.filter(a => !a.installed).length}`, 'not added yet', 'ChatGPT, Grok, DeepSeek, OpenRouter, local servers');
    const models = P.providers.reduce((a, p) => a + p.models.length, 0);
    tool($('[data-part="compare"]'), 'Compare models', String(models), models === 1 ? 'model' : 'models', 'Price, context window, response time');
    if (openKey) { const a = pv.querySelector(`[data-key="${CSS.escape(openKey)}"], [data-part="${CSS.escape(openKey)}"]`); if (a) a.setAttribute('aria-expanded', 'true'); }
    paintNeed();
  }
  function head(el, p) {
    const go = h('span', 'pt-go'); go.innerHTML = GO;
    const nm = h('div', 'pt-name'), dot = h('i'); dot.style.setProperty('--pc', p.color); nm.append(dot, h('b', '', p.provider));
    el.append(go, nm);
  }
  function card(p) {
    const el = h('button', 'pt press' + ((p.week.requests || 0) < 100 ? ' is-quiet' : '')); el.type = 'button'; el.dataset.key = p.cmd; el.style.setProperty('--pc', p.color);
    head(el, p);
    const r1 = h('div', 'pt-row'); r1.append(h('span', 'pt-cmd', p.cmd), stateEl(p)); el.append(r1);
    const f = h('span', 'pt-fig', n(p.week.requests)); f.append(' ', h('small', '', p.week.requests === 1 ? 'request' : 'requests')); el.append(f);
    el.append(Deck.spark(fours(p.week.hourly)).el);
    const st = h('div', 'pt-stats');
    const fig = (v, w) => { const s = h('span'); s.append(h('b', '', v), ' ' + w); st.append(s); };
    if (p.day.time && p.day.requests >= 10) fig(secs(p.day.time.p50), 'median');
    if (p.day.requests) fig(pct(p.day.failure), 'failed');
    if (p.week.apiValue) fig(money(p.week.apiValue), 'at API prices');
    if (!st.childElementCount && p.defaultModel) st.append(h('span', '', modelName(p, p.defaultModel)));
    el.append(st);
    return el;
  }
  function localCard(p) {
    const el = h('button', 'pt pt-local press'); el.type = 'button'; el.dataset.key = p.cmd; el.style.setProperty('--pc', 'var(--ink-faint)');
    head(el, p);
    el.append(h('span', 'pt-cmd', p.cmd), h('span', 'pt-cap', p.defaultModel ? modelName(p, p.defaultModel) : (p.models[0] || {}).name || ''), h('span', 'pt-cmd', p.server ? p.server.url : ''), stateEl(p));
    return el;
  }
  function tool(el, name, fig, unit, cap) {
    el.replaceChildren();
    const go = h('span', 'pt-go'); go.innerHTML = GO;
    const nm = h('div', 'pt-name'); nm.append(h('b', '', name));
    const f = h('span', 'pt-fig', fig); f.append(' ', h('small', '', unit));
    el.append(go, nm, f, h('span', 'pt-cap', cap));
  }
  /** saved changes that take effect when their command restarts, gathered in the need float */
  function paintNeed() {
    const count = [...pending.values()].reduce((a, l) => a + l.length, 0);
    const first = [...pending.keys()][0];
    Deck.setNeed(count, 'Restart · {n}', () => { const p = P.providers.find(x => x.key === first); if (p) open(p); });
  }

  /* ---------- the lens ---------- */
  let openKey = null, saving = 0;
  // a glass is left as it stands while a save runs or a field in it is being typed in: a read redrawing it would lose either
  const busy = () => saving > 0 || (!ins.el.hidden && ins.el.contains(document.activeElement) && /^(INPUT|TEXTAREA)$/.test(document.activeElement.tagName));
  function open(p, focus) {
    const anchor = pv.querySelector(`[data-key="${CSS.escape(p.cmd)}"]`);
    if (!anchor) return;
    openWith(anchor, el => providerLens(el, p), p.cmd, focus);
    CMD.read().then(got => { if (got && openKey === p.cmd && !busy()) refill(); });
  }
  /** the open command's glass drawn again in place, with what is in it now: it does not grow out of its part again */
  function refill() {
    const p = openKey && P.providers.find(x => x.cmd === openKey);
    if (!p || ins.el.hidden) return;
    ins.el.textContent = ''; providerLens(ins.el, p);
    if (window.DeckLens) DeckLens.redraw(ins.el);
  }
  function openWith(anchor, fillBody, key, focus) {
    $$('[aria-expanded="true"]', pv).forEach(x => x.setAttribute('aria-expanded', 'false'));
    anchor.setAttribute('aria-expanded', 'true'); openKey = key;
    ins.openBeside(anchor, el => { el.classList.remove('cmp-wide'); el.classList.add('wide'); fillBody(el); }, pv);
    if (focus) { const r = ins.el.querySelector(`[data-item="${focus}"]`); if (r) { r.classList.add('is-flash'); setTimeout(() => r.classList.remove('is-flash'), px('--dur') * 6); } }
  }
  /** closes the glass and forgets it at once, so a read landing while it fades never opens it again */
  function closeLens() { openKey = null; ins.close(); }
  new MutationObserver(() => { if (ins.el.hidden && openKey) { $$('[aria-expanded="true"]', pv).forEach(x => x.setAttribute('aria-expanded', 'false')); openKey = null; } }).observe(ins.el, { attributes: true, attributeFilter: ['hidden'] });
  /** after a read, a command's glass again with the new figures; Add provider and Compare models keep what is typed */
  function reopen() {
    if (!openKey || busy()) return;
    const p = P.providers.find(x => x.cmd === openKey);
    if (p) refill(); else if (openKey !== 'add' && openKey !== 'compare') closeLens();
  }
  const lensHead = (el, title, dotColor, sub) => {
    const hd = h('div', 'hd');
    if (dotColor) hd.append(Deck.lamp('ready', dotColor));
    hd.append(h('h3', '', title));
    const x = Deck.iconBtn('close', 'Close'); x.addEventListener('click', () => closeLens()); hd.append(x);
    el.append(hd);
    if (sub) el.append(sub);
  };
  function row(id, name, sub) {
    const r = h('div', 'lr'); r.dataset.item = id;
    const nm = h('div', 'lr-nm'); nm.append(h('span', '', name)); if (sub) nm.append(h('small', '', sub));
    const sv = Deck.saveState({ rest: () => '' });
    const ctl = h('div', 'lr-ctl');
    r.append(nm, sv, ctl);
    return { r, sv, ctl };
  }
  const figsRow = (id, name, pairs) => { const r = h('div', 'lr'); r.dataset.item = id; r.append(h('div', 'lr-nm', name), h('span')); const g = h('div', 'lr-figs'); pairs.filter(Boolean).forEach(([v, w]) => { const d = h('div'); d.append(h('b', '', v), h('span', '', w)); g.append(d); }); r.append(g); return r; };
  /** a write, said where it was made: Saving, then Applied, At restart, or Not saved with why (the old value back) */
  async function commit(sv, run, revert) {
    sv.set('saving'); saving++;
    let res;
    try { res = await run(); } finally { saving--; }
    if (!res.ok) { sv.set('refused', res.why || why(res)); if (revert) revert(); return res; }
    sv.set(res.restart ? 'restart' : 'applied', res.word);
    return res;
  }
  const remember = (p, what, to, word) => { const l = pending.get(p.key) || []; l.push({ what, to, word }); pending.set(p.key, l); paintNeed(); };
  async function saveDefault(p, id) {
    const res = await API.patch('/api/config?head=' + enc(p.key), { pinnedModel: id });
    const rejected = res.ok && res.body && res.body.rejected && Object.keys(res.body.rejected).length;
    if (!res.ok || rejected) return { ok: false, why: rejected ? String(Object.values(res.body.rejected)[0]) : why(res) };
    const restart = (res.body.restart_required || []).length > 0;
    if (restart) remember(p, 'Default model', id);
    p.defaultModel = id;
    return { ok: true, restart };
  }
  async function saveTier(p, tier, id) {
    if (!topology) return { ok: false, why: 'the topology was not read' };
    const next = structuredClone(topology);
    next.heads = next.heads || {};
    const hd = next.heads[p.key] = next.heads[p.key] || {};
    const slots = Object.fromEntries(Object.entries(hd.model_slots || {}).filter(([k]) => String(k).toLowerCase() !== tier));
    if (id) slots[tier] = id;
    hd.model_slots = slots;
    const res = await API.put('/api/topology', { topology: next });
    if (!res.ok || (res.body && res.body.ok === false)) {
      const found = (res.body && res.body.findings) || [];
      if (found.length) console.warn(`providers: mapping for ${p.cmd} refused: ${found.map(x => x.message).join('; ')}`);
      return { ok: false, why: found.length ? 'Config error' : why(res) };
    }
    topology = next; p.tiers[tier] = id;
    const restart = !!(res.body && res.body.restart_required);
    if (restart) remember(p, `Model mapping: ${tier}`, id);
    return { ok: true, restart };
  }

  const kit = {
    row, commit, refill, reread: () => read(),
    topology: () => topology, setTopology: t => { topology = t; }, vocab: () => vocab,
    remember: (p, what) => remember(p, what, null, 'Changed'),
  };
  function providerLens(el, p) {
    lensHead(el, p.provider === p.cmd ? p.cmd : `${p.provider} · ${p.cmd}`, p.color, stateEl(p));
    CMD.top(el, p, kit);
    // default model
    {
      const opts = p.models.map(m => ({ v: m.id, label: m.name, sub: m.context ? `${ctx(m.context)} context` : '' }));
      if (p.nativeTiers) opts.unshift({ v: null, label: 'Claude Code default' });
      const { r, sv, ctl } = row('default', 'Default model', 'New sessions start on it');
      if (opts.length) { let c; c = Deck.choice(opts, p.defaultModel, v => commit(sv, () => saveDefault(p, v), () => c.set(p.defaultModel)), { max: 4 }); ctl.append(c); }
      else ctl.append(h('span', 'faint', 'No models read'));
      el.append(r);
    }
    // model mapping
    {
      const { r, sv, ctl } = row('mapping', 'Model mapping', 'What /model opus, sonnet, haiku and fable select');
      ctl.remove();
      if (p.nativeTiers) { const k = h('div', 'kvs'), d = h('div'); d.append(h('span', '', 'Opus, Sonnet, Haiku, Fable'), h('b', '', 'Claude Code chooses')); k.append(d); r.append(k); }
      else {
        const t = h('div', 'tiers');
        TIERS.forEach(tier => {
          const box = h('div'); box.append(h('div', 'eyebrow', tier[0].toUpperCase() + tier.slice(1)));
          const opts = [{ v: null, label: 'Default model' }, ...p.models.map(m => ({ v: m.id, label: m.name }))];
          let c; c = Deck.choice(opts, p.tiers[tier], v => commit(sv, () => saveTier(p, tier, v), () => c.set(p.tiers[tier])), { max: 1 });
          box.append(c); t.append(box);
        });
        r.append(t);
      }
      el.append(r);
    }
    // response time per model, last 24 h
    const usedModels = p.models.filter(m => m.used && m.used.time && m.used.time.p50);
    if (usedModels.length) {
      const r = h('div', 'lr'); r.dataset.item = 'speed'; r.append(h('div', 'lr-nm', 'Response time, 24 h'), h('span', 'faint small', 'Median · 95%'));
      const box = h('div', 'rt'), max = Math.max(...usedModels.map(m => m.used.time.p95 || m.used.time.p50));
      const X = ms => Math.log(1 + ms / 1000) / Math.log(1 + max / 1000);
      usedModels.sort((a, b) => b.used.requests - a.used.requests).slice(0, 6).forEach(m => {
        const rr = h('div', 'rt-row'), bar = h('span', 'rt-bar'), i = h('i'), e = h('em');
        bar.style.setProperty('--pc', p.color); i.style.setProperty('--u', X(m.used.time.p50)); e.style.setProperty('--x', X(m.used.time.p95 || m.used.time.p50)); bar.append(i, e);
        const v = h('b', '', secs(m.used.time.p50)); v.append(h('small', '', ` · ${secs(m.used.time.p95)}`));
        rr.append(h('span', '', m.name), bar, v); box.append(rr);
      });
      r.append(box); el.append(r);
    }
    // last 24 h and last 7 days
    if (p.day.requests) {
      const c = p.day.causes, failed = c.length ? `Failed · ${c.slice(0, 2).map(x => `${x.n} ${x.cause}`).join(', ')}${c.length > 2 ? `, ${c.slice(2).reduce((a, x) => a + x.n, 0)} other` : ''}` : 'Failed';
      el.append(figsRow('day', 'Last 24 h', [[n(p.day.requests), 'Requests'], [pct(p.day.failure), failed], p.day.firstToken ? [secs(p.day.firstToken.p50), 'Time to first token'] : null, p.day.cache != null ? [pct(p.day.cache), 'Cache hit rate'] : null]));
    }
    {
      const r = figsRow('week', 'Last 7 days', [[n(p.week.requests), 'Requests'], [n(p.week.rateLimited), 'Rate limits (429)'], p.week.apiValue ? [money(p.week.apiValue), p.week.onPlan ? 'At API prices, on a plan' : 'Spent'] : null]);
      if (p.week.requests) r.append(Deck.spark(fours(p.week.hourly)).el);
      el.append(r);
    }
    // accounts
    {
      const r = h('div', 'lr'); r.dataset.item = 'accounts'; r.append(h('div', 'lr-nm', `Accounts · ${p.accounts.length}`));
      const go = h('a', 'btn quiet press', 'Open Accounts'); go.href = 'accounts.html'; r.append(go);
      const k = h('div', 'kvs'); p.accounts.slice(0, 5).forEach(a => { const d = h('div'); d.append(h('span', '', a.name), h('b', '', [a.plan, a.email].filter(Boolean).join(' · '))); k.append(d); });
      if (p.accounts.length > 5) { const d = h('div'); d.append(h('span', '', `${p.accounts.length - 5} more`), h('b')); k.append(d); }
      r.append(k); el.append(r);
    }
    // the plain claude command, where splice has a launcher for it
    if (p.pid === 'anthropic' && P.claude && P.claude.shim_path) {
      const on = P.claude.mode === 'wrapped';
      const { r, sv, ctl } = row('route', 'Send the claude command through splice', 'Off: claude goes straight to Anthropic');
      let c; c = Deck.choice([{ v: false, label: 'Off' }, { v: true, label: 'On' }], on, v => commit(sv, async () => {
        const res = await API.post('/api/claude-head/' + (v ? 'wrap' : 'unwrap'), {});
        const again = await API.get('/api/claude-head'); if (again.ok) P.claude = again.body;
        return res;
      }, () => c.set(P.claude.mode === 'wrapped')));
      ctl.append(c); el.append(r);
    }
    if (p.server) el.append(figsRow('server', 'Model server', [[p.server.url, 'URL'], [p.server.reachable ? 'Answering' : 'Not answering', 'Status']]));
    CMD.rows(el, p, kit);
    el.append(actions(p));
  }

  /* restart, stop, remove: say what each cuts before it runs */
  function actions(p) {
    const { r, sv, ctl } = row('actions', 'Restart, stop or remove', p.inflight ? `${Deck.count(p.inflight, 'request')} running would be cut` : '');
    const waiting = pending.get(p.key) || [];
    if (waiting.length) { const k = h('div', 'kvs'); waiting.forEach(w => { const d = h('div'); d.append(h('span', '', w.what), h('b', '', w.word != null ? w.word : w.to == null ? 'Default' : modelName(p, w.to))); k.append(d); }); r.insertBefore(k, ctl); }
    const btn = (cls, word) => { const b = h('button', cls + ' press', word); b.type = 'button'; return b; };
    const re = btn('btn' + (waiting.length ? ' primary' : ''), p.running ? 'Restart' : 'Start'), st = btn('btn quiet', 'Stop'), rm = btn('btn quiet', 'Remove');
    const restart = () => commit(sv, async () => {
      const res = await API.post('/api/heads/' + enc(p.key) + '/restart', {});
      if (res.ok) { pending.delete(p.key); paintNeed(); await read(); }
      return Object.assign(res, { word: p.running ? 'Restarted' : 'Running' });
    });
    // each acts on its one press (the owner's act, Nov 19, 2026); the row's caption says what a press cuts
    re.addEventListener('click', restart);
    st.addEventListener('click', () => commit(sv, async () => {
      const res = await API.post('/api/heads/' + enc(p.key) + '/stop', {});
      if (res.ok) await read();
      return Object.assign(res, { word: 'Stopped' });
    }));
    rm.addEventListener('click', () => commit(sv, async () => {
      const res = await API.del('/api/heads/' + enc(p.key));
      if (res.ok) { pending.delete(p.key); closeLens(); await read(); }
      return Object.assign(res, { word: 'Removed' });
    }));
    ctl.append(re);
    if (p.running) ctl.append(st);
    ctl.append(rm);
    return r;
  }

  /* ---------- add a provider ---------- */
  let adding = null;
  function addLens(el) {
    lensHead(el, 'Add provider');
    const body = h('div'); el.append(body);
    const list = () => {
      body.replaceChildren();
      const r = h('div', 'lr'); r.dataset.item = 'types'; r.append(h('div', 'lr-nm', 'Provider type'), h('span'));
      const l = h('div', 'addl');
      if (!P.add.length) l.append(h('span', 'faint', readOk === false ? 'splice is not answering' : 'splice lists no provider types'));
      P.add.forEach(a => {
        const b = h('button'); b.type = 'button';
        b.append(h('span', '', a.name), h('span', 'add-how', a.installed ? 'Added' : a.method === 'signin' ? 'Sign in' : a.method === 'key' ? 'API key' : 'URL and model'), h('span', 'add-cmd', a.command || ''));
        b.addEventListener('click', () => step(a)); l.append(b);
      });
      r.append(l); body.append(r);
    };
    const step = a => {
      body.replaceChildren();
      const p = a.profile, asks = p.asks || [];
      const back = h('button', 'btn quiet press', 'Back'); back.type = 'button'; back.addEventListener('click', () => { drop(); list(); });
      const { r, sv, ctl } = row('add-' + a.id, a.name, a.command && !a.installed ? `Adds the command ${a.command}` : 'Adds a command for this provider');
      const field = (ph, type) => { const i = h('input', 'lr-in'); i.placeholder = ph; if (type) i.type = type; i.autocomplete = 'off'; i.spellcheck = false; i.addEventListener('keydown', e => e.stopPropagation()); ctl.append(i); return i; };
      // a second command on a provider already added needs a name of its own
      const named = asks.includes('name') || a.installed ? field(a.installed && a.command ? `Name, e.g. work (adds ${a.command}-work)` : 'Command, e.g. claude-local') : null;
      const url = asks.includes('base_url') ? field('http://127.0.0.1:8099/v1') : null;
      const model = asks.includes('models') ? field('Model id') : null;
      const key = a.method === 'key' ? field('API key', 'password') : null;
      const go = h('button', 'btn primary press', a.method === 'signin' ? 'Sign in' : 'Verify and add'); go.type = 'button';
      const say = word => { r.querySelector('.lr-why')?.remove(); r.append(h('div', 'lr-why', word)); };
      go.addEventListener('click', () => {
        r.querySelector('.lr-why')?.remove();
        const missing = [named, url, model, key].filter(Boolean).filter(f => !f.value.trim());
        if (missing.length) { sv.set('refused'); say(key && missing.includes(key) ? 'Enter the API key' : 'Enter every field'); return; }
        if (url && !/^https?:\/\/\S+/.test(url.value.trim())) { sv.set('refused'); say('Not a URL'); return; }
        const body = { profile: p.name };
        if (named) { if (a.installed && p.command) { body.name = named.value.trim(); body.command = `${p.command}-${named.value.trim()}`; } else body.name = named.value.trim(); }
        if (url) body.base_url = url.value.trim();
        if (model) body.models = [{ id: model.value.trim() }];
        runAdd(body, key && key.value.trim(), sv, say, () => { [named, url, model, key].filter(Boolean).forEach(f => { f.value = ''; }); });
      });
      ctl.append(go);
      if (key) r.append(h('div', 'faint small', 'Stored in the keyring, never shown again'));
      body.append(r);
      const b2 = h('div', 'lr'); b2.append(back); body.append(b2);
    };
    list();
  }
  /** an add left open is ended on splice, so none waits there with nobody to finish it */
  const drop = () => { if (adding && adding.id && !adding.saved) API.del('/api/add/' + enc(adding.id)); adding = null; };
  async function runAdd(body, key, sv, say, clear) {
    drop();
    const a = adding = { id: null, saved: false };
    sv.set('saving', 'Adding'); saving++;
    try {
      const res = await API.post('/api/add', body);
      if (!res.ok) { sv.set('refused', res.status === 409 ? 'Already added' : 'Not added'); say(why(res)); return; }
      a.id = res.body.id;
      if (res.body.sign_in_by === 'key' && key) {
        const put = await API.put('/api/keys/' + enc(res.body.key_env), { value: key });
        if (!put.ok) { sv.set('refused', 'Key not saved'); say(why(put)); return; }
      }
      if (res.body.sign_in_by === 'login') {
        const add = '/api/add/' + enc(a.id), sg = { how: { start: `${add}/login`, body: {}, watch: add, end: `${add}/login` } };
        sv.set('saving', 'Signing in');
        const done = await new Promise(resolve => {
          sg.on = {
            held: () => adding === a, draw: () => { if (sg.url) { say(''); const link = h('a', 'btn quiet press', 'Open the sign-in page'); link.href = sg.url; link.target = '_blank'; link.rel = 'noopener noreferrer'; sv.closest('.lr').querySelector('.lr-why').replaceChildren(link); } },
            done: () => resolve(true), failed: () => resolve(false),
          };
          SIGNIN.start(sg, sg.on);
        });
        if (!done) { sv.set('refused', sg.fail ? sg.fail.word : 'Not completed'); say(sg.why || ''); return; }
      }
      sv.set('saving', 'Verifying');
      const v = await API.post('/api/add/' + enc(a.id) + '/verify', {});
      const failed = ((v.body && v.body.checks) || []).filter(c => !c.ok && c.name !== 'models');
      if (!v.ok || failed.length) { sv.set('refused', 'Not added'); say(failed.length ? failed.map(c => c.name + (c.detail ? ': ' + c.detail : '')).join(', ') : why(v)); return; }
      const s = await API.post('/api/add/' + enc(a.id) + '/save', {});
      if (!s.ok) { sv.set('refused', 'Not added'); say(why(s)); return; }
      a.saved = true; adding = null; clear();
      sv.set('applied', 'Added');
      await read();
    } finally { saving--; }
  }

  /* ---------- compare models ---------- */
  let picked = null;
  function compareLens(el) {
    lensHead(el, 'Compare models');
    const ALL = P.providers.flatMap(p => p.models.map(m => ({ ...m, p, key: `${p.cmd}|${m.id}` })));
    // the three most used models, the first time it opens
    if (!picked && CMP.kept().some(k => ALL.some(m => m.key === k))) picked = CMP.kept();
    if (!picked) picked = ALL.slice().sort((a, b) => ((b.used && b.used.requests) || 0) - ((a.used && a.used.requests) || 0)).filter((m, i, all) => all.findIndex(x => x.id === m.id) === i).slice(0, 3).map(m => m.key);
    picked = picked.filter(k => ALL.some(m => m.key === k));
    const body = h('div'); el.append(body);
    const keepPicks = () => CMP.keep(picked);
    let active = Math.max(0, picked.length - 1), q = '';
    const paint = () => {
      body.replaceChildren();
      const r = h('div', 'lr'); r.dataset.item = 'compare'; const nm0 = h('div', 'lr-nm', 'Up to three models'); nm0.append(h('small', '', 'Press a column, then pick its model below')); r.append(nm0, h('span'));
      const g = h('div', 'cmp'); g.style.setProperty('--n', Math.max(1, picked.length));
      g.append(h('span'));
      const ms = picked.map(k => ALL.find(m => m.key === k));
      ms.forEach((m, i) => {
        const b = h('button', 'cmp-h press' + (i === active ? ' is-on' : '')); b.type = 'button';
        const dot = h('i'); dot.style.setProperty('--pc', m.p.color); b.append(dot, h('span', '', m.name));
        b.addEventListener('click', () => { active = i; paint(); body.querySelector('.lr-in')?.focus(); });
        g.append(b);
      });
      const rowOf = (label, get, fmt, lowIsBest) => {
        g.append(h('span', '', label));
        const vals = ms.map(get), real = vals.filter(v => v != null && typeof v === 'number');
        const best = real.length > 1 && lowIsBest !== undefined ? (lowIsBest ? Math.min(...real) : Math.max(...real)) : null;
        vals.forEach(v => g.append(h('b', v == null ? 'none' : v === best ? 'best' : '', v == null ? 'None' : fmt(v))));
      };
      rowOf('Provider', m => (m.p.provider === m.p.cmd || P.providers.filter(x => x.provider === m.p.provider).length < 2 ? m.p.provider : `${m.p.provider} · ${m.p.cmd}`), v => v);
      rowOf('Input, per 1M tokens', m => (m.rates ? m.rates.input ?? null : null), v => `$${v}`, true);
      rowOf('Output, per 1M tokens', m => (m.rates ? m.rates.output ?? null : null), v => `$${v}`, true);
      rowOf('Cached input, per 1M', m => (m.rates ? m.rates.cached ?? null : null), v => `$${v}`, true);
      rowOf('Context window', m => m.maxContext || m.context, ctx, false);
      rowOf('Median response, 24 h', m => (m.used && m.used.time ? m.used.time.p50 ?? null : null), secs, true);
      rowOf('Failure rate, 24 h', m => (m.used ? m.used.failure : null), pct, true);
      rowOf('Requests, 24 h', m => (m.used ? m.used.requests : null), n, false);
      r.append(g); body.append(r);
      const f = h('div', 'lr'); f.dataset.item = 'find';
      const i = h('input', 'lr-in'); i.placeholder = `Find a model for column ${active + 1}`; i.value = q; i.spellcheck = false;
      const list = h('div', 'cmp-find');
      const fill = () => {
        q = i.value; const s = q.trim().toLowerCase();
        const hits = ALL.filter(m => !picked.includes(m.key) && (!s || `${m.name} ${m.id} ${m.p.provider} ${m.p.cmd}`.toLowerCase().includes(s)))
          .sort((a, b) => ((b.used && b.used.requests) || 0) - ((a.used && a.used.requests) || 0)).slice(0, 6);
        list.replaceChildren(...hits.map(m => { const b = h('button', 'press'); b.type = 'button'; const d = h('i'); d.style.setProperty('--pc', m.p.color); b.append(d, h('span', '', m.name), h('b', '', m.p.canSend ? m.p.cmd : `${m.p.cmd} · can’t send`)); b.addEventListener('click', () => { picked[active] = m.key; q = ''; keepPicks(); paint(); }); return b; }));
      };
      i.addEventListener('input', fill); i.addEventListener('keydown', e => e.stopPropagation());
      const acts = h('div', 'lr-ctl'); acts.append(i);
      if (picked.length < 3 && ALL.some(m => !picked.includes(m.key))) { const b = h('button', 'btn quiet press', 'Add a column'); b.type = 'button'; b.addEventListener('click', () => { picked.push(ALL.find(m => !picked.includes(m.key)).key); active = picked.length - 1; keepPicks(); paint(); }); acts.append(b); }
      if (picked.length > 2) { const b = h('button', 'btn quiet press', 'Remove a column'); b.type = 'button'; b.addEventListener('click', () => { picked.splice(active, 1); active = Math.min(active, picked.length - 1); keepPicks(); paint(); }); acts.append(b); }
      f.append(acts, list); body.append(f); fill();
      CMP.rows(body, ms.map((m, j) => ({ key: picked[j], id: m.id, name: m.name, p: m.p })), paint);
    };
    paint();
  }
  const openTool = (t, kind) => (kind === 'add' ? openWith(t, addLens, 'add') : openWith(t, el => { el.classList.add('cmp-wide'); compareLens(el); }, 'compare'));

  /* ---------- pressing a part ---------- */
  pv.addEventListener('click', e => {
    const t = e.target.closest('.pt'); if (!t) return;
    if (ins.open === t) { closeLens(); return; }
    if (t.dataset.key) { const p = P.providers.find(x => x.cmd === t.dataset.key); if (p) open(p); return; }
    if (t.dataset.part) openTool(t, t.dataset.part);
  });
  new MutationObserver(() => { if (ins.el.hidden) drop(); }).observe(ins.el, { attributes: true, attributeFilter: ['hidden'] });

  /* ---------- the address: ?cmd=, ?open=compare|add, opened once the first read lands ---------- */
  function openAddress() {
    const q = new URLSearchParams(location.search), cmd = q.get('cmd'), what = q.get('open');
    if (cmd) { const p = P.providers.find(x => x.cmd === cmd || x.key === cmd); if (p) open(p); }
    else if (what === 'compare' || what === 'add') openTool($(`[data-part="${what}"]`), what);
  }

  const clock = () => { $('#pvClock').textContent = Deck.clock(); };
  clock(); setInterval(clock, 30000);
  let reading = false;
  const readNow = async () => {
    if (reading || document.hidden || document.body.classList.contains('locked') || busy()) return;
    reading = true;
    try { await Promise.all([read(), CMD.read()]); reopen(); } finally { reading = false; }
  };
  paintHead();
  SP.ready.then(async () => { await readNow(); openAddress(); });
  setInterval(readNow, READ_MS);
  addEventListener('splice:signed-in', readNow);
})();
