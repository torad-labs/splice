/* deck-composer.js: the Night Deck kit's one composer (the prototype kit's composer.js, ported on Nov 19, 2026). A provider
   may carry its own colour (SPLICE gives each command one, by its family); one without draws in var(--p-<id>). */
/* The Night Deck composer, after T3 Code's. Locked with Accounts on Nov 17, 2026.
   Always open; one control for model and effort (rising bars, Max named); permissions; dictation; a round send that
   becomes Stop while a run goes; @ mentions and / commands; files by + or by drop; a context strip on the field.
   Deck.composer(root, config) wires the markup in shell.html's composer slot.
   config: {
     providers: [{ id, name, command, models: [[name, sub], ...] }],
     selected: { provider, model }, effort: 'low'|'medium'|'high'|'max',
     modes: [[id, label, sub]], mode,
     commands: [[name, description]],
     mentions: () => [{ label, sub, state?, color?, insert }],
     context: () => ({ state, text }),        // the strip's resting line
     placeholder, hints,                       // the text box's placeholder; the strip's right side
     onSend: ({ text, files, provider, model, effort, mode }) => ms | Promise   // how long the run lasts (mock), or
       a Promise of { state, said }: what the daemon answered, which ends the run and stands on the strip a moment
     onStop: () => void                        // the round Stop while a run goes
   } */
