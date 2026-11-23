/* Settings' Desktop policy (window.DESKTOP_POLICY), for deck-settings.js: the Claude Desktop policy keys a person sets
   from splice, in the five groups the daemon gives, each saved the moment it is picked, with Reset where the console
   holds a value of its own. Every word, value, default and group is the daemon's; the page only draws them.

   Reads: GET /api/desktop/policy ({ groups: [{ id, title, keys: [{ key, label, description, type, options?, min?, max?,
   default, effective, override, overridden }] }], restart_required, overrides_error, ignored }).
   Writes: PATCH /api/desktop/policy ({ key: value } to set one, { key: null } to put it back to the default).
   A refused value keeps its reason under its row across every later read, until that key saves. */
window.DESKTOP_POLICY = (() => {
  'use strict';
  const { h } = Deck;
  const P = { data: null, status: null };
  /** what splice last refused for each key, kept until that key saves */
  const errors = new Map();
  /** what a row last said splice answered, so a row drawn again keeps saying it for a moment */
  const told = new Map();
  const why = res => (res.status === 0 ? 'splice is not answering' : (res.body && res.body.error) || 'splice answered ' + res.status);

  /** the words each choice's values are drawn with; a value with none is drawn as it is */
  const WORDS = {
    standard: 'Standard', simple: 'Simple', 'no-vm': 'No VM', off: 'Off', low: 'Low', medium: 'Medium', high: 'High',
    xhigh: 'Extra high', max: 'Max', manual: 'Ask first', acceptEdits: 'Accept edits', plan: 'Plan', auto: 'Auto',
    dontAsk: "Don't ask", bypassPermissions: 'Bypass', allow: 'Allow', block: 'Block', detect: 'Detect', show: 'Show',
  };
  const word = v => WORDS[v] || String(v);
  const SWITCH = [{ v: false, label: 'Off' }, { v: true, label: 'On' }];

  P.read = async () => {
    const res = await API.get('/api/desktop/policy');
    P.status = res.status;
    if (res.ok && res.body && Array.isArray(res.body.groups)) P.data = res.body;
    else if (res.status === 404) P.data = null;
    return res.ok;
  };
  const keys = () => (P.data ? P.data.groups.flatMap(g => g.keys) : []);
  const field = key => keys().find(k => k.key === key);

  /** save [changes] ({ key: value | null }); the daemon's answer as a row reads it */
  P.save = async changes => {
    const res = await API.patch('/api/desktop/policy', changes);
    if (!res.ok || !res.body || !Array.isArray(res.body.groups)) {
      const per = res.body && res.body.keys && typeof res.body.keys === 'object' ? res.body.keys : {};
      const said = why(res);
      Object.keys(changes).forEach(k => errors.set(k, per[k] || said));
      return { state: 'refused', why: Object.keys(changes).map(k => errors.get(k)).find(Boolean) || said };
    }
    Object.keys(changes).forEach(k => errors.delete(k));
    P.data = res.body;
    const reset = Object.values(changes).every(v => v === null);
    return { state: 'applied', word: reset ? 'Default' : res.body.restart_required ? 'Saved · reopen Desktop' : 'Saved' };
  };

  /** the part's figure on the field: how many keys the console sets, and whether Desktop must be reopened */
  P.summary = () => {
    if (!P.data) return { fig: '—', cap: P.status === 503 ? 'Opens once the door is up' : 'Not read' };
    const set = keys().filter(k => k.overridden).length;
    const cap = P.data.overrides_error ? 'Overrides file unreadable' : P.data.restart_required ? 'Reopen Claude Desktop to apply' : 'Claude Desktop policy';
    return { fig: String(set), unit: set === 1 ? 'set here' : 'set here', cap, waiting: !!P.data.restart_required, changed: set > 0 };
  };

  /* ---------- the glass ---------- */
  /** the glass's rows into [body]; [kit] is the page's: commit (a save that says on its row what splice answered),
   *  and reopen (the glass drawn again from what splice holds now) */
  P.lens = (body, kit) => {
    if (!P.data) {
      const { r, sv } = line('desktopPolicy', 'Claude Desktop policy', P.status === 503 ? 'Opens once the daemon\'s door is up' : 'Not read');
      sv.set('idle', ''); body.append(r); return;
    }
    if (P.data.restart_required) body.append(note('dp-need', 'var(--ormolu)', 'Quit Claude Desktop and open it again', 'Desktop reads its policy only when it starts'));
    if (P.data.overrides_error) body.append(note('dp-bad', 'var(--vermilion)', 'Overrides not used', P.data.overrides_error));
    if ((P.data.ignored || []).length) body.append(note('dp-bad', 'var(--ink-faint)', 'Ignored in the overrides file', P.data.ignored.join(', ')));
    for (const g of P.data.groups) {
      body.append(h('div', 'eyebrow lr-sec', g.title));
      g.keys.forEach(k => body.append(row(k, kit)));
    }
  };

  function note(cls, color, title, sub) {
    const n = h('div', 'lr dp-note ' + cls), nm = h('div', 'lr-nm');
    nm.append(Deck.lamp('next', color), ' ', title, h('small', '', sub));
    n.append(nm); return n;
  }
  /** a row: name and its line, the state at its end (at rest: Set here, or Default), the control under it */
  function line(id, name, sub, rest) {
    const r = h('div', 'lr'); r.dataset.item = 'dp:' + id;
    const nm = h('div', 'lr-nm', name); if (sub) nm.append(h('small', '', sub));
    const sv = Deck.saveState({ rest: () => rest || '' });
    const t = told.get(id);
    if (t && Date.now() - t.at < Deck.px('--dur') * 8) sv.set(t.state, t.word);
    const ctl = h('div', 'lr-ctl');
    r.append(nm, sv, ctl);
    return { r, sv, ctl };
  }

  function row(k, kit) {
    const { r, sv, ctl } = line(k.key, k.label, k.description, k.overridden ? 'Set here' : 'Default');
    const save = (changes, revert) => kit.commit(r, sv, async () => {
      const res = await P.save(changes);
      if (res.state === 'applied') told.set(k.key, { state: 'applied', word: res.word, at: Date.now() });
      return res;
    }, revert).then(res => { if (res.state !== 'refused') kit.reopen(); return res; });
    switch (k.type) {
      case 'boolean': { let c; c = Deck.choice(SWITCH, k.effective === true, v => save({ [k.key]: v }, () => c.set(k.effective === true)), { label: k.label }); ctl.append(c); break; }
      case 'enum': { let c; c = Deck.choice(k.options.map(v => ({ v, label: word(v) })), k.effective, v => save({ [k.key]: v }, () => c.set(k.effective)), { label: k.label }); ctl.append(c); break; }
      case 'integer': ctl.append(...number(k, save)); break;
      case 'hosts': ctl.append(list(k, save, x => x, text => text)); break;
      case 'marketplaces': ctl.append(list(k, save, market, marketOf)); break;
      case 'import': ctl.append(...importParts(k, save)); break;
      default: ctl.append(h('span', 'faint small', 'Not editable here'));
    }
    if (k.overridden) {
      const back = h('button', 'btn quiet press', 'Reset'); back.type = 'button';
      back.title = 'Back to ' + shown(k.default);
      back.addEventListener('click', () => save({ [k.key]: null }));
      ctl.append(back);
    }
    const said = errors.get(k.key);
    if (said) r.append(h('div', 'lr-why', said));
    return r;
  }

  /** a whole number typed in its range, saved on Enter or when the field is left */
  function number(k, save) {
    const i = h('input', 'lr-in dp-num'); i.type = 'number'; i.min = k.min; i.max = k.max; i.step = 1;
    i.value = k.effective ?? ''; i.setAttribute('aria-label', k.label);
    const go = () => {
      const v = Number(i.value);
      if (i.value === '' || v === k.effective) { i.value = k.effective ?? ''; return; }
      save({ [k.key]: Number.isInteger(v) ? v : i.value }, () => { i.value = k.effective ?? ''; });
    };
    i.addEventListener('keydown', e => { if (e.key === 'Enter') { e.preventDefault(); go(); } e.stopPropagation(); });
    i.addEventListener('change', go);
    return [i, h('span', 'faint small', `seconds · ${k.min}–${k.max}`)];
  }

  /** a list: each entry with Remove, and a field that adds one; [show] draws an entry, [parse] reads a typed one */
  function list(k, save, show, parse) {
    const box = h('div', 'dp-list'), now = Array.isArray(k.effective) ? k.effective : [];
    const items = h('div', 'kvs fill');
    if (!now.length) items.append(h('div', 'faint', 'None'));
    now.forEach((entry, at) => {
      const d = h('div'), rm = h('button', 'btn quiet press', 'Remove'); rm.type = 'button';
      rm.addEventListener('click', () => save({ [k.key]: now.filter((_, n) => n !== at) }));
      d.append(h('b', '', show(entry)), rm); items.append(d);
    });
    const add = h('div', 'dp-add'), i = h('input', 'lr-in'); i.spellcheck = false;
    i.placeholder = k.type === 'hosts' ? 'example.com or *.example.com' : 'owner/repo or https://…/marketplace.json';
    i.setAttribute('aria-label', 'Add to ' + k.label);
    const b = h('button', 'btn quiet press', 'Add'); b.type = 'button';
    const go = () => { const t = i.value.trim(); if (!t) return; save({ [k.key]: [...now, parse(t)] }); };
    b.addEventListener('click', go);
    i.addEventListener('keydown', e => { if (e.key === 'Enter') { e.preventDefault(); go(); } e.stopPropagation(); });
    add.append(i, b);
    box.append(items, add); return box;
  }
  const market = m => (m && typeof m === 'object' ? (m.source === 'github' ? m.repo : m.url) + (m.source === 'git' ? ' · git' : '') : String(m));
  /** a typed marketplace: owner/repo is GitHub's, a URL ending .git a git remote, any other URL a hosted file */
  const marketOf = t => (/^[\w.-]+\/[\w.-]+$/.test(t) ? { source: 'github', repo: t } : /\.git$/.test(t) || /^git@/.test(t) ? { source: 'git', url: t } : { source: 'url', url: t });

  /** the claude.ai import settings: whether it is offered, and how its banner shows; the rest keeps splice's default */
  function importParts(k, save) {
    const now = k.effective && typeof k.effective === 'object' ? k.effective : {};
    const mine = k.override && typeof k.override === 'object' ? k.override : {};
    const part = (label, opts, v, sub) => {
      const box = h('div'); box.append(h('div', 'eyebrow', label));
      let c; c = Deck.choice(opts, v, x => save({ [k.key]: { ...mine, [sub]: x } }, () => c.set(v)), { label });
      box.append(c); return box;
    };
    return [part('Import', SWITCH, now.enabled === true, 'enabled'), part('Banner', ['off', 'detect', 'show'].map(v => ({ v, label: word(v) })), now.bannerBehavior, 'bannerBehavior')];
  }
  /** a default as Reset's hint says it */
  const shown = v => (v === null || v === undefined ? 'none' : typeof v === 'boolean' ? (v ? 'On' : 'Off') : Array.isArray(v) ? `${v.length} entries` : typeof v === 'object' ? 'splice\'s default' : word(v));

  return P;
})();
