// dashboard band: the first time a tab is switched, the window is marked db-live, so the panel that comes in eases in
// (css) and the meter fills of the reveal, which play once as the band enters, do not play again when a panel comes back.
(function () {
  "use strict";
  var win = document.querySelector(".db-win");
  if (!win) return;
  win.addEventListener("change", function (e) {
    if (e.target instanceof Element && e.target.matches("input[type=radio]")) win.classList.add("db-live");
  });
})();
