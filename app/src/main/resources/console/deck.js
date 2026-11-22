/* The Night Deck shell: the runtime every Splice console page shares. Ported from the approved prototype kit
   (shell.js and the markup kit/shell.html and kit/assemble.ts wrote) on Nov 19, 2026.
   A page's HTML holds only its own field (main.field) and its rails (aside.rail); this file draws the rest of the shell
   around them: the room, the scroll edges, the nav (from the one page list below), the need float, the Night/Day switch
   and, unless body.no-comp, the composer's slot. Then window.Deck gives a page: helpers, lamps, glass that
   materialises, menus, a layer stack (Escape and outside clicks), the inspect glass, the theme switch, scroll depth and
   the controls. A page never re-implements these. Loaded after api.js and deck-lens.js, before the page's own script. */

/* ---------- the theme, applied before the page paints ----------
   Saved where the console has always saved it, so the choice holds across reloads and across every page. */
const DECK_THEME_KEY = 'splice-console-theme';
try { const saved = localStorage.getItem(DECK_THEME_KEY); if (saved === 'day' || saved === 'night') document.documentElement.dataset.theme = saved; } catch { /* storage off: the page keeps its own theme */ }

/* ---------- the shell's markup, around the page's own field and rails ---------- */
/** The console's pages, in the nav's order: the one list every part of the console names them by (pilot.js too). */
const DECK_PAGES = [
  ['sessions', 'Sessions'], ['accounts', 'Accounts'], ['teams', 'Teams'],
  ['providers', 'Providers'], ['activity', 'Activity'], ['server', 'Server'], ['settings', 'Settings'],
];
(() => {
  'use strict';
  const body = document.body, field = document.querySelector('main.field');
  const here = (location.pathname.split('/').pop() || '').replace(/\.html$/, '');
  const at = (html, where, ref) => { const t = document.createElement('template'); t.innerHTML = html.trim(); const nodes = [...t.content.childNodes]; ref ? ref[where](...nodes) : body.append(...nodes); return nodes; };
  const room = '<div class="deck-room" aria-hidden="true"><i class="room-orbit a"></i><i class="room-orbit b"></i><i class="room-floor"></i><i class="room-beam"></i></div>'
    + '<div class="deck-light" aria-hidden="true"></div><div class="deck-far" aria-hidden="true"></div>';
  if (field) at(room, 'before', field); else body.prepend(...at(room));
  const tabs = DECK_PAGES.map(([file, name]) => `<a class="tab press" href="${file}.html"${file === here ? ' aria-current="page"' : ''}>${name}</a>`).join('');
  const theme = document.documentElement.dataset.theme === 'day' ? 'day' : 'night';
  const chrome = '<div class="scroll-edge" aria-hidden="true"></div><div class="scroll-edge bottom" aria-hidden="true"></div>'
    + '<nav class="glass bar nav" data-lens aria-label="Console"><a class="brand" href="#" aria-label="Splice home"><b>SPLICE</b><i></i><span>TORAD LABS</span></a><span class="bar-rule" aria-hidden="true"></span>' + tabs + '</nav>'
    + '<button class="need-float press" id="needBtn" aria-pressed="false" hidden><span class="lamp"></span><span>0 need you</span></button>'
    + `<div class="glass switch" data-lens role="radiogroup" aria-label="Appearance" data-on="${theme}"><span class="thumb" aria-hidden="true"></span>`
    + `<button class="press" role="radio" data-theme-set="night" aria-checked="${theme === 'night'}">Night</button>`
    + `<button class="press" role="radio" data-theme-set="day" aria-checked="${theme === 'day'}">Day</button></div>`;
  const after = field ? at(chrome, 'after', field) : at(chrome);
  // the rails stand where the shell put them, after the switch, so they stack as the approved pages do
  let last = after[after.length - 1];
  for (const rail of document.querySelectorAll('aside.rail')) { last.after(rail); last = rail; }
  if (!body.classList.contains('no-comp')) {
    at('<div class="comp-wrap" id="compWrap"><section class="glass comp" data-lens aria-label="Composer"><div class="chips" hidden></div>'
      + '<textarea rows="1" aria-label="Message"></textarea><div class="comp-foot">'
      + '<button class="cbtn icon press" data-comp="attach" type="button" aria-label="Attach files"><svg class="ico" viewBox="0 0 24 24"><path d="M12 5v14M5 12h14"/></svg></button>'
      + '<input type="file" multiple hidden><div class="comp-ctrls">'
      + '<button class="cbtn press" data-comp="model" type="button" aria-haspopup="dialog" aria-expanded="false"></button>'
      + '<button class="cbtn press" data-comp="mode" type="button" aria-haspopup="menu" aria-expanded="false"><svg class="ico" viewBox="0 0 24 24"><path d="M12 3l7 3v5c0 4.5-3 8-7 10-4-2-7-5.5-7-10V6z"/></svg><span class="label"></span></button>'
      + '</div><button class="cbtn icon press" data-comp="mic" type="button" aria-label="Dictate" aria-pressed="false"><svg class="ico" viewBox="0 0 24 24"><rect x="9" y="3" width="6" height="11" rx="3"/><path d="M5 11a7 7 0 0 0 14 0M12 18v3"/></svg></button>'
      + '<button class="send press" type="button" aria-label="Send" disabled></button></div><div class="drop-hint" aria-hidden="true">Drop to attach</div></section>'
      + '<div class="comp-context"></div></div>', 'after', last);
  }
  // every glass on the page is registered once the page's own script has drawn it
})();

