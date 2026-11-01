// setup band: the three steps are radios, so they switch with no script. This only plays the opening of a step (is-opening on
// the panel that opens, is-fresh on the steps that changed, for 600 ms) and opens the Install step when the header's Install
// button, or a page opened at #setup-install, asks for it.
(function () {
  "use strict";
  var win = document.querySelector(".su-win");
  if (!win) return;
  var radios = Array.prototype.slice.call(win.querySelectorAll(".su-r"));
  var steps = win.querySelectorAll(".su-st");
  var panels = win.querySelectorAll(".su-panel");
  if (radios.length !== 3 || steps.length !== 3 || panels.length !== 3) return;

  function open() {
    for (var i = 0; i < radios.length; i++) if (radios[i].checked) return i;
    return 0;
  }
  var current = open();
  var timer = 0;

  function play(was, now) {
    win.classList.add("is-touched");
    clearTimeout(timer);
    for (var i = 0; i < 3; i++) {
      panels[i].classList.remove("is-opening");
      steps[i].classList.remove("is-fresh");
    }
    void win.offsetWidth; // so a step pressed again restarts its animation
    panels[now].classList.add("is-opening");
    for (var k = 0; k < 3; k++) {
      // a step is fresh when it is the one that opened, or when it turned to a tick or back to its number
      if (k === now || (k < was) !== (k < now)) steps[k].classList.add("is-fresh");
    }
    timer = setTimeout(function () {
      for (var j = 0; j < 3; j++) {
        panels[j].classList.remove("is-opening");
        steps[j].classList.remove("is-fresh");
      }
    }, 600);
  }

  radios.forEach(function (r, n) {
    r.addEventListener("change", function () {
      if (!r.checked || n === current) return;
      var was = current;
      current = n;
      play(was, n);
    });
  });

  function showInstall() {
    if (radios[0].checked) return;
    radios[0].checked = true;
    radios[0].dispatchEvent(new Event("change", { bubbles: true }));
  }
  document.addEventListener("click", function (e) {
    var a = e.target instanceof Element ? e.target.closest('a[href="#setup-install"]') : null;
    if (a) showInstall();
  });
  window.addEventListener("hashchange", function () { if (location.hash === "#setup-install") showInstall(); });
  if (location.hash === "#setup-install") showInstall();
})();
