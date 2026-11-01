// accounts band: the rotation plays once. The markup at rest is the end of it (the first ChatGPT login locked, the second serving,
// and the line that says it moved). When the band first scrolls into view, the two logins start as they were before the limit hit,
// then the first locks at 440 ms and the second takes over at 700 ms. Without scripts, motion or an observer, the page keeps the end.
(function () {
  "use strict";
  var band = document.querySelector(".ac-in");
  var rail = band ? band.querySelector("[data-rotate]") : null;
  if (!band || !rail) return;
  if (!document.documentElement.classList.contains("pg-js") || band.classList.contains("is-seen")) return;
  if (!window.MutationObserver || (window.matchMedia && window.matchMedia("(prefers-reduced-motion: reduce)").matches)) return;
  var cards = rail.querySelectorAll(":scope > .card");
  if (cards.length !== 2) return;

  var lockedAt = 440;
  var takenAt = 700;
  var done = [cards[0], cards[1]];

  // the login before its limit hit: serving, 99% of the week, nothing under its name
  function serving(card) {
    var c = card.cloneNode(true);
    c.className = "card serving";
    var slot = c.querySelector(".slot");
    if (slot) slot.textContent = "";
    var held = c.querySelector(".heldline");
    if (held) held.remove();
    c.querySelectorAll("[style*='--r']").forEach(function (el) { el.style.setProperty("--r", "1"); });
    var bar = c.querySelector(".bar");
    if (bar) {
      bar.classList.remove("full");
      if (bar.firstElementChild) bar.firstElementChild.style.width = "99%";
    }
    var pct = c.querySelector(".pct");
    if (pct) {
      pct.classList.remove("limit");
      pct.classList.add("ac-fig");
      if (pct.firstChild && pct.firstChild.nodeType === 3) pct.firstChild.nodeValue = "99%";
    }
    return c;
  }

  // the login before it was taken: waiting, no moves line yet
  function waiting(card) {
    var c = card.cloneNode(true);
    c.className = "card";
    var moves = c.querySelector(".moves");
    if (moves) moves.remove();
    var pct = c.querySelector(".pct");
    if (pct) pct.classList.remove("ac-fig");
    return c;
  }

  var first = serving(cards[0]);
  var second = waiting(cards[1]);
  // keep the rail as tall as it ends, so nothing below it moves while the two logins change
  rail.style.minHeight = rail.offsetHeight + "px";
  cards[0].replaceWith(first);
  cards[1].replaceWith(second);

  function swap(from, to) {
    to.classList.add("ac-sw");
    from.replaceWith(to);
  }

  var watcher = new MutationObserver(function () {
    if (!band.classList.contains("is-seen")) return;
    watcher.disconnect();
    setTimeout(function () { swap(first, done[0]); }, lockedAt);
    setTimeout(function () {
      swap(second, done[1]);
      setTimeout(function () { rail.style.minHeight = ""; }, 600);
    }, takenAt);
  });
  watcher.observe(band, { attributes: true, attributeFilter: ["class"] });
})();
