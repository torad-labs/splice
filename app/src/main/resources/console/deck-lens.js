/* deck-lens.js: the Night Deck kit's lens (the prototype kit's lens.js, ported as it is on Nov 19, 2026). */
/* Night Deck lens: one optical model for every glass surface (Chromium backdrop route). */
window.DeckLens = (() => {
  const ns = 'http://www.w3.org/2000/svg';
  const svg = document.createElementNS(ns, 'svg');
  svg.setAttribute('aria-hidden', 'true');
  svg.setAttribute('width', '0'); svg.setAttribute('height', '0');
  svg.style.position = 'absolute';
  const defs = document.createElementNS(ns, 'defs');
  svg.append(defs);
  document.body.append(svg);
  const chromium = /Chrome\/|Chromium|Edg\//.test(navigator.userAgent) && !/Firefox/.test(navigator.userAgent);
  const reduceT = matchMedia('(prefers-reduced-transparency: reduce)');
  const contrast = matchMedia('(prefers-contrast: more)');
  const recs = new Map();
  let serial = 0;
  const make = (name, attrs) => { const n = document.createElementNS(ns, name); for (const k in attrs) n.setAttribute(k, attrs[k]); return n; };
  const rootPx = () => parseFloat(getComputedStyle(document.documentElement).fontSize);
  const len = (cs, name) => { const v = cs.getPropertyValue(name).trim(); if (!v) return 0; return v.endsWith('rem') ? parseFloat(v) * rootPx() : parseFloat(v); };

  /* Sextant's single-interface Snell model through a convex squircle rim (glass-optics.ts). */
  function offsetAt(s, x, y) {
    const cx = x - s.w / 2, cy = y - s.h / 2;
    const qx = Math.abs(cx) - s.w / 2 + s.r, qy = Math.abs(cy) - s.h / 2 + s.r;
    const ox = Math.max(qx, 0), oy = Math.max(qy, 0), out = Math.hypot(ox, oy);
    const inset = -(Math.min(Math.max(qx, qy), 0) + out - s.r);
    if (inset < 0) return [0, 0, 0];
    const zoom = 1 - 1 / s.zoom;
    const c = [-cx * zoom, -cy * zoom];
    if (inset >= s.bezel) return [c[0], c[1], 1];
    const nx = out > 0 ? (ox / out) * Math.sign(cx) : qx > qy ? Math.sign(cx) : 0;
    const ny = out > 0 ? (oy / out) * Math.sign(cy) : qy >= qx ? Math.sign(cy) : 0;
    const t = Math.max(0.001, inset / s.bezel);
    const base = 1 - (1 - t) ** 4;
    const height = base ** 0.25;
    const slope = ((s.depth / s.bezel) * (1 - t) ** 3) / base ** 0.75;
    const inc = Math.atan(slope), ref = Math.asin(Math.sin(inc) / 1.5);
    const off = s.depth * (0.25 + 0.75 * height) * Math.tan(inc - ref);
    /* frost weight: zero on the outer rim, full by the inner third of the band */
    const k = Math.min(1, Math.max(0, (t - 0.25) / 0.5));
    const frost = k * k * (3 - 2 * k);
    return [c[0] - nx * off, c[1] - ny * off, frost];
  }

  function draw(el) {
    const rec = recs.get(el);
    if (!rec) return;
    if (!chromium || reduceT.matches || contrast.matches) { el.style.removeProperty('--lens'); return; }
    const w = el.offsetWidth, h = el.offsetHeight;
    if (w < 2 || h < 2) return;
    const cs = getComputedStyle(el);
    const r = Math.min(parseFloat(cs.borderTopLeftRadius) || 0, w / 2, h / 2);
    const s = {
      w, h, r,
      bezel: Math.max(2, Math.min(len(cs, '--lens-bezel'), Math.min(w, h) / 2)),
      depth: len(cs, '--lens-depth'),
      zoom: parseFloat(cs.getPropertyValue('--lens-zoom')) || 1,
    };
    rec.frost = len(cs, '--lens-frost');
    rec.sat = cs.getPropertyValue('--lens-sat').trim() || '1';
    const key = [w, h, r, s.bezel, s.depth, s.zoom].join(':');
    if (rec.key !== key) {
      rec.key = key;
      const ratio = Math.min(devicePixelRatio || 1, 1400 / w, 1400 / h);
      const mw = Math.max(2, Math.ceil(w * ratio)), mh = Math.max(2, Math.ceil(h * ratio));
      const vals = new Float32Array(mw * mh * 3);
      let max = 0.5;
      for (let y = 0; y < mh; y++) for (let x = 0; x < mw; x++) {
        const o = offsetAt(s, (x + 0.5) / ratio, (y + 0.5) / ratio), i = (y * mw + x) * 3;
        vals[i] = o[0]; vals[i + 1] = o[1]; vals[i + 2] = o[2];
        max = Math.max(max, Math.abs(o[0]), Math.abs(o[1]));
      }
      const scale = 2 * max * 1.02;
      const map = new ImageData(mw, mh), mask = new ImageData(mw, mh);
      for (let p = 0; p < mw * mh; p++) {
        map.data[p * 4] = Math.round(255 * (0.5 + vals[p * 3] / scale));
        map.data[p * 4 + 1] = Math.round(255 * (0.5 + vals[p * 3 + 1] / scale));
        map.data[p * 4 + 2] = 128; map.data[p * 4 + 3] = 255;
        mask.data[p * 4] = mask.data[p * 4 + 1] = mask.data[p * 4 + 2] = 255;
        mask.data[p * 4 + 3] = Math.round(255 * vals[p * 3 + 2]);
      }
      const url = img => { const c = document.createElement('canvas'); c.width = mw; c.height = mh; c.getContext('2d').putImageData(img, 0, 0); return c.toDataURL(); };
      rec.mapImg.setAttribute('href', url(map));
      rec.maskImg.setAttribute('href', url(mask));
      for (const n of [rec.filter, rec.mapImg, rec.maskImg]) { n.setAttribute('width', w); n.setAttribute('height', h); }
      rec.scale = scale;
    }
    rec.sat_m.setAttribute('values', rec.sat);
    apply(rec);
    el.style.setProperty('--lens', `url(#${rec.filter.id})`);
  }
  function apply(rec) {
    rec.disp.setAttribute('scale', String(rec.scale * rec.k));
    rec.blur.setAttribute('stdDeviation', String(rec.frost * rec.k));
  }
  function add(el) {
    if (recs.has(el)) return;
    const id = 'deck-lens-' + ++serial;
    const filter = make('filter', { id, x: '0', y: '0', filterUnits: 'userSpaceOnUse', primitiveUnits: 'userSpaceOnUse', 'color-interpolation-filters': 'sRGB' });
    const mapImg = make('feImage', { x: '0', y: '0', preserveAspectRatio: 'none', result: 'map' });
    const maskImg = make('feImage', { x: '0', y: '0', preserveAspectRatio: 'none', result: 'mask' });
    const disp = make('feDisplacementMap', { in: 'SourceGraphic', in2: 'map', xChannelSelector: 'R', yChannelSelector: 'G', result: 'bent' });
    const blur = make('feGaussianBlur', { in: 'bent', result: 'frost', 'edgeMode': 'duplicate' });
    const sat_m = make('feColorMatrix', { in: 'frost', type: 'saturate', values: '1', result: 'lift' });
    const inner = make('feComposite', { in: 'lift', in2: 'mask', operator: 'in', result: 'inner' });
    const merge = make('feMerge', {});
    merge.append(make('feMergeNode', { in: 'bent' }), make('feMergeNode', { in: 'inner' }));
    filter.append(mapImg, maskImg, disp, blur, sat_m, inner, merge);
    defs.append(filter);
    recs.set(el, { filter, mapImg, maskImg, disp, blur, sat_m, key: '', scale: 0, frost: 0, k: 1, sat: '1' });
    ro.observe(el);
    draw(el);
  }
  const ro = new ResizeObserver(es => { for (const e of es) draw(e.target); });
  /* Materialise: ramp lensing and frost from 0 to 1 (or back). Tint and shadow follow through --mat in CSS. */
  function materialise(el, on, ms) {
    const rec = recs.get(el);
    el.style.setProperty('--mat', on ? 1 : 0);
    if (!rec) return Promise.resolve();
    const from = rec.k, to = on ? 1 : 0;
    if (matchMedia('(prefers-reduced-motion: reduce)').matches || !ms) { rec.k = to; apply(rec); return Promise.resolve(); }
    const t0 = performance.now();
    return new Promise(done => {
      const step = now => {
        const p = Math.min(1, (now - t0) / ms), e = 1 - (1 - p) ** 3;
        rec.k = from + (to - from) * e; apply(rec);
        if (p < 1) requestAnimationFrame(step); else done();
      };
      requestAnimationFrame(step);
    });
  }
  function scan(root = document) { root.querySelectorAll('[data-lens]').forEach(add); }
  const refresh = () => { for (const el of recs.keys()) { recs.get(el).key = ''; draw(el); } };
  reduceT.addEventListener('change', refresh);
  contrast.addEventListener('change', refresh);
  window.addEventListener('resize', refresh);
  /* Light: a faint glow follows the pointer; a press illuminates from the contact point. */
  document.addEventListener('pointermove', e => {
    const g = e.target.closest && e.target.closest('[data-lens]');
    if (!g) return;
    const b = g.getBoundingClientRect();
    g.style.setProperty('--lx', (e.clientX - b.left) + 'px');
    g.style.setProperty('--ly', (e.clientY - b.top) + 'px');
  }, { passive: true });
  document.addEventListener('pointerdown', e => {
    const c = e.target.closest && e.target.closest('.press');
    if (!c) return;
    const b = c.getBoundingClientRect();
    c.style.setProperty('--px', (e.clientX - b.left) + 'px');
    c.style.setProperty('--py', (e.clientY - b.top) + 'px');
    c.classList.add('is-pressed');
    const up = () => { c.classList.remove('is-pressed'); removeEventListener('pointerup', up); removeEventListener('pointercancel', up); };
    addEventListener('pointerup', up); addEventListener('pointercancel', up);
  });
  const redraw = el => { const r = recs.get(el); if (r) { r.key = ""; draw(el); } };
  return { scan, add, redraw, materialise, refresh, chromium };
})();
