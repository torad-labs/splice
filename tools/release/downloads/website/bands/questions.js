// questions band: when a row opens, scroll it into view with its top 16 px under the fixed header (64 px, 56 px on a phone),
// smooth only when the visitor has no motion preference, and only when the opened row's top or its answer would sit off screen.
(function () {
  "use strict";
  var list = document.querySelector(".qa-list");
  if (!list) return;
  var calm = window.matchMedia ? window.matchMedia("(prefers-reduced-motion: no-preference)") : null;

  function header() {
    var h = parseFloat(getComputedStyle(document.documentElement).getPropertyValue("--fr-hh"));
    return h > 0 ? h : window.innerWidth <= 820 ? 56 : 64;
  }

  // toggle does not bubble, so the list listens in the capture phase
  list.addEventListener("toggle", function (e) {
    var row = e.target;
    if (!(row instanceof HTMLDetailsElement) || !row.open || !row.classList.contains("qa-q")) return;
    var sum = row.querySelector("summary");
    var ans = row.querySelector(".qa-ans");
    if (!sum || !ans) return;
    var hh = header();
    var box = row.getBoundingClientRect();
    var top = box.top;
    // the answer's own height is its full height even while the row is still opening, so the row's finished foot is known now
    var foot = top + sum.offsetHeight + ans.offsetHeight;
    if (top >= hh && foot <= window.innerHeight) return;
    // While the row opens, the page is still short by what the answer has yet to grow, and a scroll that starts now stops
    // at today's foot of the page: a row near the end landed hundreds of pixels low. Aim where the finished page allows,
    // and lend the page that room under its foot until the row has opened.
    var doc = document.documentElement;
    var grow = Math.max(0, sum.offsetHeight + ans.offsetHeight - box.height);
    var aim = Math.min(window.scrollY + top - hh - 16, doc.scrollHeight + grow - window.innerHeight);
    var lend = Math.ceil(aim - (doc.scrollHeight - window.innerHeight));
    if (lend > 0) {
      var body = document.body;
      var was = body.style.paddingBottom;
      body.style.paddingBottom = lend + "px";
      var give = function () {
        row.removeEventListener("transitionend", ended);
        clearTimeout(late);
        body.style.paddingBottom = was;
      };
      var ended = function (te) { if (te.target === row && te.propertyName === "block-size") give(); };
      row.addEventListener("transitionend", ended);
      var late = setTimeout(give, 1200);
    }
    window.scrollTo({ top: aim, behavior: calm && calm.matches ? "smooth" : "auto" });
  }, true);

  // The head column carries the whole drawing as one piece pinned under the header (sticky in the stylesheet), so the stack of answer cards
  // stays beside the part of an open answer that is in view. The piece is as tall as the column and the screen allow: no taller than the screen
  // under the header less the heading's own height, no taller than its parts plus 280 px of cable between each two, and no taller than the column (a short column holds it
  // to its own height). The two cable lengths share what is left. Under the heading its foot is on screen when the band's top is.
  var art = document.querySelector(".qa-art");
  var deck = art && art.querySelector(".qa-deck");
  var rows = art && art.querySelector("svg");
  var junction = art && art.querySelector(".qa-mid");
  var frame = 0;
  var MIN_GAP = 24;
  var REACH = 280;

  function clear() {
    art.classList.remove("qa-follow");
    art.style.removeProperty("--qa-ah");
    art.style.removeProperty("--qa-ga");
    art.style.removeProperty("--qa-gb");
  }

  // an svg of the drawing is as tall as its width times its viewBox's ratio
  function tall(svg, w) {
    var vb = svg.viewBox.baseVal;
    return (w * vb.height) / vb.width;
  }

  function place() {
    frame = 0;
    if (getComputedStyle(deck).display === "none") return clear();
    art.classList.add("qa-follow");
    var head = art.parentElement;
    var h2 = head.querySelector(".pg-h2");
    var w = art.clientWidth;
    var parts = tall(rows, w) + tall(junction, w) + tall(deck, w);
    var top = h2.getBoundingClientRect().bottom + (parseFloat(getComputedStyle(art).marginTop) || 0);
    var room = list.getBoundingClientRect().bottom - top;
    var screen = window.innerHeight - header() - 2 * 16 - (top - head.getBoundingClientRect().top);
    var h = Math.max(parts + 2 * MIN_GAP, Math.min(room, screen, parts + 2 * REACH));
    var gap = (h - parts) / 2;
    art.style.setProperty("--qa-ah", h + "px");
    art.style.setProperty("--qa-ga", gap + "px");
    art.style.setProperty("--qa-gb", gap + "px");
  }

  function soon() {
    if (!frame) frame = requestAnimationFrame(place);
  }

  if (art && deck && rows && junction && window.ResizeObserver) {
    new ResizeObserver(soon).observe(list);
    window.addEventListener("resize", soon);
    soon();
  }
})();