Deck.composer = (root, config) => {
  'use strict';
  const { $, h, px, lamp, ICON } = Deck;
  const comp = $('.comp', root), text = $('textarea', root), sendBtn = $('.send', root), chipsEl = $('.chips', root), fileIn = $('input[type=file]', root);
  const modelBtn = $('[data-comp=model]', root), modeBtn = $('[data-comp=mode]', root), micBtn = $('[data-comp=mic]', root), ctxEl = $('.comp-context', root);
  const EFFORT = [['low', 'Low'], ['medium', 'Medium'], ['high', 'High'], ['max', 'Max']];
  const P = config.providers;
  const cs = { provider: config.selected.provider, model: config.selected.model, effort: config.effort || 'high', mode: config.mode || (config.modes && config.modes[0][0]), files: [], running: false, listening: false, note: null };
  if (config.placeholder) text.placeholder = config.placeholder;
  const SVG = 'http://www.w3.org/2000/svg';
  const svgEl = (tag, attrs) => { const n = document.createElementNS(SVG, tag); for (const k in attrs) n.setAttribute(k, attrs[k]); return n; };
  function glyph(effort) {
    const s = svgEl('svg', { class: 'glyph', viewBox: '0 0 10 12', 'aria-hidden': 'true' });
    const filled = effort === 'max' ? 3 : EFFORT.findIndex(e => e[0] === effort) + 1;
    [6, 9, 12].forEach((hgt, i) => s.append(svgEl('rect', { x: i * 4, y: 12 - hgt, width: 2, height: hgt, class: i < filled ? '' : 'off' })));
    return s;
  }
  const chev = () => { const s = svgEl('svg', { class: 'chev', viewBox: '0 0 24 24', 'aria-hidden': 'true' }); s.append(svgEl('path', { d: 'M6 9l6 6 6-6' })); return s; };
  const UP = '<svg viewBox="0 0 24 24"><path d="M12 19V5M5.5 11.5L12 5l6.5 6.5"/></svg>', STOP = '<svg viewBox="0 0 24 24"><rect x="7" y="7" width="10" height="10" rx="2"/></svg>';
  const prov = id => P.find(p => p.id === id);
  const colourOf = p => (p && p.color) || 'var(--p-' + (p ? p.id : 'local') + ')';

  function renderModelBtn() {
    modelBtn.textContent = '';
    const dot = h('span', 'pdot'); dot.style.setProperty('--pc', colourOf(prov(cs.provider)));
    modelBtn.append(dot, h('span', 'label', cs.model), glyph(cs.effort));
    if (cs.effort === 'max') modelBtn.append(h('span', '', 'Max'));
    modelBtn.append(chev());
    modelBtn.setAttribute('aria-label', 'Model ' + cs.model + ' on ' + (prov(cs.provider) || { name: 'no provider yet' }).name + ', effort ' + EFFORT.find(e => e[0] === cs.effort)[1]);
  }
  function renderSend() {
    const can = text.value.trim().length > 0 || cs.files.length > 0;
    sendBtn.classList.toggle('is-running', cs.running);
    sendBtn.innerHTML = cs.running ? STOP : UP;
    sendBtn.disabled = !cs.running && !can;
    sendBtn.setAttribute('aria-label', cs.running ? 'Stop' : 'Send');
  }
  function renderContext() {
    ctxEl.textContent = '';
    const where = h('span', 'where'), c = cs.note || config.context();
    where.append(lamp(c.state), h('span', '', c.text));
    const keys = h('span', 'keys'); keys.innerHTML = config.hints || '<kbd>Enter</kbd> to send · <kbd>Shift Enter</kbd> new line';
    ctxEl.append(where, keys);
  }
  function renderChips() {
    chipsEl.textContent = ''; chipsEl.hidden = cs.files.length === 0;
    cs.files.forEach((f, i) => {
      const c = h('span', 'chip'); c.append(h('span', 'nm', f.name), h('span', 'size', f.size < 1024 ? f.size + ' B' : Math.round(f.size / 1024) + ' KB'));
      const x = h('button'); x.type = 'button'; x.innerHTML = ICON.close; x.setAttribute('aria-label', 'Remove ' + f.name);
      x.addEventListener('click', () => { cs.files.splice(i, 1); renderChips(); renderSend(); text.focus(); });
      c.append(x); chipsEl.append(c);
    });
  }
  function autosize() { text.style.height = 'auto'; text.style.height = Math.min(text.scrollHeight, px('--comp-text-max')) + 'px'; }

  /* model and effort picker */
  const picker = Deck.glass('picker'); picker.setAttribute('role', 'dialog'); picker.setAttribute('aria-label', 'Model and effort');
  let viewProv = cs.provider, pickerOff = null, query = '';
  function buildPicker() {
    picker.textContent = '';
    const rail = h('div', 'pk-rail');
    P.forEach(p => {
      const b = h('button', 'pk-prov press'); b.type = 'button'; b.setAttribute('aria-label', p.name); b.title = p.name;
      b.setAttribute('aria-pressed', !query && p.id === viewProv);
      b.style.setProperty('--pc', colourOf(p)); b.append(h('span', 'lamp'));
      b.addEventListener('click', () => { viewProv = p.id; query = ''; buildPicker(); $('.pk-search', picker).focus(); });
      rail.append(b);
    });
    const mid = h('div', 'pk-models');
    const search = h('input', 'pk-search'); search.placeholder = 'Search models'; search.value = query; search.setAttribute('aria-label', 'Search models');
    search.addEventListener('input', () => { query = search.value; const pos = search.selectionStart; buildPicker(); const s2 = $('.pk-search', picker); s2.focus(); s2.setSelectionRange(pos, pos); });
    const list = h('div', 'pk-list'); let any = false;
    (query ? P : P.filter(p => p.id === viewProv)).forEach(p => {
      const rows = p.models.filter(m => !query || (m[0] + ' ' + p.name).toLowerCase().includes(query.toLowerCase()));
      if (!rows.length) return; any = true;
      list.append(h('div', 'eyebrow pk-group', p.name + ' · ' + p.command));
      rows.forEach(([name, sub]) => {
        const r = h('button', 'pk-row press'); r.type = 'button';
        const left = h('span'); left.append(h('span', 'nm', name), h('span', 'sub', sub || ''));
        const ck = h('span'); if (p.id === cs.provider && name === cs.model) ck.innerHTML = ICON.check;
        r.append(left, ck);
        r.addEventListener('click', () => { cs.provider = p.id; cs.model = name; viewProv = p.id; renderModelBtn(); closePicker(); });
        list.append(r);
      });
    });
    if (!any) list.append(h('div', 'pk-empty', 'No model matches "' + query + '"'));
    mid.append(search, list);
    const eff = h('div', 'pk-effort'); eff.append(h('div', 'eyebrow', 'Effort'));
    EFFORT.forEach(([id, label]) => {
      const r = h('button', 'pk-row press'); r.type = 'button';
      const lead = h('span', 'lead'); lead.append(glyph(id), h('span', 'nm', label));
      const ck = h('span'); if (id === cs.effort) ck.innerHTML = ICON.check;
      r.append(lead, ck);
      r.addEventListener('click', () => { cs.effort = id; renderModelBtn(); buildPicker(); $('.pk-search', picker).focus({ preventScroll: true }); });
      eff.append(r);
    });
    picker.append(rail, mid, eff);
  }
  function openPicker() {
    Deck.closeMenu(false); closeSuggest();
    viewProv = cs.provider; query = ''; buildPicker(); picker.hidden = false;
    const r = modelBtn.getBoundingClientRect(), c = comp.getBoundingClientRect(), pw = picker.offsetWidth, ph = picker.offsetHeight, g = px('--sp-2');
    const x = Math.max(g, Math.min(innerWidth - pw - g, c.right - pw)), y = Math.max(g, c.top - g - ph);
    picker.style.left = x + 'px'; picker.style.top = y + 'px';
    Deck.show(picker, [r.left + r.width / 2 - x, r.top - y]);
    modelBtn.setAttribute('aria-expanded', 'true');
    pickerOff = Deck.layer(picker, () => closePicker(), [modelBtn]);
    requestAnimationFrame(() => $('.pk-search', picker).focus({ preventScroll: true }));
  }
  function closePicker(refocus = true) {
    if (!pickerOff) return;
    pickerOff(); pickerOff = null; modelBtn.setAttribute('aria-expanded', 'false');
    Deck.hide(picker).then(() => refocus && text.focus({ preventScroll: true }));
  }

  /* @ mentions and / commands */
  const suggest = Deck.glass('suggest'); suggest.setAttribute('role', 'listbox');
  let sg = null, sgOff = null;
  function tokenAtCaret() {
    const before = text.value.slice(0, text.selectionStart);
    const m = /(^|\s)([@/])([^\s@/]*)$/.exec(before);
    return m ? { kind: m[2], q: m[3].toLowerCase(), start: before.length - m[3].length - 1 } : null;
  }
  function updateSuggest() {
    const t = tokenAtCaret(); if (!t) return closeSuggest();
    const items = t.kind === '@' ? config.mentions() : (config.commands || []).map(([c, d]) => ({ label: '/' + c, sub: d, insert: '/' + c + ' ', key: true }));
    const hits = items.filter(i => i.label.toLowerCase().replace(/^\//, '').includes(t.q)).slice(0, 8);
    if (!hits.length) return closeSuggest();
    const wasOpen = !!sg;
    sg = { start: t.start, items: hits, active: sg ? Math.min(sg.active, hits.length - 1) : 0 };
    suggest.textContent = '';
    hits.forEach((it, i) => {
      const r = h('button', 'sg-row' + (i === sg.active ? ' is-active' : '')); r.type = 'button'; r.setAttribute('role', 'option');
      const lead = h('span', 'lead');
      if (it.state || it.color) lead.append(lamp(it.state, it.color));
      const words = h('span'); words.append(h('span', 'nm' + (it.key ? ' key' : ''), it.label), h('span', 'sub', it.sub || ''));
      lead.append(words); r.append(lead);
      r.addEventListener('pointerdown', e => { e.preventDefault(); applySuggest(i); });
      suggest.append(r);
    });
    const c = comp.getBoundingClientRect(), g = px('--sp-2');
    if (!wasOpen) {
      suggest.hidden = false;
      suggest.style.left = c.left + 'px'; suggest.style.top = Math.max(g, c.top - g - suggest.offsetHeight) + 'px';
      Deck.show(suggest, [px('--sp-5'), suggest.offsetHeight]);
      sgOff = Deck.layer(suggest, () => closeSuggest(), [comp]);
    } else suggest.style.top = Math.max(g, c.top - g - suggest.offsetHeight) + 'px';
  }
  function applySuggest(i) {
    const it = sg.items[i], end = text.selectionStart;
    text.value = text.value.slice(0, sg.start) + it.insert + text.value.slice(end);
    const pos = sg.start + it.insert.length; text.setSelectionRange(pos, pos);
    closeSuggest(); autosize(); renderSend(); text.focus();
  }
  function closeSuggest() { if (!sg) return; sg = null; sgOff && sgOff(); sgOff = null; Deck.hide(suggest); }

  /* send, and the run it starts */
  let runTimer = 0;
  function note(state, words, ms) { cs.note = { state, text: words }; renderContext(); if (ms) setTimeout(() => { cs.note = null; renderContext(); }, ms); }
  function send() {
    if (cs.running) { cs.running = false; clearTimeout(runTimer); renderSend(); if (config.onStop) config.onStop(); note('stalled', 'Stopped', 2500); return; }
    if (!text.value.trim() && !cs.files.length) return;
    const msg = { text: text.value, files: cs.files.slice(), provider: cs.provider, model: cs.model, effort: cs.effort, mode: cs.mode };
    text.value = ''; cs.files = []; renderChips(); autosize(); closeSuggest();
    const dur = config.onSend ? config.onSend(msg) : 3000;
    if (!dur) { renderSend(); return; }
    cs.running = true; renderSend(); note('working', config.workingText || 'Working on it');
    Promise.resolve(dur).then(ms => {
      // a live send: what the daemon answered ends the run, in its own words
      if (ms && typeof ms === 'object') { if (!cs.running) return; cs.running = false; renderSend(); note(ms.state || 'serving', ms.said || config.doneText || 'Done', 3500); return; }
      runTimer = setTimeout(() => { if (!cs.running) return; cs.running = false; renderSend(); note('serving', config.doneText || 'Done', 3500); }, typeof ms === 'number' ? ms : 3000);
    });
  }
  sendBtn.addEventListener('click', send);
  text.addEventListener('input', () => { autosize(); renderSend(); updateSuggest(); });
  text.addEventListener('click', updateSuggest);
  text.addEventListener('keydown', e => {
    if (sg) {
      if (e.key === 'ArrowDown' || e.key === 'ArrowUp') { e.preventDefault(); sg.active = (sg.active + (e.key === 'ArrowDown' ? 1 : sg.items.length - 1)) % sg.items.length; [...suggest.children].forEach((n, i) => n.classList.toggle('is-active', i === sg.active)); return; }
      if (e.key === 'Enter' || e.key === 'Tab') { e.preventDefault(); applySuggest(sg.active); return; }
    }
    if (e.key === 'Enter' && !e.shiftKey && !e.isComposing) { e.preventDefault(); send(); }
    if (e.key === 'Escape' && !sg) text.blur();
  });
  modelBtn.addEventListener('click', () => pickerOff ? closePicker() : openPicker());
  if (modeBtn && config.modes) modeBtn.addEventListener('click', () => {
    closePicker(false);
    Deck.openMenu(modeBtn, config.modes.map(([id, label, sub]) => ({ label, sub: sub || '', checked: id === cs.mode,
      run: () => { cs.mode = id; $('.label', modeBtn).textContent = label; text.focus(); } })), 'up');
  });
  if (modeBtn && config.modes) $('.label', modeBtn).textContent = config.modes.find(m => m[0] === cs.mode)[1];
  if (micBtn) micBtn.addEventListener('click', () => {
    cs.listening = !cs.listening; micBtn.setAttribute('aria-pressed', cs.listening);
    cs.note = cs.listening ? { state: 'needs', text: 'Listening. Click the mic again to stop' } : null; renderContext();
  });
  $('[data-comp=attach]', root).addEventListener('click', () => fileIn.click());
  fileIn.addEventListener('change', () => { cs.files.push(...[...fileIn.files].map(f => ({ name: f.name, size: f.size }))); fileIn.value = ''; renderChips(); renderSend(); text.focus(); });
  let dragDepth = 0;
  const hasFiles = e => [...(e.dataTransfer?.types || [])].includes('Files');
  comp.addEventListener('dragenter', e => { if (!hasFiles(e)) return; e.preventDefault(); dragDepth++; comp.classList.add('is-drop'); });
  comp.addEventListener('dragover', e => { if (hasFiles(e)) e.preventDefault(); });
  comp.addEventListener('dragleave', () => { dragDepth = Math.max(0, dragDepth - 1); if (!dragDepth) comp.classList.remove('is-drop'); });
  comp.addEventListener('drop', e => { if (!hasFiles(e)) return; e.preventDefault(); dragDepth = 0; comp.classList.remove('is-drop'); cs.files.push(...[...e.dataTransfer.files].map(f => ({ name: f.name, size: f.size }))); renderChips(); renderSend(); text.focus(); });
  document.addEventListener('keydown', e => { if ((e.ctrlKey || e.metaKey) && e.key.toLowerCase() === 'k') { e.preventDefault(); text.focus(); } });
  renderModelBtn(); renderSend(); renderContext(); autosize();
  return {
    focus: () => text.focus(),
    refresh: renderContext,
    note,
    insert(str) { text.value += (text.value && !text.value.endsWith(' ') ? ' ' : '') + str; autosize(); renderSend(); text.focus(); },
    get state() { return { ...cs }; },
    setModel(provider, model) { cs.provider = provider; cs.model = model; renderModelBtn(); },
  };
};
