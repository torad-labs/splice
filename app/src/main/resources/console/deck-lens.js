/* deck-lens.js: how a Night Deck panel comes and goes, and the light under the pointer.
   Nov 21, 2026: this file built the lens, an SVG filter that bent and blurred the page behind every panel (the
   prototype kit's lens.js). The panels are solid now (deck.css, .glass): that filter was drawn again on every frame
   anything near it changed, and on a 3840 px screen it made the console too slow to use. The lens went with it. */
window.DeckLens = (() => {
  /* Materialise: a panel's --mat goes to 1 (or back to 0), and its opacity and shadow follow it in CSS over the kit's
     own transition. Resolves once [ms] have passed, so a caller hides the panel after it has gone. */
  function materialise(el, on, ms) {
    el.style.setProperty('--mat', on ? 1 : 0);
    if (!ms || matchMedia('(prefers-reduced-motion: reduce)').matches) return Promise.resolve();
    return new Promise(done => setTimeout(done, ms));
  }
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
  return { materialise };
})();