window.Deck = (() => {
  'use strict';
  const $ = (s, r = document) => r.querySelector(s);
  const $$ = (s, r = document) => Array.from(r.querySelectorAll(s));
  const h = (tag, cls, text) => { const n = document.createElement(tag); if (cls) n.className = cls; if (text != null) n.textContent = text; return n; };
  const fmt = n => Math.round(n).toLocaleString('en-US');
  const money = n => '$' + Number(n).toLocaleString('en-US', { minimumFractionDigits: 2, maximumFractionDigits: 2 });
  const pct = u => Math.round(u * 100) + '%';
  const css = name => getComputedStyle(document.documentElement).getPropertyValue(name).trim();
  const rem = () => parseFloat(getComputedStyle(document.documentElement).fontSize);
  /** a token's length in px (rem tokens are multiplied by the root size; plain numbers, and numbers in another unit such
   *  as ms, are returned as they are); one written as a calc() is measured, since only the browser can resolve it */
  const px = name => {
    const v = css(name);
    if (/^-?[\d.]+rem$/.test(v)) return parseFloat(v) * rem();
    if (/^-?[\d.]+([a-z%]+)?$/i.test(v)) return parseFloat(v);
    const probe = document.createElement('div');
    probe.style.cssText = 'position:absolute;visibility:hidden;height:0;width:var(' + name + ')';
    (document.body || document.documentElement).append(probe);
    const w = probe.getBoundingClientRect().width; probe.remove();
    return w;
  };
  /** a count with its noun, singular for one: count(1, 'request') is '1 request', count(3, 'request') '3 requests' */
  const count = (n, one, many = one + 's') => fmt(n) + ' ' + (Math.round(n) === 1 ? one : many);
  /** the time of day in the viewer's own zone, with the zone named as the approved pages write it ('2:01 PM CT') */
  const clock = (ts = Date.now()) => {
    const at = new Date(ts), time = at.toLocaleTimeString('en-US', { hour: 'numeric', minute: '2-digit' });
    const zone = o => (new Intl.DateTimeFormat('en-US', { timeZoneName: o }).formatToParts(at).find(p => p.type === 'timeZoneName') || {}).value || '';
    const z = zone('shortGeneric');
    return time + ' ' + (z && !z.includes(' ') ? z : zone('short'));
  };
  const ago = ms => { const s = Math.max(0, Math.round(ms / 1000)); if (s < 60) return s + ' s'; const m = Math.round(s / 60); if (m < 60) return m + ' min'; const hr = Math.round(m / 60); return hr < 48 ? hr + ' h' : Math.round(hr / 24) + ' d'; };

  /* ---------- lamps: filled = settled, ring = provisional, live = pulses ---------- */
  /* one vocabulary for every page; a page adds its own states with Deck.STATES[name] = {...} */
  const STATES = {
    serving: { word: 'serving now', c: 'var(--verdigris)', live: true },
    next: { word: 'next up', c: 'var(--prussian)', ring: true },
    ready: { word: 'ready', c: 'var(--prussian)' },
    limit: { word: 'at a limit', c: 'var(--vermilion)' },
    out: { word: 'needs sign-in', c: 'var(--ormolu)', ring: true },
    working: { word: 'working', c: 'var(--verdigris)', live: true },
    needs: { word: 'needs you', c: 'var(--vermilion)', ring: true, live: true },
    stalled: { word: 'stalled', c: 'var(--vermilion)' },
    idle: { word: 'idle', c: 'var(--ink-faint)', ring: true },
    ended: { word: 'ended', c: 'var(--ink-faint)' },
  };
  function lamp(state, color) {
    const st = STATES[state] || {};
    const l = h('span', 'lamp' + (st.ring ? ' ring' : '') + (st.live ? ' live' : ''));
    l.style.setProperty('--c', color || st.c || 'var(--ink-faint)');
    return l;
  }
  const ICON = {
    check: '<svg class="ico" viewBox="0 0 24 24"><path d="M5 12.5l4.5 4.5L19 7"/></svg>',
    close: '<svg class="ico" viewBox="0 0 24 24"><path d="M6 6l12 12M18 6L6 18"/></svg>',
    open: '<svg class="ico" viewBox="0 0 24 24"><path d="M8 16L16 8M10 8h6v6"/></svg>',
    chev: '<svg class="ico" viewBox="0 0 24 24"><path d="M7 10l5 5 5-5"/></svg>',
    more: '<svg class="ico" viewBox="0 0 24 24"><path d="M6 12h.01M12 12h.01M18 12h.01" stroke-width="3"/></svg>',
    grip: '<svg class="ico" viewBox="0 0 24 24"><path d="M9 6h.01M9 12h.01M9 18h.01M15 6h.01M15 12h.01M15 18h.01" stroke-width="3"/></svg>',
    stop: '<svg class="ico" viewBox="0 0 24 24"><rect x="7" y="7" width="10" height="10" rx="2"/></svg>',
    search: '<svg class="ico" viewBox="0 0 24 24"><circle cx="10.5" cy="10.5" r="6.5"/><path d="M15.5 15.5L21 21"/></svg>',
  };
  const iconBtn = (name, label) => { const b = h('button', 'btn quiet icon press'); b.type = 'button'; b.innerHTML = ICON[name]; b.setAttribute('aria-label', label); return b; };

  /* ---------- glass that materialises (never fades), growing out of an origin point ---------- */
  function show(el, origin) {
    el._shown = (el._shown || 0) + 1;           // a hide still fading out does not hide this showing
    el.hidden = false;
    if (origin) { el.style.setProperty('--ox', origin[0] + 'px'); el.style.setProperty('--oy', origin[1] + 'px'); }
    el.classList.add('is-entering', 'no-t');
    el.style.setProperty('--mat', 0);
    DeckLens.materialise(el, false, 0);
    void el.offsetWidth;
    el.classList.remove('no-t');
    requestAnimationFrame(() => { el.classList.remove('is-entering'); DeckLens.materialise(el, true, px('--dur-mat')); });
  }
  function hide(el) {
    if (el.hidden) return Promise.resolve();
    const shown = el._shown;
    el.classList.add('is-entering');
    return DeckLens.materialise(el, false, px('--dur-mat') * 0.7).then(() => { if (el._shown !== shown) return; el.hidden = true; el.classList.remove('is-entering'); });
  }
  /** a new glass surface (class names from shell.css: menu, lens, picker, suggest, or the page's own) */
  function glass(cls, parent = document.body) { const g = h('div', 'glass ' + cls); g.hidden = true; g.setAttribute('data-lens', ''); parent.append(g); return g; }

  /* ---------- the layer stack: Escape closes the newest, a press outside closes it too ---------- */
  const layers = [];
  /** register an open layer; [el] is what counts as inside; [close] closes it. Returns a remover. */
  function layer(el, close, keep = []) { const L = { el, close, keep }; layers.push(L); return () => { const i = layers.indexOf(L); if (i >= 0) layers.splice(i, 1); }; }
  document.addEventListener('keydown', e => {
    if (e.key === 'Escape' && layers.length) { e.preventDefault(); layers[layers.length - 1].close(); }
  });
  document.addEventListener('pointerdown', e => {
    for (const L of [...layers].reverse()) {
      if (L.el.contains(e.target) || L.keep.some(k => k && k.contains(e.target))) break;
      L.close();
    }
  });

  /* ---------- menus pop open out of their button ---------- */
  const menu = glass('menu'); menu.setAttribute('role', 'menu');
  let menuFor = null, menuOff = null;
  function openMenu(btn, items, place) {
    if (menuFor === btn) { closeMenu(); return; }
    if (menuFor) closeMenu(false);
    menuFor = btn;
    menu.textContent = ''; menu.classList.toggle('is-long', items.length > 12);
    for (const it of items) {
      if (it === '-') { menu.append(h('hr')); continue; }
      const b = h('button', 'press' + (it.danger ? ' danger' : '')); b.type = 'button'; b.setAttribute('role', 'menuitem');
      const ic = h('span'); if (it.checked) ic.innerHTML = ICON.check;
      b.append(ic, h('span', '', it.label), h('span', 'sub', it.sub || ''));
      b.addEventListener('click', () => { closeMenu(false); it.run && it.run(); });
      menu.append(b);
    }
    menu.hidden = false;
    const r = btn.getBoundingClientRect(), mw = menu.offsetWidth, mh = menu.offsetHeight, g = px('--sp-1');
    const comp = btn.closest('.comp');
    const topRef = place === 'up' && comp ? comp.getBoundingClientRect().top : r.top;
    let x = place === 'up' ? r.left : r.right - mw, y = place === 'up' ? topRef - g - mh : r.bottom + g;
    x = Math.max(g, Math.min(innerWidth - mw - g, x));
    if (y + mh > innerHeight - g) y = r.top - g - mh;
    menu.style.left = x + 'px'; menu.style.top = y + 'px';
    show(menu, [r.left + r.width / 2 - x, (place === 'up' ? r.top : r.bottom) - y]);
    btn.setAttribute('aria-expanded', 'true');
    menuOff = layer(menu, () => closeMenu(), [btn]);
    requestAnimationFrame(() => menu.querySelector('button') && menu.querySelector('button').focus({ preventScroll: true }));
  }
  function closeMenu(refocus = true) {
    if (!menuFor) return;
    menuFor.setAttribute('aria-expanded', 'false');
    const b = menuFor; menuFor = null; menuOff && menuOff(); menuOff = null;
    hide(menu).then(() => refocus && b.focus({ preventScroll: true }));
  }

  /* ---------- the inspect glass: one at a time, placed beside what opened it, growing out of it ---------- */
  function inspector(parent) {
    const el = glass('lens', parent); el.setAttribute('role', 'dialog');
    let forEl = null, off = null;
    return {
      el,
      get open() { return forEl; },
      /** fill(el) draws the content; [anchor] is the element it opened from, in [area]'s column */
      openBeside(anchor, fill, area) {
        if (forEl) { off && off(); }
        forEl = anchor; el.textContent = ''; fill(el); el.hidden = false;
        const host = el.offsetParent || document.body, f = host.getBoundingClientRect(), c = (area || host).getBoundingClientRect(), m = anchor.getBoundingClientRect();
        const lw = el.offsetWidth, lh = el.offsetHeight, gap = px('--gap');
        let x = m.right + gap; if (x + lw > c.right) x = m.left - gap - lw; if (x < c.left) x = Math.min(c.right - lw, Math.max(c.left, m.left));
        const navB = ($('.nav') ? $('.nav').getBoundingClientRect().bottom : 0) + gap;
        let y = Math.max(m.top + m.height / 2 - lh / 2, c.top, navB);
        if (y + lh > innerHeight - gap) y = Math.max(navB, innerHeight - gap - lh);
        el.style.left = (x - f.left) + 'px'; el.style.top = (y - f.top) + 'px';
        show(el, [m.left + m.width / 2 - x, m.top + m.height / 2 - y]);
        off = layer(el, () => this.close(), [anchor]);
      },
      close() { if (!forEl) return; const a = forEl; forEl = null; off && off(); off = null; hide(el).then(() => a.focus && a.focus({ preventScroll: true })); },
    };
  }

  /* ---------- theme switch, the floating alert, scroll depth ---------- */
  /** the theme in use, saved so it holds across reloads and pages; the favicon follows it where a page links one */
  function setTheme(theme) {
    document.documentElement.dataset.theme = theme;
    try { localStorage.setItem(DECK_THEME_KEY, theme); } catch { /* storage off: this page still switches */ }
    const sw = $('.switch'); if (sw) sw.dataset.on = theme;
    $$('[data-theme-set]').forEach(x => x.setAttribute('aria-checked', x.dataset.themeSet === theme));
    favicon();
  }
  function favicon() {
    const link = $('link[rel="icon"][type="image/svg+xml"]');
    if (link) link.href = document.documentElement.dataset.theme === 'day' ? 'brand/favicon-day.svg' : 'brand/favicon.svg';
  }
  favicon();
  $$('[data-theme-set]').forEach(b => b.addEventListener('click', () => setTheme(b.dataset.themeSet)));
  // The room behind the page stays where it is when the page scrolls. It drifted: each scroll frame wrote the scroll
  // position on the root element, which restyles every element of the page, and moved the room's layers under every
  // panel. On a 3840 px screen a scroll step over an open session took 169 ms with the drift and 84 ms without.
  /** set the floating alert next to the switch: n things need him, and what pressing it does */
  function setNeed(n, word, onPress) {
    const b = $('#needBtn'); if (!b) return;
    // "need you" agrees with its count, whichever form a page passes: 1 needs you, 2 need you
    const said = !word || /^needs? you$/.test(word) ? (n === 1 ? 'needs you' : 'need you') : word;
    const text = said.includes('{n}') ? said.replace('{n}', n) : n + ' ' + said;
    b.onclick = () => { const on = b.getAttribute('aria-pressed') !== 'true'; b.setAttribute('aria-pressed', on); onPress && onPress(on); };
    // a page sets it on every read: told what it already says, it writes nothing and measures nothing
    if (b._said === text && b.hidden === !n) return;
    b._said = text; b.hidden = !n; b.querySelector('span:last-child').textContent = text;
    fitNeed();
  }
  /** the alert never reaches into the nav or the switch: beside the nav it takes the room between the two, and on a
   *  phone, where it sits above the composer, the room from its own left to the switch; past that it ellipsises
   *  (Nov 21, 2026: the phone's room was measured from the nav, which is on another row there, so it came to nothing
   *  and the alert showed its lamp without its words) */
  function fitNeed() {
    const b = $('#needBtn'), nav = $('.nav'), sw = $('.switch'); if (!b || !nav || !sw) return;
    b.style.maxWidth = '';
    const br = b.getBoundingClientRect(), nr = nav.getBoundingClientRect(), sr = sw.getBoundingClientRect();
    const from = br.top < nr.bottom && br.bottom > nr.top ? nr.right + px('--gap') : br.left;
    b.style.maxWidth = Math.max(0, sr.left - px('--gap') - from) + 'px';
  }
  addEventListener('resize', fitNeed);

  /* ---------- answering: a question and its options as a numbered list, the way Claude Code asks ----------
     ask: { kind: 'dialog'|'input', tool?, what?, question, choices: [label, ...] }
     opts: { onAnswer(index, label, typed), waited?: 'text', compact?: bool }
     Keys 1 to 9 pick an option while the block has focus; the last option of a dialog, and "Something else",
     open a line to type what to do instead. */
  function answer(ask, opts = {}) {
    const box = h('div', 'ask' + (opts.compact ? ' compact' : '')); box.tabIndex = -1;
    const head = h('div', 'ask-head');
    head.append(h('span', 'eyebrow', ask.kind === 'dialog' ? 'Permission' + (ask.tool ? ' · ' + ask.tool : '') : 'Question'));
    if (opts.waited) head.append(h('span', 'ask-wait', opts.waited));
    box.append(head, h('div', 'ask-q', ask.question));
    if (ask.what) box.append(h('pre', 'ask-cmd', ask.what));
    const list = h('ol', 'opts');
    const typed = i => /something else|tell claude|differently/i.test(ask.choices[i]);
    let done = false;
    const pick = i => {
      if (done) return;
      if (typed(i) && !list.querySelector('.opt-say')) {
        const say = h('div', 'opt-say'), input = h('input'); input.placeholder = 'Tell it what to do instead';
        const go = h('button', 'btn press', 'Send'); go.type = 'button';
        const send = () => { if (input.value.trim()) finish(i, input.value.trim()); };
        go.addEventListener('click', send); input.addEventListener('keydown', e => { if (e.key === 'Enter') send(); e.stopPropagation(); });
        say.append(input, go); list.children[i].after(say); input.focus(); return;
      }
      if (!typed(i)) finish(i, null);
    };
    const finish = (i, text) => {
      done = true;
      [...list.querySelectorAll('.opt')].forEach((b, j) => { b.disabled = true; b.classList.toggle('is-picked', j === i); });
      box.classList.add('is-answered');
      setTimeout(() => opts.onAnswer && opts.onAnswer(i, ask.choices[i], text), px('--dur') * 2);
    };
    ask.choices.forEach((c, i) => {
      const li = h('li'), b = h('button', 'opt press'); b.type = 'button';
      b.append(h('kbd', '', String(i + 1)), h('span', 'opt-label', c), h('span', 'opt-mark'));
      b.lastChild.innerHTML = ICON.check;
      b.addEventListener('click', () => pick(i));
      li.append(b); list.append(li);
    });
    box.append(list);
    box.addEventListener('keydown', e => {
      if (e.target.tagName === 'INPUT') return;
      const n = parseInt(e.key, 10);
      if (n >= 1 && n <= ask.choices.length) { e.preventDefault(); pick(n - 1); }
    });
    return box;
  }

  /* ---------- instruments: show, don't tell ---------- */
  const SVGNS = 'http://www.w3.org/2000/svg';
  const svgEl = (tag, attrs) => { const n = document.createElementNS(SVGNS, tag); for (const k in attrs) n.setAttribute(k, attrs[k]); return n; };
  /** a sparkline with a soft area under it; values 0..max. update(values) eases to new values. Colour = currentColor. */
  function spark(values, o = {}) {
    const W = 100, H = 30, el = svgEl('svg', { class: 'spark', viewBox: `0 0 ${W} ${H}`, preserveAspectRatio: 'none', 'aria-hidden': 'true' });
    const area = svgEl('path', { class: 'spark-area' }), line = svgEl('path', { class: 'spark-line', 'vector-effect': 'non-scaling-stroke' }), dot = svgEl('circle', { class: 'spark-dot', r: 1.6 });
    el.append(area, line, dot);
    let cur = values.slice();
    const draw = vals => {
      const max = o.max || Math.max(1e-9, ...vals), n = vals.length;
      const pts = vals.map((v, i) => [n === 1 ? W : (i / (n - 1)) * W, H - 2 - (v / max) * (H - 4)]);
      const d = pts.map((p, i) => (i ? 'L' : 'M') + p[0].toFixed(2) + ' ' + p[1].toFixed(2)).join(' ');
      line.setAttribute('d', d); area.setAttribute('d', d + ` L${W} ${H} L0 ${H} Z`);
      const last = pts[pts.length - 1]; dot.setAttribute('cx', last[0]); dot.setAttribute('cy', last[1]);
    };
    draw(cur);
    el.classList.add('is-drawing');
    requestAnimationFrame(() => requestAnimationFrame(() => el.classList.remove('is-drawing')));
    // what the spark shows or is easing toward. A page hands it its values on every tick, changed or not; handed
    // the same ones it draws nothing. (Nov 21, 2026: every call eased for two beats whatever it was handed, so a
    // page that ticks each second never stopped drawing, and the browser's compositor ran flat out behind it.)
    let aim = values.join();
    return {
      el,
      update(next) {
        const key = next.join();
        if (key === aim) return;
        aim = key;
        const from = cur.slice(), t0 = performance.now(), ms = px('--dur') * 2;
        while (from.length < next.length) from.unshift(from[0] || 0);
        while (from.length > next.length) from.shift();
        // a frame can be handed a timestamp from before the count began (a busy page): that reads as its start, never a step back
    // an ease overtaken by newer values stops where it is: the newer one starts from the values it had reached
    const step = now => { if (aim !== key) return; const p = Math.max(0, Math.min(1, (now - t0) / ms)), e = 1 - (1 - p) ** 3; cur = next.map((v, i) => from[i] + (v - from[i]) * e); draw(cur); if (p < 1) requestAnimationFrame(step); else cur = next.slice(); };
        if (matchMedia('(prefers-reduced-motion: reduce)').matches) { cur = next.slice(); draw(cur); } else requestAnimationFrame(step);
      },
    };
  }
  /** puts [nodes] in [slot] unless they are the markup it was last given: a page that builds what it shows again on
   *  every read changes the screen only when the read changed it, and a lamp it holds is not made again to pulse
   *  again. True when it replaced what stood there. What a page adds to the nodes afterwards is not compared. */
  function put(slot, nodes) {
    const key = nodes.map(n => n.outerHTML || n.textContent || '').join('\n');
    if (slot._put === key) return false;
    slot._put = key; slot.replaceChildren(...nodes);
    return true;
  }
  /** what a strip of these segments shows, as one string: two strips with the same key look the same */
  const stripKey = segments => segments.map(s => (s.state || '') + ':' + (s.c || '') + ':' + Number(s.share).toFixed(4)).join('|');
  /** puts a strip of [segments] in [slot] unless the strip standing there shows the same: a slot repainted on a timer
   *  is rebuilt only when its segments changed */
  function stripInto(slot, segments) {
    const now = slot.firstElementChild;
    if (now && slot.childElementCount === 1 && now._key === stripKey(segments)) return;
    slot.replaceChildren(strip(segments));
  }
  /** a strip of states over a window: segments [{ state, share }] (shares sum to 1), drawn left to right */
  function strip(segments) {
    const el = h('div', 'strip'); el.setAttribute('aria-hidden', 'true');
    el._key = stripKey(segments);
    segments.forEach(s => { const i = h('i'); i.style.setProperty('--share', s.share); i.style.setProperty('--c', s.c || (STATES[s.state] || {}).c || 'var(--ink-faint)'); el.append(i); });
    return el;
  }
  /** a number that rolls to its new value instead of jumping */
  function roll(el, to, format = fmt) {
    const from = parseFloat(el.dataset.v ?? to), text = format(to);
    // rolled to the value it already shows, it writes nothing: a page rolls its numbers on every tick
    if (from === to && el._rolled === text) return;
    el.dataset.v = to; el._rolled = text;
    if (from === to || matchMedia('(prefers-reduced-motion: reduce)').matches) { el.textContent = text; return; }
    const t0 = performance.now(), ms = px('--dur') * 2;
    // a frame can be handed a timestamp from before the count began (a busy page): that reads as its start, never a step back
    const step = now => { const p = Math.max(0, Math.min(1, (now - t0) / ms)), e = 1 - (1 - p) ** 3; el.textContent = format(from + (to - from) * e); if (p < 1) requestAnimationFrame(step); };
    requestAnimationFrame(step);
  }
  /** a meter: u 0..1 in a colour; set(u) eases */
  function meter(u, color) { const m = h('span', 'meter'), i = h('i'); i.style.setProperty('--u', Math.min(1, u)); if (color) m.style.setProperty('--pc', color); m.append(i); m.set = v => i.style.setProperty('--u', Math.min(1, v)); return m; }

  /* ---------- settings: a value is picked, never typed, and its row says what the change did ----------
     choice(options, value, onPick, { label, max }): options [{ v, label, sub? }]. Up to `max` (5) options are a
       segmented track whose thumb slides to the pick; more are one button that opens a menu. Returns the element with
       .set(v) (no onPick) and .value().
     notch(stops, value, onPick, { label, format, marks }): stops [{ v, label }] in order, drawn as dots on a line with
       their labels under it; the thumb snaps to a stop (press a dot, drag, or the arrow keys). A value that is not a
       stop is placed where it falls. marks [{ at, word, c, show }] are facts drawn on the same line (this week's
       longest silence, today's peak): numbers are placed between stops on a log scale, past the last stop at the end.
       Returns the element with .set(v) and .marks(list).
     saveState({ rest }): the one place a row says what a change did. .set(state, word?): 'saving', 'applied' (in
       effect now), 'saved' (written), 'restart' (written, takes effect when splice restarts), 'refused' (word: the
       reason; the page puts the old value back), 'idle' (word: where the value comes from, or nothing). 'applied' and
       'saved' settle to 'idle' with rest() after a moment.
     setting({ name, sub, control, state, under }): a settings row: name (and an optional line under it), the control,
       the state at the end; `under` (an element) spans the row below, for per-command values or a consequence. */
  function choice(options, value, onPick, o = {}) {
    let cur = value;
    if (options.length > (o.max ?? 5)) {
      const b = h('button', 'pickbtn press'); b.type = 'button';
      const word = h('span', 'pickbtn-v'), chev = h('span', 'pickbtn-chev'); chev.innerHTML = ICON.chev;
      b.append(word, chev);
      const paint = () => { const x = options.find(x => x.v === cur); word.textContent = x ? x.label : String(cur); };
      b.addEventListener('click', () => openMenu(b, options.map(x => ({ label: x.label, sub: x.sub, checked: x.v === cur, run: () => { if (x.v === cur) return; cur = x.v; paint(); onPick && onPick(x.v); } })), 'down'));
      if (o.label) b.setAttribute('aria-label', o.label);
      paint(); b.set = v => { cur = v; paint(); }; Object.defineProperty(b, "value", { value: () => cur, configurable: true });
      return b;
    }
    const el = h('div', 'seg'); el.setAttribute('role', 'radiogroup'); if (o.label) el.setAttribute('aria-label', o.label);
    const thumb = h('span', 'seg-thumb'); el.append(thumb);
    const btns = options.map((x, i) => {
      const b = h('button', 'seg-o', x.label); b.type = 'button'; b.setAttribute('role', 'radio'); if (x.sub) b.title = x.sub;
      b.addEventListener('click', () => pick(i)); el.append(b); return b;
    });
    const pick = i => { const x = options[i]; if (!x || x.v === cur) return; cur = x.v; paint(true); onPick && onPick(x.v); };
    const paint = anim => {
      const i = options.findIndex(x => x.v === cur);
      btns.forEach((b, j) => { b.setAttribute('aria-checked', String(j === i)); b.tabIndex = j === Math.max(0, i) ? 0 : -1; });
      thumb.hidden = i < 0; if (i < 0 || !btns[i].offsetWidth) return;
      if (!anim) thumb.style.transition = 'none';
      thumb.style.width = btns[i].offsetWidth + 'px'; thumb.style.transform = `translateX(${btns[i].offsetLeft}px)`;
      if (!anim) requestAnimationFrame(() => { thumb.style.transition = ''; });
    };
    el.addEventListener('keydown', e => {
      const i = options.findIndex(x => x.v === cur), d = e.key === 'ArrowRight' ? 1 : e.key === 'ArrowLeft' ? -1 : 0;
      if (!d) return; e.preventDefault(); const j = Math.min(options.length - 1, Math.max(0, i + d)); pick(j); btns[j].focus();
    });
    new ResizeObserver(() => paint(false)).observe(el);
    paint(false); el.set = v => { cur = v; paint(true); }; el.value = () => cur;
    return el;
  }
  function notch(stops, value, onPick, o = {}) {
    const fmtv = o.format || (v => String(v));
    let list = stops.slice(), cur = value, marks = o.marks || [];
    const el = h('div', 'notch'); el.tabIndex = 0; el.setAttribute('role', 'slider'); if (o.label) el.setAttribute('aria-label', o.label);
    const track = h('div', 'notch-track'), line = h('i', 'notch-line'), run = h('i', 'notch-run'), thumb = h('span', 'notch-thumb');
    const labels = h('div', 'notch-labels'), markBox = h('div', 'notch-marks');
    el.append(markBox, track, labels);
    const num = v => typeof v === 'number' && v > 0;
    const xOfIndex = i => (list.length < 2 ? 0 : i / (list.length - 1));
    const xOfValue = v => {
      const i = list.findIndex(s => s.v === v); if (i >= 0) return xOfIndex(i);
      if (!num(v)) return 0;
      const n = list.map(s => s.v);
      for (let k = 0; k < n.length - 1; k++) if (num(n[k]) && num(n[k + 1]) && v >= n[k] && v <= n[k + 1]) return xOfIndex(k + Math.log(v / n[k]) / Math.log(n[k + 1] / n[k]));
      const firstNum = n.findIndex(num); if (firstNum >= 0 && v < n[firstNum]) return xOfIndex(firstNum);
      return 1;
    };
    const build = () => {
      if (num(cur) && !list.some(s => s.v === cur)) { list.push({ v: cur, label: fmtv(cur), own: true }); list.sort((a, b) => (num(a.v) ? a.v : Infinity) - (num(b.v) ? b.v : Infinity)); }
      track.replaceChildren(line, run); labels.replaceChildren();
      list.forEach((s, i) => {
        const d = h('button', 'notch-stop'); d.type = 'button'; d.tabIndex = -1; d.title = s.label; d.style.setProperty('--x', xOfIndex(i));
        d.addEventListener('click', e => { e.stopPropagation(); pick(i); }); track.append(d);
        const l = h('span', 'notch-l', s.label); l.style.setProperty('--x', xOfIndex(i)); labels.append(l);
      });
      track.append(thumb); paint(); drawMarks();
    };
    const paint = () => {
      const x = xOfValue(cur); el.style.setProperty('--at', x);
      const i = list.findIndex(s => s.v === cur);
      [...labels.children].forEach((l, j) => l.classList.toggle('is-on', j === i));
      [...track.querySelectorAll('.notch-stop')].forEach((d, j) => d.classList.toggle('is-past', xOfIndex(j) <= x));
      el.setAttribute('aria-valuetext', i >= 0 ? list[i].label : fmtv(cur));
      requestAnimationFrame(thin);
    };
    const thin = () => {
      const ls = [...labels.children]; if (!ls.length || !labels.offsetWidth) return;
      ls.forEach(l => l.classList.remove('is-thin'));
      const gap = px('--sp-1'), boxes = ls.map(l => l.getBoundingClientRect());
      const order = ls.map((l, i) => i).sort((a, b) => (ls[b].classList.contains('is-on') - ls[a].classList.contains('is-on')) || ((b === 0 || b === ls.length - 1) - (a === 0 || a === ls.length - 1)) || a - b);
      const shown = [];
      order.forEach(i => { const r = boxes[i]; if (shown.some(s => r.left < s.right + gap && r.right + gap > s.left)) ls[i].classList.add('is-thin'); else shown.push(r); });
    };
    new ResizeObserver(thin).observe(labels);
    const drawMarks = () => {
      markBox.replaceChildren();
      marks.forEach(m => {
        const k = h('span', 'notch-mark' + (m.show ? ' is-shown' : '')); k.style.setProperty('--x', xOfValue(m.at)); k.style.setProperty('--c', m.c || 'var(--prussian)'); k.title = m.word || '';
        k.append(h('i'), h('span', 'notch-mark-w', m.word || '')); markBox.append(k);
      });
    };
    const pick = i => { const s = list[i]; if (!s || s.v === cur) return; cur = s.v; paint(); onPick && onPick(s.v); };
    const nearest = clientX => { const r = track.getBoundingClientRect(), t = Math.min(1, Math.max(0, (clientX - r.left) / r.width)); return Math.round(t * (list.length - 1)); };
    track.addEventListener('pointerdown', e => {
      track.setPointerCapture(e.pointerId); let i = nearest(e.clientX);
      el.style.setProperty('--at', xOfIndex(i));
      const move = ev => { i = nearest(ev.clientX); el.style.setProperty('--at', xOfIndex(i)); };
      const up = () => { track.removeEventListener('pointermove', move); track.removeEventListener('pointerup', up); pick(i); paint(); };
      track.addEventListener('pointermove', move); track.addEventListener('pointerup', up);
    });
    el.addEventListener('keydown', e => {
      const d = e.key === 'ArrowRight' || e.key === 'ArrowUp' ? 1 : e.key === 'ArrowLeft' || e.key === 'ArrowDown' ? -1 : 0;
      if (!d) return; e.preventDefault(); const i = list.findIndex(s => s.v === cur);
      pick(Math.min(list.length - 1, Math.max(0, (i < 0 ? 0 : i) + d)));
    });
    build();
    el.set = v => { cur = v; build(); }; el.value = () => cur; el.marks = m => { marks = m || []; drawMarks(); };
    return el;
  }
  const SV = {
    saving: { c: 'var(--ormolu)', ring: true, live: true, word: 'Saving' },
    applied: { c: 'var(--verdigris)', word: 'Applied' },
    saved: { c: 'var(--verdigris)', word: 'Saved' },
    restart: { c: 'var(--ormolu)', ring: true, word: 'At restart' },
    refused: { c: 'var(--vermilion)', word: 'Not saved' },
  };
  function saveState(o = {}) {
    const el = h('span', 'sv'), l = h('span', 'lamp'), w = h('span', 'sv-w'); el.append(l, w); el.setAttribute('aria-live', 'polite');
    let timer = 0;
    el.set = (state, word) => {
      clearTimeout(timer);
      const s = SV[state];
      el.dataset.s = state || 'idle';
      l.className = 'lamp' + (s && s.ring ? ' ring' : '') + (s && s.live ? ' live' : '');
      l.style.setProperty('--c', s ? s.c : 'transparent');
      w.textContent = word != null ? word : s ? s.word : ''; w.title = w.textContent;
      if (state === 'applied' || state === 'saved') timer = setTimeout(() => el.set('idle', o.rest ? o.rest() : ''), px('--dur') * 8);
    };
    el.set('idle', o.rest ? o.rest() : '');
    return el;
  }
  function setting(o) {
    const row = h('div', 'setting'), nm = h('div', 'setting-nm');
    nm.append(h('span', 'setting-name', o.name)); if (o.sub) nm.append(h('span', 'setting-sub', o.sub));
    const ctl = h('div', 'setting-ctl'); if (o.control) ctl.append(o.control);
    row.append(nm, ctl, o.state || h('span'));
    if (o.under) { const u = h('div', 'setting-under'); u.append(o.under); row.append(u); }
    row._p = { nm, ctl, state: o.state };
    return row;
  }

  /* ---------- a sign-in in a glass: api.js's SIGNIN steps [s], drawn with the kit's panel rows. Its buttons carry
     data-act "signin" (Try again), "reopen-signin" (Open the sign-in page again) and "cancel-signin" (Cancel), which
     the page holding the sign-in answers. ---------- */
  function signRows(s) {
    const box = h('div');
    if (s.state === 'done') {
      const r = h('div', 'lr');
      r.append(h('div', 'lr-nm sign-word', s.label ? 'Signed in as ' + s.label : 'Signed in'));
      box.append(r);
      return box;
    }
    if (s.state === 'fail') {
      const r = h('div', 'lr'); r.append(h('div', 'lr-nm sign-word', s.fail ? s.fail.word : 'Not completed'));
      if (s.why) r.append(h('div', 'lr-sub sign-why', s.why));
      const acts = h('div', 'lr-acts');
      // Try again only where api.js's failure kinds say a second try is one (retry): a login that is already signed in
      // and waits on a restart, or one already added, would be signed in twice
      if (!s.fail || s.fail.retry) { const b = h('button', 'btn press primary', 'Try again'); b.dataset.act = 'signin'; acts.append(b); }
      r.append(acts); box.append(r);
      return box;
    }
    // signed in, and splice is restarting to read the login (api.js, by: "restart"): nothing is left to open, type or
    // cancel, and the glass says so until the next splice answers
    if (s.by === 'restart') {
      const r = h('div', 'lr');
      r.append(h('div', 'lr-nm', 'Signed in'), h('div', 'lr-sub', 'Restarting splice to use this login'));
      box.append(r);
      return box;
    }
    const r = h('div', 'lr');
    r.append(h('div', 'lr-nm', s.state === 'ending' ? 'Cancelling' : 'Signing in'));
    if (s.tabClosed) {
      r.append(h('div', 'lr-sub sign-why', 'The sign-in page was closed before the sign-in finished.'));
    }
    const ctl = h('div', 'lr-ctl');
    if (s.url && s.tabClosed) {
      const o = h('button', 'btn press primary', s.by === 'code' ? 'Open the code page again' : 'Open the sign-in page again');
      o.type = 'button'; o.dataset.act = 'reopen-signin'; ctl.append(o);
    } else if (s.url) {
      const o = h('a', 'btn press primary', s.by === 'code' ? 'Open the code page' : 'Open the sign-in page');
      o.href = s.url; o.target = '_blank'; o.rel = 'noopener noreferrer'; ctl.append(o);
    } else {
      ctl.append(h('span', 'quiet small', 'Waiting for splice to start it'));
    }
    if (s.by === 'code' && s.code) ctl.append(h('span', 'sign-code', s.code));
    r.append(ctl);
    if (s.by === 'paste') {
      const sub = h('div', 'lr-ctl'), inp = h('input', 'lr-in'); inp.placeholder = 'Paste the code'; inp.setAttribute('aria-label', 'Code from the sign-in page');
      const go = h('button', 'btn press', 'Send code'); go.type = 'button';
      const send = () => { const v = inp.value.trim(); if (v) SIGNIN.code(s, s.on, v); };
      go.addEventListener('click', send);
      inp.addEventListener('keydown', e => { e.stopPropagation(); if (e.key === 'Enter') { e.preventDefault(); send(); } });
      sub.append(inp, go); r.append(sub);
    }
    const acts = h('div', 'lr-acts'), c = h('button', 'btn quiet press', 'Cancel'); c.dataset.act = 'cancel-signin';
    acts.append(c); r.append(acts);
    box.append(r);
    return box;
  }

  const PAGES = DECK_PAGES.map(([file, name]) => ({ file: file + '.html', name }));
  const api = { PAGES, setTheme, $, $$, h, fmt, count, clock, money, pct, css, rem, px, ago, STATES, lamp, ICON, iconBtn, show, hide, glass, layer, openMenu, closeMenu, inspector, setNeed, answer, spark, strip, stripInto, put, roll, meter, choice, notch, saveState, setting, signRows };
  return api;
})();
