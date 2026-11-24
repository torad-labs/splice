/* Desktop harnesses (window.DESKTOP_HARNESSES): which program a Claude Desktop session runs on, by model family. Settings
   draws its glass (deck-settings.js); Accounts draws each provider's harness beside it and GPT's switch
   (deck-accounts.js). A GPT session runs on Claude Code through Splice unless the person opts into Codex CLI;
   Antigravity's sessions run on agy while it is on; every other provider's run on Claude Code through Splice. Every
   word, harness and default is the daemon's; the page only draws them.

   Reads: GET /api/desktop/harnesses ({ default_harness, harnesses: [{ id, name, description }], families: [{ family,
   title, description, harness, enabled, set_by, toggleable, choices, default, providers, prefixes, configured }],
   switches: [{ prefix, provider, family, harness, enabled }], restart_required, overrides_error, ignored, file }).
   Writes: PATCH /api/desktop/harnesses ({ family: { harness?, enabled? } } to choose, { family: null } for the default).
   A refused choice keeps its reason under its row across every later read, until that family saves. A read that
   fails for any reason but 404 keeps the last map drawn; a daemon without the route (404) draws none. */
window.DESKTOP_HARNESSES = (() => {
  'use strict';
  const { h } = Deck;
  const H = { data: null, status: null };
  /** what splice last refused for each family, kept until that family saves */
  const errors = new Map();
  /** what a row last said splice answered, so a row drawn again keeps saying it for a moment */
  const told = new Map();
  /** whether a save is waiting for splice's answer; every harness control waits with it */
  let saving = false;
  const why = res => (res.status === 0 ? 'splice is not answering' : (res.body && res.body.error) || 'splice answered ' + res.status);
  const SWITCH = [{ v: false, label: 'Off' }, { v: true, label: 'On' }];
  const SET_BY = { console: 'Set here', 'splice.toml': 'From splice.toml', default: 'Default' };

  /** bumped by every read and save as it starts, and by a save as it lands: a read answered after a newer read or save
   *  began is stale, and never draws over the map that save returned */
  let generation = 0;
  H.read = async () => {
    const mine = ++generation;
    const res = await API.get('/api/desktop/harnesses');
    if (mine !== generation) return res.ok;
    H.status = res.status;
    if (res.ok && res.body && Array.isArray(res.body.families)) H.data = res.body;
    else if (res.status === 404) H.data = null;
    return res.ok;
  };
  H.family = id => (H.data ? H.data.families.find(f => f.family === id) || null : null);
  /** a harness's name as the daemon gives it */
  H.name = id => ((H.data && H.data.harnesses.find(x => x.id === id)) || { name: id }).name;
  /** whether a save is in flight, so a page can hold its own controls */
  H.busy = () => saving;

  /** the harness a provider's Desktop sessions run on: its switch's, by provider key; [family] (the console's, as
   *  SPLICE names it) decides for a provider the map has no switch for. null while there is no map. */
  H.of = (key, family) => {
    if (!H.data) return null;
    const sw = H.data.switches.find(s => s.provider === key);
    // a family is read however it is cased, and a `codex` family is OpenAI's, as the daemon reads both (HarnessFamily)
    const named = String(family || '').toLowerCase();
    const fam = sw && sw.family ? H.family(sw.family) : named === 'openai' || named === 'codex' ? H.family('openai') : null;
    const harness = sw ? sw.harness : fam ? fam.harness : H.data.default_harness;
    const enabled = sw ? sw.enabled !== false : fam ? fam.enabled !== false : true;
    return { harness, name: H.name(harness), enabled, family: fam };
  };

  /** save [changes] ({ family: { harness?, enabled? } | null }); the daemon's answer as a row reads it */
  H.save = async changes => {
    if (saving) return { state: 'busy' };
    saving = true;
    ++generation;
    let res;
    try { res = await API.patch('/api/desktop/harnesses', changes); } finally { saving = false; ++generation; }
    if (!res.ok || !res.body || !Array.isArray(res.body.families)) {
      const per = res.body && res.body.families && typeof res.body.families === 'object' && !Array.isArray(res.body.families) ? res.body.families : {};
      const said = why(res);
      Object.keys(changes).forEach(k => errors.set(k, per[k] || said));
      return { state: 'refused', why: Object.keys(changes).map(k => errors.get(k)).find(Boolean) || said };
    }
    Object.keys(changes).forEach(k => errors.delete(k));
    H.data = res.body;
    const reset = Object.values(changes).every(v => v === null);
    return { state: 'applied', word: reset ? 'Default' : res.body.restart_required ? 'Saved · restart splice' : 'Saved' };
  };
  /** the reason splice last refused [family], or null */
  H.error = family => errors.get(family) || null;

  /** the part's figure on the field: the harness GPT runs on, and whether the daemon must start again */
  H.summary = () => {
    if (!H.data) return { fig: '—', cap: H.status === 503 ? 'Opens once the door is up' : 'Not read' };
    const gpt = H.family('openai'), agy = H.family('antigravity');
    const fig = gpt ? (gpt.harness === 'codex' ? 'Codex' : 'Claude Code') : '—';
    const cap = H.data.overrides_error ? 'Harness file unreadable'
      : H.data.restart_required ? 'Restart splice to apply'
        : 'Antigravity ' + (agy && agy.enabled ? 'on' : 'off');
    const set = H.data.families.filter(f => f.set_by === 'console').length;
    return { fig, unit: 'for GPT', cap, waiting: !!H.data.restart_required, changed: set > 0 };
  };

  /* ---------- Settings' glass ---------- */
  /** the glass's rows into [body]; [kit] is the page's: commit (a save that says on its row what splice answered),
   *  and reopen (the glass drawn again from what splice holds now) */
  H.lens = (body, kit) => {
    if (!H.data) {
      const { r, sv } = line('none', 'Desktop harnesses', H.status === 503 ? 'Opens once the daemon\'s door is up' : H.status === 404 ? 'This splice has no harness map' : 'Not read');
      sv.set('idle', ''); body.append(r); return;
    }
    if (H.data.restart_required) body.append(note('dp-need', 'var(--ormolu)', 'Restart splice to apply', 'The daemon asks agy for Antigravity\'s models only when it starts; then reopen Claude Desktop'));
    if (H.data.overrides_error) body.append(note('dp-bad', 'var(--vermilion)', 'Harness file not used', H.data.overrides_error));
    if ((H.data.ignored || []).length) body.append(note('dp-bad', 'var(--ink-faint)', 'Ignored in the harness file', H.data.ignored.join(', ')));
    H.data.families.forEach(f => body.append(row(f, kit)));
    const { r, sv, ctl } = line('rest', 'Every other provider', 'Their sessions run on Claude Code through Splice, on the model picked', 'Fixed');
    sv.set('idle', 'Fixed');
    ctl.append(h('span', 'quiet small', H.name(H.data.default_harness)));
    body.append(r);
    const { r: fr, sv: fsv, ctl: fctl } = line('file', 'Harness map', 'Every model prefix and its harness, for a Desktop session launcher to read');
    fsv.set('idle', '');
    fctl.append(h('span', 'mono small quiet', '<state>/' + (H.data.file || 'desktop-harnesses.json')));
    body.append(fr);
  };

  function note(cls, color, title, sub) {
    const n = h('div', 'lr dp-note ' + cls), nm = h('div', 'lr-nm');
    nm.append(Deck.lamp('next', color), ' ', title, h('small', '', sub));
    n.append(nm); return n;
  }
  /** a row: name and its line, the state at its end (at rest: who chose it), the control under it */
  function line(id, name, sub, rest) {
    const r = h('div', 'lr'); r.dataset.item = 'dh:' + id;
    const nm = h('div', 'lr-nm', name); if (sub) nm.append(h('small', '', sub));
    const sv = Deck.saveState({ rest: () => rest || '' });
    const t = told.get(id);
    if (t && Date.now() - t.at < Deck.px('--dur') * 8) sv.set(t.state, t.word);
    const ctl = h('div', 'lr-ctl');
    r.append(nm, sv, ctl);
    return { r, sv, ctl };
  }

  function row(f, kit) {
    const { r, sv, ctl } = line(f.family, f.title, f.description, SET_BY[f.set_by] || '');
    const save = (changes, revert) => {
      if (saving) { revert && revert(); return Promise.resolve({ state: 'busy' }); }
      const glass = r.closest('.lens') || r;
      const live = [...glass.querySelectorAll('.lr button')].filter(x => !x.disabled);
      live.forEach(x => { x.disabled = true; });
      return kit.commit(r, sv, async () => {
        const res = await H.save(changes);
        if (res.state === 'applied') told.set(f.family, { state: 'applied', word: res.word, at: Date.now() });
        return res;
      }, revert).then(res => {
        if (res.state !== 'refused') kit.reopen();
        return res;
      }).finally(() => { live.forEach(x => { x.disabled = false; }); });
    };
    let c;
    if (f.toggleable) {
      c = Deck.choice(SWITCH, f.enabled === true, v => save({ [f.family]: { enabled: v } }, () => c.set(f.enabled === true)), { label: f.title });
    } else {
      const opts = f.choices.map(id => ({ v: id, label: H.name(id) }));
      c = Deck.choice(opts, f.harness, v => save({ [f.family]: { harness: v } }, () => c.set(f.harness)), { label: f.title + ' harness' });
    }
    ctl.append(c);
    if (f.set_by === 'console') {
      const back = h('button', 'btn quiet press', 'Reset'); back.type = 'button';
      back.title = 'Back to ' + (f.toggleable ? (f.default.enabled ? 'On' : 'Off') : H.name(f.default.harness));
      back.addEventListener('click', () => save({ [f.family]: null }));
      ctl.append(back);
    }
    const where = served(f);
    if (where) ctl.append(h('span', 'faint small', where));
    const said = errors.get(f.family);
    if (said) r.append(h('div', 'lr-why', said));
    return r;
  }
  /** a line on whether splice serves the family now; nothing when it does or can't tell */
  const served = f => (f.configured !== false ? '' : f.family === 'openai' ? 'No ChatGPT or OpenAI provider yet' : 'agy is not serving now');

  return H;
})();
