// What every band of splice.torad.ai shares. The head's inline script has already set the theme and the pg-js class
// before the first paint; this runs after the page is parsed.
(function () {
  "use strict";
  var root = document.documentElement;
  var KEY = "splice-site-theme";

  // Theme: Auto follows the system, Daylight and Candlelight hold until changed. Every group of
  // [data-theme-choice] buttons on the page (header, menu, footer) shows the same choice.
  var dark = window.matchMedia ? window.matchMedia("(prefers-color-scheme: dark)") : null;
  function stored() {
    try { return localStorage.getItem(KEY) || "auto"; } catch (e) { return "auto"; }
  }
  function apply(choice) {
    var theme = choice === "day" ? "day" : choice === "night" ? "night" : dark && !dark.matches ? "day" : "night";
    root.setAttribute("data-theme", theme);
    document.querySelectorAll("[data-theme-choice]").forEach(function (b) {
      b.setAttribute("aria-pressed", String(b.getAttribute("data-theme-choice") === choice));
    });
  }
  document.addEventListener("click", function (e) {
    var b = e.target instanceof Element ? e.target.closest("[data-theme-choice]") : null;
    if (!b) return;
    var choice = b.getAttribute("data-theme-choice") || "auto";
    try { if (choice === "auto") localStorage.removeItem(KEY); else localStorage.setItem(KEY, choice); } catch (err) { /* private mode */ }
    apply(choice);
  });
  if (dark && dark.addEventListener) dark.addEventListener("change", function () { if (stored() === "auto") apply("auto"); });
  apply(stored());

  // Reveal: an element with data-reveal gets is-seen once, the first time it scrolls into view, and keeps it.
  var reveal = document.querySelectorAll("[data-reveal]");
  if (!("IntersectionObserver" in window)) {
    reveal.forEach(function (el) { el.classList.add("is-seen"); });
  } else {
    var io = new IntersectionObserver(function (entries) {
      entries.forEach(function (en) {
        if (!en.isIntersecting) return;
        en.target.classList.add("is-seen");
        io.unobserve(en.target);
      });
    }, { rootMargin: "0px 0px -12% 0px" });
    reveal.forEach(function (el) { io.observe(el); });
  }

  // Copy: a [data-copy] button copies its text and reads Copied for a moment. Without a clipboard (a page not served
  // over https) the buttons stay hidden, and the text stays selectable. A button that holds both words, Copy in
  // [data-copy-label] and Copied in [data-copied-label], keeps its markup and swaps them with is-copied (the page's css
  // slides one out and the other in); any other button has its text replaced.
  var copies = document.querySelectorAll("[data-copy]");
  if (navigator.clipboard && window.isSecureContext) {
    copies.forEach(function (b) {
      b.hidden = false;
      var label = b.textContent;
      var was = b.querySelector("[data-copy-label]");
      var now = b.querySelector("[data-copied-label]");
      var timer = 0;
      b.addEventListener("click", function () {
        navigator.clipboard.writeText(b.getAttribute("data-copy") || "").then(function () {
          if (was && now) {
            was.setAttribute("aria-hidden", "true");
            now.removeAttribute("aria-hidden");
          } else b.textContent = "Copied";
          b.classList.add("is-copied");
          clearTimeout(timer);
          timer = setTimeout(function () {
            if (was && now) {
              now.setAttribute("aria-hidden", "true");
              was.removeAttribute("aria-hidden");
            } else b.textContent = label;
            b.classList.remove("is-copied");
          }, 1600);
        });
      });
    });
  }

  // Menu: the header's [data-menu] button opens and closes the sheet it controls (a phone's navigation). It closes on
  // Escape, on a link inside it and when the window grows past the width where the header shows its links (1200 px).
  var menuBtn = document.querySelector("[data-menu]");
  var sheet = menuBtn ? document.getElementById(menuBtn.getAttribute("aria-controls") || "") : null;
  if (menuBtn && sheet) {
    var setMenu = function (open) {
      sheet.hidden = !open;
      menuBtn.setAttribute("aria-expanded", String(open));
      if (open) menuBtn.removeAttribute("aria-label"); else menuBtn.setAttribute("aria-label", "Open menu");
    };
    menuBtn.addEventListener("click", function () { setMenu(sheet.hidden); });
    sheet.addEventListener("click", function (e) { if (e.target instanceof Element && e.target.closest("a")) setMenu(false); });
    document.addEventListener("keydown", function (e) {
      if (e.key === "Escape" && !sheet.hidden) { setMenu(false); menuBtn.focus(); }
    });
    if (window.matchMedia) window.matchMedia("(min-width: 1201px)").addEventListener("change", function (m) { if (m.matches) setMenu(false); });
  }

  // Film: Play the film opens the film window (a modal dialog) and starts the film; Close, Escape or a click on the dim
  // layer closes it and stops the film. Without scripts the link goes to the film itself.
  var film = document.getElementById("fr-film");
  if (film && typeof film.showModal === "function") {
    var video = film.querySelector("video");
    document.querySelectorAll("[data-film]").forEach(function (a) {
      a.addEventListener("click", function (e) {
        e.preventDefault();
        film.showModal();
        if (video) { var p = video.play(); if (p && p.catch) p.catch(function () { /* the viewer presses play */ }); }
      });
    });
    var closeBtn = film.querySelector("[data-film-close]");
    if (closeBtn) closeBtn.addEventListener("click", function () { film.close(); });
    film.addEventListener("click", function (e) {
      if (e.target !== film) return;
      var r = film.getBoundingClientRect();
      if (e.clientX < r.left || e.clientX > r.right || e.clientY < r.top || e.clientY > r.bottom) film.close();
    });
    film.addEventListener("close", function () { if (video) video.pause(); });

    // Captions: the film draws its own words all over the picture, so the cue is shown on the strip under it, never on the
    // picture (the css hides the picture's own cue). The strip follows the track the viewer's captions control picks: it
    // shows the active cue while the track is on and nothing when the viewer turns captions off.
    var cue = film.querySelector(".fr-cue");
    var track = video && video.textTracks && video.textTracks.length ? video.textTracks[0] : null;
    if (cue && track) {
      var showCue = function () {
        var on = track.mode !== "disabled" && track.activeCues && track.activeCues.length;
        cue.textContent = on ? Array.prototype.map.call(track.activeCues, function (c) { return c.text; }).join(" ") : "";
      };
      track.addEventListener("cuechange", showCue);
      video.textTracks.addEventListener("change", showCue);
      video.addEventListener("seeked", showCue);
      film.addEventListener("close", function () { cue.textContent = ""; });
    }
  }

  // Life: a band, the first screen and each drawing get pg-on while they are on screen, and the page's css runs their motion
  // only then (a dot on the gutter's cable, a dot on a drawing's cable, a lamp, a fan). pg-away on <html> pauses all of
  // it while the tab is hidden. Nothing here runs under reduced motion: the css stops the motion there.
  var still = window.matchMedia ? window.matchMedia("(prefers-reduced-motion: reduce)") : { matches: false, addEventListener: null };
  if ("IntersectionObserver" in window) {
    // pg-life tells the css the on-screen marks are kept, so what loops off screen (a drawing, a working lamp's beat) is paused
    root.classList.add("pg-life");
    var lifeIO = new IntersectionObserver(function (entries) {
      entries.forEach(function (en) { en.target.classList.toggle("pg-on", en.isIntersecting); });
    }, { rootMargin: "80px 0px 80px 0px" });
    document.querySelectorAll("main > .fr-first, main > .pg-band, .pg-art").forEach(function (el) { lifeIO.observe(el); });
  }
  document.addEventListener("visibilitychange", function () { root.classList.toggle("pg-away", document.hidden); });

  // The film's preview rests on its title card, like a video's thumbnail. While a mouse is over it, it plays its own frames muted; when
  // the mouse leaves, or the film opens, it stops and goes back to the title card. Under reduced motion it never moves.
  var pv = document.querySelector(".fr-pv");
  var pvFilm = pv && pv.closest("[data-film]");
  if (pvFilm && !still.matches) {
    var rest = function () { pv.pause(); try { pv.currentTime = 7; } catch (_) { /* not loaded yet: it is still on its title card */ } };
    pvFilm.addEventListener("pointerenter", function (e) { if (e.pointerType !== "mouse") return; var p = pv.play(); if (p && p.catch) p.catch(rest); });
    pvFilm.addEventListener("pointerleave", rest);
    pvFilm.addEventListener("click", rest);
  }
})();
