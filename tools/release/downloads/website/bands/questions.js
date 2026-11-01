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
})();
