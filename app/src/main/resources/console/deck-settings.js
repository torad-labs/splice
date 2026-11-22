/* Settings: the approved Night Deck machine (prototype settings/m-machine), on splice's live reads. Splice drawn as what
   it is: Sessions -> one gate per command -> Stalled responses -> Reasoning display back, and the system row (Usage
   alerts, MCP servers, Modes, Storage, Projects, Splice, Advanced; Modes since Nov 21, 2026: what each mode adds to a
   session and its switch per command, modes/README.md). Each part shows its live figure; pressing one grows its
   glass holding only its controls. A value is picked, never typed, unless it truly is free, and every change saves the
   moment it is made, its row saying what splice answered: Applied, At restart, or Not saved with why (the old value
   back). At rest a row names where its value comes from. The model and every read and write are deck-settings-model.js. */
(() => {
  'use strict';
  const { $, $$, h } = Deck, S = window.SETTINGS_LIVE, F = S.fmt, SP = window.SPLICE;
  const mc = $('#mc'), ins = Deck.inspector(mc);
  const pc = cmd => (SP.providerOf(cmd) || {}).color || 'var(--ink-faint)';
  // a provider is named alone, as the approved page names its gates; its command is added only where two commands share it
  const provName = cmd => { const p = SP.providerOf(cmd); return p ? (p.name === p.command || SP.providers.filter(x => x.name === p.name).length < 2 ? p.name : `${p.name} · ${p.command}`) : cmd; };
  const cmdName = cmd => (SP.providerOf(cmd) || {}).command || cmd;
  const days = d => (d === 'forever' ? 'Forever' : d === 0 ? 'Today' : d === 365 ? '1 year' : d === 1 ? '1 day' : `${d} d`);
  const optLabel = (opts, v) => (opts.find(o => o.v === v) || {}).label || (v == null ? 'Default' : String(v));
  const n0 = n => (n == null ? '—' : Math.round(n).toLocaleString('en-US'));
  const READ_MS = 30000;
  const pctOf = n => (n == null ? '—' : `${n}%`);

  /* ---------- which settings each part holds ---------- */
  const PARTS = {
    cc: ['effort', 'progressLine', 'verbosity'],
    reasoning: ['reasoning', 'replayReasoning'],
    gates: ['maxInflight', 'maxQueued', 'maxRequestBytes', 'requestReadTimeoutMs', 'ingressReadIdleMs', 'materializationHeapBytes'],
    watch: ['stallLine'],
    limits: ['planPoll', 'usageWarnPct', 'budgetDefaultAction', 'alerts', 'usageWarnTokens5h'],
    mcp: ['mcpShare', 'mcpIdleTimeoutMs', 'mcpUnshared', 'mcpMaxServers', 'mcpRequestTimeoutMs', 'mcpInitializeTimeoutMs'],
    modes: [],   // no setting of /api/config: its rows are the modes GET /api/modes lists (modesLens)
    storage: ['transcriptView', 'keep', 'traceMaxBodyChars'],
    projects: ['projectsFolders', 'statuslineGitRoots'],
    splice: ['version', 'restart', 'debug', 'files', 'fixed'],
  };
  const NAME = { cc: 'Sessions', reasoning: 'Reasoning display', gates: 'Concurrent requests', watch: 'Stalled responses', mcp: 'MCP servers', modes: 'Modes', storage: 'Storage', limits: 'Usage alerts', projects: 'Projects', splice: 'Splice', internals: 'Advanced' };
  const keysOf = item => {
    if (!item) return [];
    const ks = [];
    if (item.key) ks.push(item.key);
    if (Array.isArray(item.keys)) ks.push(...item.keys); else if (item.keys) ks.push(...Object.values(item.keys));
    (item.parts || []).forEach(p => ks.push(p.key));
    (item.rows || []).forEach(r => { if (r.key) ks.push(r.key); if (r.toggle) ks.push(r.toggle); if (r.combo) ks.push(...r.combo.keys); });
    return ks.filter(k => S.knob(k));
  };
  /** the rare settings: the Advanced part holds them, grouped by where they belong */
  const rareKey = key => S.deck.some(sec => sec.items.some(it => (it.adv && keysOf(it).includes(key)) || (it.rows || []).some(r => r.adv && keysOf({ rows: [r] }).includes(key))));
  const partKeys = p => (p === 'internals' ? S.knobs.filter(k => rareKey(k.key)).map(k => k.key) : PARTS[p].flatMap(id => keysOf(S.item(id))));
  const changed = p => partKeys(p).some(k => !S.isDefault(k) || Object.keys(S.knob(k).own).length);
  const waiting = p => S.pending().some(c => partKeys(p).includes(c.key));

  /* ---------- the parts on the field ---------- */
  const GO = '<svg class="ico" viewBox="0 0 24 24"><path d="M8 16L16 8M10 8h6v6"/></svg>';
  function fill(el, { name, fig, unit, cap, vis, part }) {
    el.replaceChildren();
    const go = h('span', 'pt-go'); go.innerHTML = GO; el.append(go);
    const n = h('span', 'pt-name', name);
    if (part && waiting(part)) n.append(h('i', 'pt-wait'));
    else if (part && changed(part)) n.append(h('i', 'pt-changed'));
    const f = h('span', 'pt-fig', fig); if (unit) f.append(' ', h('small', '', unit));
    el.append(n, f);
    if (cap) el.append(h('span', 'pt-cap', cap));
    if (vis) { const v = h('div', 'pt-vis'); v.append(vis); el.append(v); }
  }
  const meterRow = (word, u, c, mark) => {
    const r = h('div', 'mtr'), b = h('span', 'mtr-bar'), i = h('i');
    i.style.setProperty('--u', Math.min(1, u || 0)); if (c) b.style.setProperty('--c', c); b.append(i);
    if (mark != null) { const m = h('em'); m.style.setProperty('--x', mark); b.append(m); }
    r.append(h('span', '', word), b); return r;
  };
  const AX = { from: 10000, to: 7200000 };
  const ax = ms => Math.max(0, Math.min(1, Math.log(ms / AX.from) / Math.log(AX.to / AX.from)));
  function stallVis() {
    const box = h('div'), sw = h('div', 'sw'); sw.append(h('i', 'sw-line'));
    const longest = S.item('stallLine').longest;
    const fb = S.valueOf('firstByteTimeoutMs'), si = S.valueOf('streamIdleMs');
    const ticks = [
      ...(fb === si ? [{ ms: fb, c: 'var(--prussian)', up: true }] : [{ ms: fb, c: 'var(--prussian)', up: true }, { ms: si, c: 'var(--prussian)', up: true }]),
      { ms: S.valueOf('stallReanchorMs'), c: 'var(--verdigris)' },
      { ms: S.valueOf('upstreamTimeoutMs'), c: 'var(--vermilion)', up: true },
      ...(longest ? [{ ms: longest, c: 'var(--ink)', word: F.exact(longest) }] : []),
    ].filter(t => t.ms > 0);
    ticks.forEach(t => { const e = h('span', 'sw-t' + (t.up ? ' up' : '')); e.style.setProperty('--x', ax(t.ms)); e.style.setProperty('--c', t.c); e.append(h('span', '', t.word || F.dur(t.ms))); sw.append(e); });
    const ends = h('div', 'sw-ends'); ends.append(h('span', '', '10 s'), h('span', '', '2 h'));
    box.append(sw, ends); return box;
  }
  function draw() {
    const v = S.valueOf, eff = S.item('effort'), pair = S.item('reasoning');
    fill($('[data-part="cc"]'), { part: 'cc', name: NAME.cc, fig: String(S.running.sessions), unit: 'running', cap: `Default effort: ${optLabel(eff.options, v('effort'))}` });
    fill($('[data-part="reasoning"]'), { part: 'reasoning', name: NAME.reasoning, fig: `${optLabel(pair.parts[0].options, v('summary'))} · ${optLabel(pair.parts[1].options, v('showReasoning'))}` });
    drawGates();
    const sl = S.item('stallLine');
    fill($('[data-part="watch"]'), { part: 'watch', name: NAME.watch, fig: n0(sl.silent), unit: 'this week', cap: `Stalled over ${F.dur(v('streamIdleMs'))} · ${n0(sl.gaveUp)} gave up`, vis: stallVis() });
    const lv = h('div'), outs = h('div', 'outs');
    if (v('usageWarnPct') != null) lv.append(meterRow(pctOf(v('usageWarnPct')), 0, null, v('usageWarnPct') / 100));
    [['Command', !!S.alerts.command], ['Desktop', !!S.alerts.desktop], ['Webhook', !!S.alerts.webhook]].forEach(([w, on]) => { const o = h('span'); o.append(Deck.lamp(on ? 'ready' : 'next', on ? 'var(--verdigris)' : 'var(--ink-faint)'), w); outs.append(o); });
    lv.append(outs);
    fill($('[data-part="limits"]'), { part: 'limits', name: NAME.limits, fig: pctOf(v('usageWarnPct')), cap: 'Alert threshold for plan usage', vis: lv });
    const m = S.mcp, mv = h('div');
    if (m.bytesNow) mv.append(meterRow('Now', 1, 'var(--ink-faint)'), meterRow('Shared', (m.bytesIfShared || 0) / m.bytesNow, 'var(--verdigris)'));
    fill($('[data-part="mcp"]'), { part: 'mcp', name: NAME.mcp, fig: F.bytes(m.bytesNow), cap: m.bytesIfShared != null ? `${F.bytes(m.bytesIfShared)} with one shared copy` : '', vis: mv });
    // a mode counts as on when any command has it on; the figure is how many are, of how many splice carries
    const md = S.modes, mdOn = md ? md.filter(x => Object.values(x.heads || {}).some(st => st.on)).length : null;
    fill($('[data-part="modes"]'), { part: 'modes', name: NAME.modes, fig: md ? String(mdOn) : '—', unit: md ? `of ${md.length} on` : '', cap: md ? 'Tools Splice adds to a session' : 'Not read' });
    const st = S.data.stores, total = st.length ? st.reduce((n, s) => n + (s.bytes || 0), 0) : null;
    const sv = h('div');
    if (total) {
      const stk = h('div', 'stk'), keys = h('div', 'stk-k');
      const big = [['transcript_copies', 'Backups', S.knob('keepTranscriptCopies') ? S.comboValue(['keepTranscriptCopies', 'transcriptCopyDays']) : null, 'var(--seg-a)'], ['history', 'History', v('historyRetentionDays'), 'var(--seg-b)']];
      let rest = total;
      big.forEach(([id, word, keep, a]) => {
        const s = st.find(x => x.id === id); if (!s) return; rest -= s.bytes || 0;
        const seg = h('i'); seg.style.setProperty('--share', (s.bytes || 0) / total); seg.style.setProperty('--a', a); stk.append(seg);
        const k = h('span'), swt = h('i'); swt.style.setProperty('--a', a); k.append(swt, word + ' ', h('b', '', keep === 'off' ? 'Off' : keep == null ? '—' : days(keep))); keys.append(k);
      });
      const r = h('i'); r.style.setProperty('--share', Math.max(rest / total, 0.004)); r.style.setProperty('--a', 'var(--seg-d)'); stk.append(r);
      sv.append(stk, keys);
    }
    fill($('[data-part="storage"]'), { part: 'storage', name: NAME.storage, fig: F.bytes(total), unit: 'on disk', vis: sv });
    const folders = S.folders('projectsFolders');
    fill($('[data-part="projects"]'), { part: 'projects', name: NAME.projects, fig: String(S.projects.list.length), cap: folders.length ? `Project folders: ${folders.length}` : 'Detected from git repos' });
    const pend = S.pending().length;
    fill($('[data-part="splice"]'), { part: 'splice', name: NAME.splice, fig: S.version.installed || '—', cap: pend ? `${pend} waiting for restart` : S.version.previous ? `Rollback: ${S.version.previous}` : '' });
    const rare = S.knobs.filter(k => rareKey(k.key)).length;
    fill($('[data-part="internals"]'), { part: 'internals', name: NAME.internals, fig: String(rare), unit: 'settings', cap: 'Queue, uploads, memory, logs, metrics' });
    $('#mcEyebrow').textContent = S.version.installed ? `Splice ${S.version.installed}` : 'Splice';
    $('#mcRunning').textContent = `${Deck.count(S.running.requests, 'request')} running`;
    Deck.setNeed(pend, 'Restart · {n}', () => open('splice'));
    requestAnimationFrame(wires);
  }
  function drawGates() {
    const box = $('#gates');
    const head = box.querySelector('[data-part="gates"]') || (() => { const b = h('button', 'pt press'); b.type = 'button'; b.dataset.part = 'gates'; box.append(b); return b; })();
    const rows = S.item('maxInflight').rows, read = Object.keys(S.week).length > 0;
    const busy = rows.filter(r => r.peak > 0), shown = busy.length ? busy : rows.slice(0, 3), idle = rows.length - shown.length;
    const top = busy[0];
    fill(head, { part: 'gates', name: NAME.gates, fig: read ? String(top ? top.peak : 0) : '—', unit: 'peak today', cap: 'Peak today / limit, per provider' });
    box.querySelectorAll('.gl').forEach(n => n.remove());
    shown.forEach(r => {
      const lim = S.valueOf('maxInflight', r.cmd), b = h('button', 'gl press'); b.type = 'button'; b.dataset.gate = r.cmd; b.style.setProperty('--pc', pc(r.cmd));
      const nm = h('span', 'gl-nm'); nm.append(h('i'), provName(r.cmd)); nm.title = cmdName(r.cmd);
      const bar = h('span', 'gl-bar'), fillI = h('i'); fillI.style.setProperty('--u', lim ? Math.max(0.015, r.peak / lim) : 0.015); bar.append(fillI);
      const val = h('span', 'gl-val'); val.append(h('b', '', read ? String(r.peak) : '—'), ` of ${lim || 'no limit'}`);
      const go = h('span', 'pt-go'); go.innerHTML = GO;
      b.append(nm, bar, val, go); box.append(b);
    });
    if (idle > 0) { const b = h('button', 'gl is-idle press'); b.type = 'button'; b.dataset.gate = '*'; const go = h('span', 'pt-go'); go.innerHTML = GO; b.append(h('span', 'gl-nm', `${idle} more`), h('span'), h('span', 'gl-val', '0 today'), go); box.append(b); }
  }

  /* ---------- wires: the request path, and reasoning back ---------- */
  function wires() {
    const svg = $('#wires'), f = $('#flow').getBoundingClientRect();
    const at = (sel, side) => { const r = $(sel).getBoundingClientRect(); const y = side === 'b' ? r.bottom - f.top : r.top - f.top + Math.min(r.height / 2, Deck.px('--wire-anchor-max')); return { x: side === 'r' ? r.right - f.left : side === 'l' ? r.left - f.left : r.left - f.left + r.width / 2, y }; };
    const seg = (a, b) => `M${a.x} ${a.y} C${(a.x + b.x) / 2} ${a.y} ${(a.x + b.x) / 2} ${b.y} ${b.x} ${b.y}`;
    const paths = [[at('[data-part="cc"]', 'r'), at('[data-part="gates"]', 'l')], [at('[data-part="gates"]', 'r'), at('[data-part="watch"]', 'l')]];
    const live = S.running.requests > 0;
    const back = $('[data-part="reasoning"]').getBoundingClientRect(), by = back.top - f.top + back.height / 2;
    const w = at('[data-part="watch"]', 'b'), c = at('[data-part="cc"]', 'b'), bend = Deck.px('--wire-bend');
    const d = `M${w.x} ${w.y} C${w.x} ${by} ${w.x} ${by} ${w.x - bend} ${by} L${c.x + bend} ${by} C${c.x} ${by} ${c.x} ${by} ${c.x} ${c.y}`;
    svg.innerHTML = paths.map(([a, b]) => `<path class="${live ? 'live' : ''}" d="${seg(a, b)}"/><circle cx="${b.x}" cy="${b.y}"/>`).join('') + `<path class="back" d="${d}"/><circle cx="${c.x}" cy="${c.y}"/>`;
  }
  addEventListener('resize', () => requestAnimationFrame(wires));

  /* ---------- the lens: one part's controls, grown out of the part ---------- */
  let openPart = null, openAnchor = null, busy = 0;
  function open(part, anchor, focusItem) {
    anchor = anchor || $(`[data-part="${part}"]`);
    $$('[aria-expanded="true"]').forEach(x => x.setAttribute('aria-expanded', 'false'));
    anchor.setAttribute('aria-expanded', 'true');
    openPart = part; openAnchor = anchor;
    ins.openBeside(anchor, el => {
      el.classList.toggle('wide', ['watch', 'storage', 'gates', 'limits', 'reasoning', 'cc', 'internals'].includes(part) || (!!anchor.dataset.gate && anchor.dataset.gate !== '*'));
      const hd = h('div', 'hd'), gate = anchor.dataset.gate;
      if (gate && gate !== '*') hd.append(Deck.lamp('ready', pc(gate)));
      hd.append(h('h3', '', gate ? (gate === '*' ? 'No requests today' : provName(gate)) : NAME[part]));
      const x = Deck.iconBtn('close', 'Close'); x.addEventListener('click', () => ins.close()); hd.append(x);
      el.append(hd);
      const body = h('div'); el.append(body);
      if (gate) gateLens(body, gate); else lensFor(part, body);
    }, mc);
    if (focusItem) { const row = ins.el.querySelector(`[data-item="${CSS.escape(focusItem)}"]`); if (row) { row.classList.add('is-flash'); setTimeout(() => row.classList.remove('is-flash'), Deck.px('--dur') * 6); } }
  }
  new MutationObserver(() => { if (ins.el.hidden && openAnchor) { openAnchor.setAttribute('aria-expanded', 'false'); openAnchor = null; openPart = null; } }).observe(ins.el, { attributes: true, attributeFilter: ['hidden'] });
  const typing = () => !ins.el.hidden && ins.el.contains(document.activeElement) && /^(INPUT|TEXTAREA)$/.test(document.activeElement.tagName);
  /** an ask (a shorter keep, an upgrade) waits for its answer: a read never draws the glass again under it */
  const asking = () => !ins.el.hidden && !!ins.el.querySelector('.lr-ask');
  const reopen = () => {
    if (!openPart || !openAnchor || busy || typing() || asking()) return;
    const sy = ins.el.scrollTop, gate = openAnchor.dataset.gate;
    const a = gate ? $(`.gl[data-gate="${CSS.escape(gate)}"]`) : $(`[data-part="${openPart}"]`);
    if (!a) { ins.close(); return; }
    open(openPart, a); ins.el.scrollTop = sy;
  };

  /* Modes (modes/README.md): one group a mode. Its summary and how it is answered, the tools it adds as the model sees
     them, the callers it leaves out, and one row a command with On and Off, saved at once. A command the mode's
     manifest never offers it on has no switch and says so. */
  const MODE_KIND = { door: 'Answered inside Splice', mcp: 'MCP server' };
  const MODE_LEFT_OUT = { desktop: "Not offered to Claude Desktop's own sessions", door: 'Not offered to claude on the door', head: "Not offered to a command's own sessions" };
  function modesLens(body) {
    const list = S.modes;
    if (!list) { const { r, ctl } = row('modes', 'Modes'); r.querySelector('.sv').remove(); ctl.append(h('span', 'faint', 'Not read')); body.append(r); return; }
    if (!list.length) { const { r, ctl } = row('modes', 'Modes'); r.querySelector('.sv').remove(); ctl.append(h('span', 'faint', 'This Splice carries no mode')); body.append(r); return; }
    for (const m of list) {
      body.append(h('div', 'eyebrow lr-sec', m.name));
      const about = row('mode:' + m.name, m.summary || m.name, [MODE_KIND[m.kind] || m.kind, ...(m.callers_off || []).map(c => MODE_LEFT_OUT[c]).filter(Boolean)].join(' · '));
      about.r.querySelector('.sv').remove();
      const tools = h('div', 'kvs fill');
      (m.tools || []).forEach(t => { const d = h('div'); d.append(h('span', '', t.description || t.name), h('b', '', t.offered_as || t.name)); tools.append(d); });
      if (!(m.tools || []).length) tools.append(h('div', 'faint', 'No tools'));
      about.ctl.append(tools);
      if ((m.hooks || []).length) {
        const hooks = h('div', 'kvs fill lr-sub');
        m.hooks.forEach(hk => {
          const w = typeof hk.worker === 'string' ? hk.worker : hk.worker && hk.worker.kind ? hk.worker.kind : 'in-process';
          const d = h('div');
          d.append(h('span', '', `Hook · ${hk.moment}`), h('b', '', `${w} · ${hk.timeout_s}s · ${hk.on_failure}`));
          hooks.append(d);
        });
        about.r.append(hooks);
      }
      body.append(about.r);
      // one line a command, as a setting's per-command values are drawn: its dot and name, the switch, what splice said
      const box = h('div', 'lr'); box.dataset.item = 'mode:' + m.name + ':cmds';
      const sub = h('div', 'lr-sub');
      for (const [cmd, st] of Object.entries(m.heads || {})) {
        const line = h('div', 'lr-cmd'), nm = h('span'), dot = h('i'); dot.style.setProperty('--pc', pc(cmd)); nm.append(dot, provName(cmd)); nm.title = cmdName(cmd);
        line.dataset.mode = m.name; line.dataset.cmd = cmd; line.dataset.item = 'mode:' + m.name + ':' + cmd;
        if (st.from === 'never') line.append(nm, h('span', 'faint small', 'Never offered on this command'), h('span'));
        else {
          const sv = Deck.saveState({ rest: () => '' });
          // a refused save puts the switch back where splice holds it now, which an earlier save on this glass may have moved
          const held = () => { const now = ((S.modes || []).find(x => x.name === m.name) || m).heads[cmd]; return !!(now && now.on); };
          let c; c = Deck.choice([{ v: true, label: 'On' }, { v: false, label: 'Off' }], !!st.on, v => commit(line, sv, () => S.setMode(m.name, cmd, v), () => c.set(held())), { label: m.name + ' on ' + provName(cmd) });
          const right = h('span', 'lr-acts'); right.append(sv);
          line.append(nm, c, right);
        }
        sub.append(line);
      }
      box.append(sub); body.append(box);
    }
  }
  function lensFor(part, body) {
    if (part === 'internals') return internalsLens(body);
    if (part === 'modes') return modesLens(body);
    PARTS[part].map(S.item).filter(i => i && !i.adv).forEach(i => body.append(...render(i, 'main')));
  }
  function internalsLens(body) {
    for (const sec of S.deck) {
      const items = sec.items.filter(i => i.adv || (i.rows || []).some(r => r.adv));
      if (!items.length) continue;
      body.append(h('div', 'eyebrow lr-sec', sec.title));
      items.forEach(i => body.append(...render(i, i.adv ? 'all' : 'rare')));
    }
  }
  const keepRow = (mode, r) => mode === 'all' || (mode === 'rare' ? !!r.adv : !r.adv);

  /** one row: name (and a short line), the state at its end (at rest, where its value comes from), the control under it */
  function row(id, name, sub, key, cmd) {
    const r = h('div', 'lr'); r.dataset.item = id;
    const nm = h('div', 'lr-nm', name); if (sub) nm.append(h('small', '', sub));
    const sv = Deck.saveState({ rest: () => (key ? S.sourceWord(key, cmd) : '') });
    const t = told.get(id);
    if (t && Date.now() - t.at < Deck.px('--dur') * 8) sv.set(t.state, t.word);
    else if (key) { const k = S.knob(key); if (k && !k.editable) sv.set('idle', k.readOnlyWhy || 'Set in splice.toml'); else if (k && S.pending().some(c => c.key === key && (c.cmd || null) === (cmd || null))) sv.set('restart'); }
    const ctl = h('div', 'lr-ctl');
    r.append(nm, sv, ctl);
    return { r, sv, ctl };
  }
  /** what a row last said splice answered, so a row drawn again (a read came in) keeps saying it until it settles */
  const told = new Map();
  /** run a save, say on the row what splice answered, put the control back where it refused */
  async function commit(r, sv, run, revert) {
    r.querySelector('.lr-why')?.remove();
    sv.set('saving'); busy++;
    let res;
    try { res = await run(); } finally { busy--; }
    sv.set(res.state, res.word);
    if (r.dataset.item && (res.state === 'applied' || res.state === 'saved')) told.set(r.dataset.item, { state: res.state, word: res.word, at: Date.now() });
    if (res.state === 'refused') { revert && revert(); r.append(h('div', 'lr-why', res.why || 'Not saved')); }
    draw();
    return res;
  }
  const choice = (opts, v, on) => Deck.choice(opts, v, on);
  /** a picked value on its steps; a value that is not one of them is drawn as its own step, so the row reads what holds */
  const notch = (stops, v, on, marks) => {
    const list = v != null && !stops.some(s => s.v === v) && typeof v === 'number' && stops.every(s => typeof s.v === 'number' || s.v === 'off')
      ? [...stops, { v, label: String(v) }].sort((a, b) => (a.v === 'off' ? -1 : b.v === 'off' ? 1 : a.v === 0 ? 1 : b.v === 0 ? -1 : a.v - b.v)) : stops;
    return Deck.notch(list, v, on, { format: x => String(x), marks });
  };

  function render(item, mode = 'main') {
    const out = [];
    switch (item.kind) {
      case 'pick': case 'toggle': case 'steps': {
        if (!S.knob(item.key)) break;
        const opts = item.kind === 'toggle' ? [{ v: false, label: 'Off' }, { v: true, label: 'On' }] : item.options;
        const { r, sv, ctl } = row(item.id, item.label, item.hint, item.key);
        const make = (cmd, onSv, onRow) => {
          let c;
          const save = v => commit(onRow, onSv, () => S.save(item.key, v, cmd), () => c.set(S.valueOf(item.key, cmd)));
          c = item.kind === 'steps' ? notch(item.stops, S.valueOf(item.key, cmd), save) : choice(opts, S.valueOf(item.key, cmd), save);
          return c;
        };
        ctl.append(make(null, sv, r));
        out.push(r);
        if (item.perCommand) out.push(perCommand(item, make));
        break;
      }
      case 'pair': {
        const parts = item.parts.filter(p => S.knob(p.key));
        if (!parts.length) break;
        const { r, sv, ctl } = row(item.id, item.label, null, parts[0].key);
        const build = (cmd, onSv, onRow) => {
          const wrap = h('div', 'lr-ctl');
          const cs = parts.map(p => {
            const box = h('div'); box.append(h('div', 'eyebrow', p.label));
            let c; c = choice(p.options, S.valueOf(p.key, cmd), v => commit(onRow, onSv, () => S.save(p.key, v, cmd), () => c.set(S.valueOf(p.key, cmd))).then(dim));
            box.append(c); wrap.append(box); return c;
          });
          const dim = () => { if (cs[1]) cs[0].classList.toggle('is-dim', cs[1].value() === 'off'); };
          dim(); return wrap;
        };
        const w = build(null, sv, r); ctl.replaceWith(w);
        out.push(r, perCommand({ ...item, key: parts[0].key, keys: parts.map(p => p.key) }, build));
        break;
      }
      case 'combo': {
        if (!item.keys.every(k => S.knob(k))) break;
        const { r, sv, ctl } = row(item.id, item.label, null, item.keys[1]);
        let c; c = notch(item.stops, S.comboValue(item.keys), v => commit(r, sv, () => S.saveCombo(item.keys, v), () => c.set(S.comboValue(item.keys))));
        ctl.append(c); out.push(r); break;
      }
      case 'timeline': {
        const longest = item.longest;
        if (mode === 'main') {
          const c0 = h('div', 'lr'), cs = h('div', 'lr-cons');
          cs.append(h('span', '', `This week: ${n0(item.silent)} stalled over ${F.dur(S.valueOf('streamIdleMs'))}${longest ? ` · longest ${F.exact(longest)}` : ''}`), h('span', '', `${n0(item.gaveUp)} gave up`));
          c0.append(cs); out.push(c0);
        }
        item.rows.filter(t => keepRow(mode, t) && S.knob(t.key)).forEach(t => {
          const { r, sv, ctl } = row(t.key, t.label, t.hint, t.key);
          const lo = t.stops[0].v, hi = t.stops[t.stops.length - 1].v;
          const marks = longest && longest >= lo && longest <= hi ? [{ at: longest, word: F.exact(longest), c: 'var(--ink)', show: true }] : [];
          let c; c = notch(t.stops, S.valueOf(t.key), v => commit(r, sv, () => S.save(t.key, v), () => c.set(S.valueOf(t.key))), marks);
          ctl.append(c); out.push(r);
        });
        break;
      }
      case 'perCommandLimit': {
        if (!S.knob(item.key)) break;
        const { r, sv, ctl } = row(item.id, 'Default limit', 'Providers without a custom limit', item.key);
        let c; c = notch(item.stops, S.valueOf(item.key), v => commit(r, sv, () => S.save(item.key, v), () => c.set(S.valueOf(item.key))));
        ctl.append(c); out.push(r);
        const own = Object.keys(S.knob(item.key).own);
        if (own.length) {
          const sub = h('div', 'lr'), list = h('div', 'kvs lr-sub');
          own.forEach(cmd => { const d = h('div'); d.append(h('span', '', provName(cmd)), h('b', '', String(S.knob(item.key).own[cmd] || 'No limit'))); list.append(d); });
          sub.append(h('div', 'lr-nm', 'Custom limits'), h('span'), list); out.push(sub);
        }
        break;
      }
      case 'stores': item.rows.filter(s => keepRow(mode, s) && keysOf({ rows: [s] }).length).forEach(s => out.push(storeRow(s))); break;
      case 'outlets': out.push(...outlets(item)); break;
      case 'share': {
        const { r, sv, ctl } = row(item.id, item.label);
        let c; c = choice(item.options, S.mcp.shared, v => commit(r, sv, () => S.setShare(v), () => c.set(S.mcp.shared)));
        ctl.append(c); out.push(r); break;
      }
      case 'folders': if (S.knob(item.key)) out.push(folders(item)); break;
      case 'list': {
        const { r, ctl } = row(item.id, `${item.label} · ${item.rows.length}`); r.querySelector('.sv').remove();
        const list = h('div', 'kvs fill'); item.rows.forEach(x => { const d = h('div'); d.append(h('span', '', x.name), h('b', '', x.value || '')); list.append(d); });
        if (!item.rows.length) list.append(h('div', 'faint', 'None'));
        ctl.append(list); out.push(r); break;
      }
      case 'info': {
        if (!item.rows.length) break;
        const { r, ctl } = row(item.id, item.label); r.querySelector('.sv').remove();
        const list = h('div', 'kvs fill'); item.rows.forEach(x => { const d = h('div'); d.append(h('span', '', x.label), h('b', '', x.value)); list.append(d); });
        ctl.append(list); out.push(r); break;
      }
      case 'version': out.push(versionRow(item)); break;
      case 'restart': out.push(...restartRows()); break;
      case 'otlp': if (S.knob(item.keys.endpoint)) out.push(...otlpRows(item)); break;
    }
    return out;
  }

  /* per command: a command's own value as a compact line under its setting, with Back to default; Add provider */
  function perCommand(item, make) {
    const keys = item.keys || [item.key];
    const box = h('div', 'lr'); box.dataset.item = item.id + ':cmds';
    const sub = h('div', 'lr-sub');
    const cmds = [...new Set(keys.flatMap(k => Object.keys(S.knob(k) ? S.knob(k).own : {})))];
    cmds.forEach(cmd => {
      const line = h('div', 'lr-cmd'), nm = h('span'), dot = h('i'); dot.style.setProperty('--pc', pc(cmd)); nm.append(dot, provName(cmd));
      const sv = Deck.saveState({ rest: () => '' });
      const back = h('button', 'btn quiet press', 'Back to default'); back.type = 'button';
      back.addEventListener('click', () => commit(box, sv, async () => {
        let st = { state: 'applied' };
        for (const k of keys) if (cmd in S.knob(k).own) { const res = await S.save(k, null, cmd); if (res.state === 'refused') return res; if (res.state === 'restart') st = res; }
        return st;
      }).then(reopen));
      const ctl = make(cmd, sv, box), right = h('span', 'lr-acts'); right.append(back, sv);
      line.append(nm, ctl, right); sub.append(line);
    });
    const add = h('button', 'btn quiet press', 'Add provider'); add.type = 'button';
    add.addEventListener('click', () => Deck.openMenu(add, S.commands.filter(c => !cmds.includes(c.key)).map(c => ({ label: provName(c.key), sub: c.label, run: async () => {
      for (const k of keys) await S.save(k, S.valueOf(k), c.key);
      draw(); reopen();
    } })), 'down'));
    const acts = h('div', 'lr-acts'); acts.append(add); sub.append(acts);
    box.append(sub); return box;
  }

  function gateLens(body, cmd) {
    const item = S.item('maxInflight');
    if (cmd === '*') {
      const list = h('div', 'kvs');
      item.rows.filter(r => !r.peak).forEach(r => { const d = h('div'); d.append(h('span', '', provName(r.cmd)), h('b', '', `limit ${S.valueOf('maxInflight', r.cmd) || 'none'}`)); list.append(d); });
      body.append(list); return;
    }
    const r0 = item.rows.find(r => r.cmd === cmd) || { peak: 0 };
    const own = cmd in S.knob('maxInflight').own;
    const { r, sv, ctl } = row('maxInflight', item.label, own ? 'Custom limit' : 'Default limit', 'maxInflight', cmd);
    const cons = h('div', 'lr-cons');
    // the consequence, in this command's own figures: today's peak against the limit
    const say = lim => { cons.replaceChildren(h('span', '', lim ? `Limit ${lim}` : 'No limit'), h('span', '', lim && lim < r0.peak ? `Peak today: ${r0.peak}, so ${r0.peak - lim} would have queued` : `Peak today: ${r0.peak}, within the limit`)); };
    let c; c = notch(item.stops, S.valueOf('maxInflight', cmd), v => { say(v); commit(r, sv, () => S.save('maxInflight', v, cmd), () => { c.set(S.valueOf('maxInflight', cmd)); say(S.valueOf('maxInflight', cmd)); }); }, [{ at: r0.peak || 1, word: `Peak ${r0.peak}`, c: pc(cmd), show: true }]);
    ctl.append(c);
    if (own) { const b = h('button', 'btn quiet press', 'Back to default'); b.type = 'button'; b.addEventListener('click', () => commit(r, sv, () => S.save('maxInflight', null, cmd)).then(reopen)); ctl.append(b); }
    r.append(cons); say(S.valueOf('maxInflight', cmd));
    body.append(r);
    // the rest of the gate: the settings every command shares
    ['maxQueued', 'maxRequestBytes'].map(S.item).filter(Boolean).forEach(i => body.append(...render(i, 'main')));
  }

  function storeRow(s) {
    const key = s.key || s.toggle || s.combo.keys[1];
    const { r, sv, ctl } = row(s.id, s.label, s.size == null ? '' : F.bytes(s.size), key);
    let c;
    const cur = () => (s.combo ? S.comboValue(s.combo.keys) : S.valueOf(s.key || s.toggle));
    const save = v => (s.combo ? S.saveCombo(s.combo.keys, v) : s.key === 'historyRetentionDays' ? S.shortenHistory(v, null) : S.save(s.key || s.toggle, v));
    // a shorter keep asks first, saying what it deletes, and saves only on the yes
    const ask = async v => {
      r.querySelector('.lr-ask')?.remove();
      const before = cur(), shorter = typeof v === 'number' && (before === 'forever' || before === 'off' || typeof before !== 'number' || v < before);
      const d = s.asks && shorter ? await S.wouldDelete(s.key || s.combo.keys[1], v) : null;
      if (!d || !d.bytes) return commit(r, sv, () => save(v), () => c.set(cur()));
      const a = h('div', 'lr-ask');
      a.append(h('span', '', `Deletes ${F.bytes(d.bytes)} · ${Deck.count(d.items, d.word.replace(/s$/, ''), d.word)}${d.estimated ? ', about' : ''}`));
      const del = h('button', 'btn primary press', 'Delete'), no = h('button', 'btn quiet press', 'Cancel'); del.type = no.type = 'button';
      del.addEventListener('click', () => { a.remove(); commit(r, sv, () => (s.key === 'historyRetentionDays' ? S.shortenHistory(v, d.cutoff) : save(v)), () => c.set(cur())).then(() => S.read().then(() => { draw(); reopen(); })); });
      no.addEventListener('click', () => { a.remove(); c.set(cur()); });
      a.append(no, del); r.append(a);
    };
    c = s.toggle ? choice([{ v: false, label: 'Off' }, { v: true, label: 'On' }], cur(), v => commit(r, sv, () => save(v), () => c.set(cur()))) : notch(s.stops, cur(), ask);
    ctl.append(c); return r;
  }

  function outlets(item) {
    const out = [];
    item.outlets.forEach(o => {
      const { r, sv, ctl } = row('alerts.' + o.id, o.label, o.readOnly ? o.source : '');
      if (o.readOnly) { sv.set('idle', o.value); ctl.remove(); }
      else if (o.kind === 'toggle') { let c; c = choice([{ v: false, label: 'Off' }, { v: true, label: 'On' }], !!S.alerts[o.id], v => commit(r, sv, () => S.setAlert(o.id, v), () => c.set(!!S.alerts[o.id]))); ctl.append(c); }
      else {
        const i = h('input', 'lr-in'); i.placeholder = 'https://'; i.value = S.alerts[o.id] || ''; i.spellcheck = false;
        const go = () => { if ((i.value.trim() || null) === (S.alerts[o.id] || null)) return; const before = S.alerts[o.id] || ''; commit(r, sv, () => S.setAlert(o.id, i.value.trim()), () => { i.value = before; }); };
        i.addEventListener('keydown', e => { if (e.key === 'Enter') { e.preventDefault(); go(); } e.stopPropagation(); }); i.addEventListener('change', go); ctl.append(i);
      }
      out.push(r);
    });
    const { r, sv, ctl } = row('alerts.events', item.events.join(' · '));
    r.querySelector('.lr-nm').classList.add('faint');
    const t = h('button', 'btn press', item.action.label); t.type = 'button';
    t.addEventListener('click', () => commit(r, sv, () => S.testAlert()));
    ctl.append(t); out.push(r);
    return out;
  }

  function folders(item) {
    const { r, sv, ctl } = row(item.id, item.label, item.hint, item.key);
    const list = h('div', 'kvs fill');
    const paint = () => {
      list.replaceChildren();
      const fs = S.folders(item.key);
      if (!fs.length) list.append(h('div', 'faint', item.empty));
      fs.forEach(p => { const d = h('div'), rm = h('button', 'btn quiet press', 'Remove'); rm.type = 'button'; rm.addEventListener('click', () => commit(r, sv, () => S.removeFolder(item.key, p)).then(paint)); d.append(h('b', '', p), rm); list.append(d); });
    };
    paint();
    const add = h('button', 'btn quiet press', 'Add folder'); add.type = 'button';
    add.addEventListener('click', () => Deck.openMenu(add, item.candidates.filter(p => !S.folders(item.key).includes(p)).map(p => ({ label: p, sub: Deck.count(S.projectsUnder(p), 'project'), run: () => commit(r, sv, () => S.addFolder(item.key, p)).then(paint) })), 'down'));
    ctl.append(list, add); return r;
  }

  /** what restarts splice (Restart now, an upgrade, a rollback) asks first only while requests are running, since the
   *  restart cuts other sessions' live turns; with nothing running it acts on the one press */
  function askIfRunning(r, word, go) {
    r.querySelector('.lr-ask')?.remove();
    if (!S.running.requests) { go(); return; }
    const b = (cls, w, f) => { const x = h('button', cls + ' press', w); x.type = 'button'; x.addEventListener('click', f); return x; };
    const a = h('div', 'lr-ask');
    a.append(h('span', '', `Restarts splice · ${Deck.count(S.running.requests, 'request')} running ${S.running.requests === 1 ? 'is' : 'are'} cut`), b('btn quiet', 'Cancel', () => a.remove()), b('btn primary', word, () => { a.remove(); go(); }));
    r.append(a);
  }
  function versionRow(item) {
    const { r, sv, ctl } = row('version', item.label);
    const paint = () => {
      ctl.replaceChildren(h('span', 'vbig', S.version.installed || '—'));
      const run = h('span', 'faint small mono', `${n0(S.running.requests)} running`);
      const btn = (cls, word, go) => { const b = h('button', cls + ' press', word); b.type = 'button'; b.addEventListener('click', go); return b; };
      const askThen = (word, go) => askIfRunning(r, word, go);
      if (S.version.latest && S.version.latest !== S.version.installed) ctl.append(btn('btn', `Upgrade to ${S.version.latest}`, () => askThen('Upgrade', () => commit(r, sv, () => S.upgrade()))), run);
      else ctl.append(btn('btn quiet', 'Check', () => commit(r, sv, () => S.checkVersion()).then(paint)));
      if (S.version.previous) ctl.append(btn('btn quiet', `Rollback to ${S.version.previous}`, () => askThen('Rollback', () => commit(r, sv, () => S.goBack()))));
    };
    paint(); return r;
  }

  function restartRows() {
    const out = [], pend = S.pending();
    const { r, sv, ctl } = row('restart', 'Restart', pend.length ? `${pend.length} waiting` : '');
    pend.forEach(c => { const d = h('div', 'ch span-all'); d.append(h('span', '', labelOf(c.key) + (c.cmd ? ` · ${provName(c.cmd)}` : '')), h('b', '', show(c.key, c.to)), h('span')); r.append(d); });
    const now = h('button', 'btn' + (pend.length ? ' primary' : '') + ' press', 'Restart now'), idle = h('button', 'btn quiet press', 'Restart when idle'); now.type = idle.type = 'button';
    const run = h('span', 'faint small mono', `${n0(S.running.requests)} running`);
    // the row keeps saying Back once splice answers again; what waited for the restart has gone from it
    const back = res => { if (res.state !== 'refused') r.querySelectorAll('.ch').forEach(x => x.remove()); };
    now.addEventListener('click', () => askIfRunning(r, 'Restart now', () => commit(r, sv, () => S.restart(true)).then(back)));
    idle.addEventListener('click', () => commit(r, sv, () => S.restart(false)).then(back));
    ctl.append(now, idle, run); out.push(r);
    return out;
  }
  /** a setting's name, as the deck words it */
  function labelOf(key) {
    for (const sec of S.deck) for (const it of sec.items) {
      if (it.key === key) return it.label;
      const p = (it.parts || []).find(x => x.key === key); if (p) return `${it.label} · ${p.label}`;
      const rr = (it.rows || []).find(x => x.key === key || x.toggle === key || (x.combo && x.combo.keys.includes(key))); if (rr) return rr.label;
      if (it.keys && (Array.isArray(it.keys) ? it.keys : Object.values(it.keys)).includes(key)) return it.label;
    }
    return key;
  }
  /** a stored value as the words its control uses */
  function show(key, v) {
    if (v === null || v === undefined) return 'Default';
    if (typeof v === 'boolean') return v ? 'On' : 'Off';
    for (const sec of S.deck) for (const it of sec.items) {
      const pools = [it.options, it.stops, ...(it.parts || []).map(p => p.key === key && p.options), ...(it.rows || []).map(x => (x.key === key || (x.combo && x.combo.keys[1] === key)) && x.stops)];
      for (const pool of pools) { const o = (pool || []).find(x => x && x.v === v); if (o) return o.label; }
    }
    return typeof v === 'number' && v >= 1000 && key.endsWith('Ms') ? F.dur(v) : String(v);
  }

  function otlpRows(item) {
    const K = item.keys, out = [];
    { const { r, sv, ctl } = row('otlp', item.label, null, K.endpoint);
      const i = h('input', 'lr-in'); i.placeholder = item.placeholder; i.value = S.valueOf(K.endpoint) || ''; i.spellcheck = false;
      const go = () => { const v = i.value.trim(); if (v === (S.valueOf(K.endpoint) || '')) return; const before = S.valueOf(K.endpoint) || ''; commit(r, sv, () => S.save(K.endpoint, v), () => { i.value = before; }).then(reopen); };
      i.addEventListener('keydown', e => { if (e.key === 'Enter') { e.preventDefault(); go(); } e.stopPropagation(); }); i.addEventListener('change', go);
      ctl.append(i); out.push(r); }
    const on = !!S.valueOf(K.endpoint);
    if (S.knob(K.protocol)) { const { r, sv, ctl } = row('otlp.protocol', 'Protocol', null, K.protocol);
      let c; c = choice(item.protocols, S.valueOf(K.protocol), v => commit(r, sv, () => S.save(K.protocol, v), () => c.set(S.valueOf(K.protocol))));
      ctl.append(c); r.classList.toggle('is-dim', !on); out.push(r); }
    if (S.knob(K.signals)) { const { r, sv, ctl } = row('otlp.signals', 'Signals', null, K.signals);
      const cur = () => String(S.valueOf(K.signals) || '').split(',').filter(Boolean);
      item.signals.forEach(sg => { const box = h('div'); box.append(h('div', 'eyebrow', sg.label)); let c; c = choice([{ v: false, label: 'Off' }, { v: true, label: 'On' }], cur().includes(sg.v), v => { const set = new Set(cur()); v ? set.add(sg.v) : set.delete(sg.v); commit(r, sv, () => S.save(K.signals, [...set].join(',')), () => c.set(cur().includes(sg.v))); }); box.append(c); ctl.append(box); });
      r.classList.toggle('is-dim', !on); out.push(r); }
    if (S.knob(K.headers)) { const { r, sv } = row('otlp.headers', 'Headers'); sv.set('idle', S.valueOf(K.headers) && S.valueOf(K.headers) !== '{}' ? 'Set' : 'None'); r.querySelector('.lr-ctl').remove(); r.classList.toggle('is-dim', !on); out.push(r); }
    return out;
  }

  /* ---------- pressing a part ---------- */
  mc.addEventListener('click', e => {
    if (!S.deck.length) return;
    const g = e.target.closest('.gl'); if (g) { if (ins.open === g) ins.close(); else open('gates', g); return; }
    const p = e.target.closest('.pt'); if (!p || !p.dataset.part) return;
    if (ins.open === p) ins.close(); else open(p.dataset.part, p);
  });

  /* ---------- "/" finds a setting, by its name, its words or its key ---------- */
  const find = Deck.glass('lens find', document.body), fin = h('input'), flist = h('div', 'find-list');
  fin.placeholder = 'Search'; fin.setAttribute('aria-label', 'Search settings'); find.append(fin, flist);
  let fOff = null, fSel = 0, fHits = [];
  const partOfItem = () => Object.fromEntries(Object.entries(PARTS).flatMap(([p, ids]) => ids.map(id => [id, p])));
  const itemOfKey = key => { for (const sec of S.deck) for (const it of sec.items) if (keysOf(it).includes(key)) return it; return null; };
  const ALSO = { timeout: ['upstreamTimeoutMs', 'firstByteTimeoutMs', 'streamIdleMs', 'mcpRequestTimeoutMs', 'requestReadTimeoutMs'], stuck: ['streamIdleMs', 'stallReanchorMs', 'upstreamTimeoutMs'], disk: ['historyRetentionDays', 'transcriptCopyDays', 'traceRetentionDays', 'activityRetentionDays'], quota: ['quotaPoll', 'usageWarnPct'], memory: ['materializationHeapBytes', 'mcpMaxServers'] };
  function paintFind() {
    const q = fin.value.trim().toLowerCase(), parts = partOfItem();
    const index = S.knobs.map(k => { const it = itemOfKey(k.key); return { k, it, part: it ? parts[it.id] : null, label: labelOf(k.key) }; }).filter(x => x.part)
      .map(x => ({ ...x, words: [x.label, x.k.key, x.it.label].join(' ').toLowerCase() }));
    fHits = !q ? [] : index.filter(x => x.words.includes(q) || Object.entries(ALSO).some(([w, ks]) => w.startsWith(q) && ks.includes(x.k.key))).slice(0, 12);
    fSel = Math.min(fSel, Math.max(0, fHits.length - 1));
    flist.replaceChildren(...fHits.map((x, i) => { const b = h('button', i === fSel ? 'is-on' : ''); b.type = 'button'; b.append(h('span', '', x.label), h('b', '', rareKey(x.k.key) ? NAME.internals : NAME[x.part])); b.addEventListener('click', () => go(x)); return b; }));
  }
  const go = x => { closeFind(); const rows = x.it.rows && x.it.kind === 'stores' ? (x.it.rows.find(r => keysOf({ rows: [r] }).includes(x.k.key)) || {}).id : x.it.kind === 'timeline' ? x.k.key : x.it.id; open(rareKey(x.k.key) ? 'internals' : x.part, null, rows); };
  const closeFind = () => { if (find.hidden) return; fOff && fOff(); fOff = null; Deck.hide(find); };
  fin.addEventListener('input', () => { fSel = 0; paintFind(); });
  fin.addEventListener('keydown', e => {
    if (e.key === 'ArrowDown' || e.key === 'ArrowUp') { e.preventDefault(); fSel = Math.max(0, Math.min(fHits.length - 1, fSel + (e.key === 'ArrowDown' ? 1 : -1))); paintFind(); }
    if (e.key === 'Enter' && fHits[fSel]) { e.preventDefault(); go(fHits[fSel]); }
  });
  addEventListener('keydown', e => {
    if (e.key !== '/' || /INPUT|TEXTAREA/.test(document.activeElement.tagName)) return;
    e.preventDefault(); ins.close(); fin.value = ''; paintFind();
    Deck.show(find, [find.offsetWidth / 2, 0]); fOff = Deck.layer(find, closeFind); fin.focus();
  });

  /* ---------- clock and the reads ---------- */
  const clock = () => { $('#mcClock').textContent = Deck.clock(); };
  clock(); setInterval(clock, 30000);
  S.onWeek(() => { if (!busy) { draw(); reopen(); } });
  /* an address names a part to open (other pages' doors: #data, #projects), and only a part this page has */
  const HASH = { data: 'storage', storage: 'storage', projects: 'projects', mcp: 'mcp', modes: 'modes', limits: 'limits', alerts: 'limits', version: 'splice', splice: 'splice', reasoning: 'reasoning', stalls: 'watch', gates: 'gates', advanced: 'internals' };
  let hashDone = false;
  const openFromHash = () => {
    if (hashDone) return; hashDone = true;
    const k = decodeURIComponent(location.hash.slice(1));
    if (Object.hasOwn(HASH, k)) open(HASH[k]);
  };
  let reading = false;
  const readNow = async () => {
    if (reading || busy || typing() || document.hidden || document.body.classList.contains('locked')) return;
    reading = true;
    try {
      const ok = await S.read();
      if (ok) { draw(); reopen(); openFromHash(); } else $('#mcRunning').textContent = 'Not answering';
    } finally { reading = false; }
  };
  SP.ready.then(readNow);
  setInterval(readNow, READ_MS);
  addEventListener('splice:signed-in', readNow);
})();
