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

  // The head column carries the drawing's lower half beside the open answer: the stack of answer cards stands level with the middle of the
  // answer that is open, and the ten rows by the heading move down by part of the distance, so no stretch of the column is only a cable.
  // The list tells the column its height as the row opens (a ResizeObserver), so the stack rises with the answer; with none open the stack
  // stands at the foot of the column, where the stylesheet puts it.
  var art = document.querySelector(".qa-art");
  var deck = art && art.querySelector(".qa-deck");
  var rows = art && art.querySelector("svg");
  var junction = art && art.querySelector(".qa-mid");
  var last = null;
  var frame = 0;
  var MIN_GAP = 40;
  var REACH = 290;

  function clear() {
    art.classList.remove("qa-follow");
    art.style.removeProperty("--qa-lift");
    art.style.removeProperty("--qa-ga");
    art.style.removeProperty("--qa-gb");
  }

  function place() {
    frame = 0;
    var row = last && last.open ? last : list.querySelector(".qa-q[open]");
    if (!row || getComputedStyle(deck).display === "none") return clear();
    var sum = row.querySelector("summary");
    var ans = row.querySelector(".qa-ans");
    if (!sum || !ans) return clear();
    var a = art.getBoundingClientRect();
    var r = rows.getBoundingClientRect();
    var was = r.top - a.top;
    var rowsFoot = r.bottom - a.top - was;
    var h = deck.getBoundingClientRect().height;
    var m = junction.getBoundingClientRect().height;
    var box = row.getBoundingClientRect();
    var mid = box.top + sum.offsetHeight + ans.offsetHeight / 2 - a.top;
    var foot = list.getBoundingClientRect().bottom + Math.max(0, sum.offsetHeight + ans.offsetHeight - box.height) - a.top;
    // level with the middle of the answer, but never so high that more than REACH of the column is left bare under the stack
    var top = Math.max(rowsFoot + m + 2 * MIN_GAP, Math.min(Math.max(mid - h / 2, foot - h - REACH), foot - h));
    // what lies between the rows and the stack is shared by three stretches: the one above the rows (the art's own margin and the lift) and the
    // two cable lengths either side of the junction box, so each is about as long as the others
    var space = top - rowsFoot - m;
    var margin = parseFloat(getComputedStyle(art).marginTop) || 0;
    var lift = Math.max(0, (space - 2 * margin) / 3);
    var gap = (space - lift) / 2;
    art.style.setProperty("--qa-lift", lift + "px");
    art.style.setProperty("--qa-ga", gap + "px");
    art.style.setProperty("--qa-gb", gap + "px");
    art.classList.add("qa-follow");
  }

  function soon() {
    if (!frame) frame = requestAnimationFrame(place);
  }

  if (art && deck && rows && junction && window.ResizeObserver) {
    list.addEventListener("toggle", function (e) {
      if (e.target instanceof HTMLDetailsElement) last = e.target;
      soon();
    }, true);
    new ResizeObserver(soon).observe(list);
  }
})();
