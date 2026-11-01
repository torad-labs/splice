// share band: the first time a tab is switched, the box is marked sh-moved, so the pane that comes in eases in (css). Every
// switch also eases the panes' box from the height of the tab it leaves to the height of the tab it shows: two auto heights
// cannot be transitioned by css, so the box is held at the old height, then set to the new one with the transition, and let
// go when it ends. The panes are each as tall as their own content. Under reduced motion the height changes at once.
(function () {
  "use strict";
  var more = document.querySelector(".sh-more");
  var box = more && more.querySelector(".sh-panes");
  if (!box) return;
  var calm = window.matchMedia("(prefers-reduced-motion: reduce)");
  var last = box.getBoundingClientRect().height;
  var watch = typeof ResizeObserver === "function" ? new ResizeObserver(function (entries) {
    var size = entries[entries.length - 1].borderBoxSize;
    last = size && size[0] ? size[0].blockSize : box.getBoundingClientRect().height;
  }) : null;
  if (watch) watch.observe(box);
  var timer = 0;

  function release() {
    clearTimeout(timer);
    box.classList.remove("sh-ease");
    box.style.height = "";
    box.style.transition = "";
  }

  box.addEventListener("transitionend", function (e) {
    if (e.target === box && e.propertyName === "height") release();
  });

  more.addEventListener("change", function (e) {
    if (!(e.target instanceof Element) || !e.target.matches("input[type=radio]")) return;
    more.classList.add("sh-moved");
    if (!watch || calm.matches) return;
    var from = last;
    release();
    var to = box.getBoundingClientRect().height;
    if (Math.abs(to - from) < 1) return;
    box.classList.add("sh-ease");
    box.style.height = from + "px";
    void box.offsetHeight;
    box.style.transition = "height var(--t-snap) var(--e-out)";
    box.style.height = to + "px";
    timer = setTimeout(release, 600);
  });
})();
