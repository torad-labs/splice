// dashboard band: the first time a tab is switched, the window is marked db-live, so the panel that comes in eases in
// (css) and the meter fills of the reveal, which play once as the band enters, do not play again when a panel comes back.
// Every switch also eases the window's height from the tab it leaves to the tab it shows: two auto heights cannot be
// transitioned by css, so the window is held at the old height, then set to the new one with the transition, and let go
// when it ends. Under reduced motion the height changes at once.
(function () {
  "use strict";
  var win = document.querySelector(".db-win");
  if (!win) return;
  var calm = window.matchMedia("(prefers-reduced-motion: reduce)");
  var last = win.getBoundingClientRect().height;
  var watch = typeof ResizeObserver === "function" ? new ResizeObserver(function (entries) {
    var box = entries[entries.length - 1].borderBoxSize;
    last = box && box[0] ? box[0].blockSize : win.getBoundingClientRect().height;
  }) : null;
  if (watch) watch.observe(win);
  var timer = 0;

  function release() {
    clearTimeout(timer);
    win.classList.remove("db-ease");
    win.style.height = "";
    win.style.transition = "";
  }

  win.addEventListener("transitionend", function (e) {
    if (e.target === win && e.propertyName === "height") release();
  });

  win.addEventListener("change", function (e) {
    if (!(e.target instanceof Element) || !e.target.matches("input[type=radio]")) return;
    win.classList.add("db-live");
    if (!watch || calm.matches) return;
    var from = last;
    release();
    var to = win.getBoundingClientRect().height;
    if (Math.abs(to - from) < 1) return;
    win.classList.add("db-ease");
    win.style.height = from + "px";
    void win.offsetHeight;
    win.style.transition = "height var(--t-snap) var(--e-out)";
    win.style.height = to + "px";
    timer = setTimeout(release, 600);
  });
})();
