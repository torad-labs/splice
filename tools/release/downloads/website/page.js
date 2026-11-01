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

  // The first screen's stage: when it comes into view, claudex hands a turn to the Grok plan and claude-splice hands one to the
  // Muse plan. The command that hands the turn on and the plan that takes it light up, the turn's wire runs its dashes while the
  // turn travels it, the plan's meter fills a little, and everything settles back. It plays once per view: leaving the stage and
  // coming back plays it again, and nothing runs while the stage rests. Under reduced motion the stage rests as drawn.
  var win = document.querySelector(".fr-stage .fr-win");
  if (win && "IntersectionObserver" in window) {
    var rail = win.querySelectorAll(".fr-rail > .card.h");
    var plans = win.querySelectorAll(".fr-prov .fr-grp:first-child .fr-plan");
    var timers = [];
    var run = 0;
    var inView = false;
    var ratio = 0;
    var pkt = null;
    var undo = [];
    var turns = [
      { cmd: 1, plan: 1, to: 2, toCmd: 2, color: "var(--gpt)", pct: [31, 33], from: [55, 56] },
      { cmd: 0, plan: 0, to: 4, toCmd: 4, color: "var(--claude)", pct: null, from: null }
    ];

    var after = function (id, fn, ms) { timers.push(setTimeout(function () { if (id === run) fn(); }, ms)); };
    var stacked = function () { return getComputedStyle(win).gridTemplateColumns.split(" ").length < 3; };
    var on = function (card, yes) { if (card) card.classList.toggle("fr-on", !!yes); };
    var hot = function (card, color, yes) {
      if (!card) return;
      card.classList.toggle("fr-hot", !!yes);
      if (yes) card.style.setProperty("--fr-wc", color); else card.style.removeProperty("--fr-wc");
    };
    var meter = function (card, from, to) {
      var fill = card.querySelector(".windows .bar.grow > b");
      var p = card.querySelector(".windows .pct");
      var txt = p && p.firstChild && p.firstChild.nodeType === 3 ? p.firstChild : null;
      if (fill) fill.style.transform = "scaleX(" + to / from + ")";
      if (txt) txt.nodeValue = Math.round(to) + "%";
    };

    // the packet: a small tile that rides the connector from the command's wire to the plan's wire, inside the connector bar
    var travel = function (fromCard, toCard, color, done) {
      var bar = win.querySelector(".fr-bar");
      if (stacked() || !win.animate || !bar) { done(); return; }
      if (!pkt) {
        pkt = document.createElement("span");
        pkt.className = "fr-pkt";
        pkt.setAttribute("aria-hidden", "true");
        pkt.innerHTML = '<svg viewBox="0 0 16 16" fill="none" stroke="currentColor" stroke-width="2.2" stroke-linecap="round" stroke-linejoin="round"><path d="M3 8h10M9 4l4 4-4 4"/></svg>';
        bar.appendChild(pkt);
      }
      pkt.style.setProperty("--c", color);
      var br = bar.getBoundingClientRect();
      var wr = win.getBoundingClientRect();
      var z = (win.offsetWidth ? wr.width / win.offsetWidth : 1) || 1;
      var rem = parseFloat(getComputedStyle(root).fontSize) || 16;
      var half = (pkt.offsetWidth / 2) * z, cast = 0.5 * rem * z;
      var a = fromCard.getBoundingClientRect(), b = toCard.getBoundingClientRect();
      var X = function (v) { return (v - br.left) / z; }, Y = function (v) { return (v - br.top) / z; };
      var x0 = X(a.right + cast + half + 16), y0 = Y(a.top + a.height / 2);
      var xm = X(br.left + br.width / 2);
      var y1 = Y(b.top + b.height / 2), x1 = X(b.left - half - 16);
      var l1 = Math.max(1, xm - x0), l2 = Math.max(1, Math.abs(y1 - y0)), l3 = Math.max(1, x1 - xm);
      var tot = l1 + l2 + l3;
      var at = function (x, y, s, o, off) { return { transform: "translate(" + x + "px," + y + "px) scale(" + s + ")", opacity: o, offset: off }; };
      var anim = pkt.animate([
        at(x0, y0, 0.6, 0, 0), at(x0, y0, 1, 1, 0.06),
        at(xm, y0, 1, 1, 0.06 + 0.88 * l1 / tot), at(xm, y1, 1, 1, 0.06 + 0.88 * (l1 + l2) / tot),
        at(x1, y1, 1, 1, 0.94), at(x1, y1, 0.6, 0, 1)
      ], { duration: 3200, easing: "cubic-bezier(.65,0,.35,1)", fill: "none" });
      anim.onfinish = done;
      anim.oncancel = done;
    };

    var settle = function () {
      run++;
      timers.forEach(clearTimeout);
      timers = [];
      if (pkt && pkt.getAnimations) pkt.getAnimations().forEach(function (an) { an.onfinish = null; an.oncancel = null; an.cancel(); });
      Array.prototype.forEach.call(win.querySelectorAll(".fr-on, .fr-hot"), function (c) { c.classList.remove("fr-on", "fr-hot"); c.style.removeProperty("--fr-wc"); });
      undo.forEach(function (fn) { fn(); });
      undo = [];
    };

    var play = function () {
      var id = ++run;
      var n = 0;
      var next = function () {
        var t = turns[n++];
        if (!t || id !== run) return;
        var cmd = rail[t.cmd], src = plans[t.plan], dst = plans[t.to], dstCmd = rail[t.toCmd];
        if (!cmd || !src || !dst) return;
        on(cmd, true); on(src, true);
        if (t.from) { meter(src, t.from[0], t.from[1]); undo.push(function () { meter(src, t.from[1], t.from[0]); }); }
        after(id, function () {
          hot(cmd, t.color, true); hot(dst, t.color, true);
          travel(cmd, dst, t.color, function () {
            if (id !== run) return;
            hot(cmd, t.color, false); hot(dst, t.color, false);
            on(src, false); on(cmd, false); on(dst, true); on(dstCmd, true);
            if (t.pct) { meter(dst, t.pct[0], t.pct[1]); undo.push(function () { meter(dst, t.pct[1], t.pct[0]); }); }
            after(id, function () {
              on(dst, false); on(dstCmd, false);
              if (t.pct) meter(dst, t.pct[1], t.pct[0]);
              if (t.from) meter(src, t.from[1], t.from[0]);
              undo = [];
              after(id, next, 700);
            }, 2600);
          });
        }, 900);
      };
      next();
    };

    var enter = function () {
      if (inView || still.matches || document.hidden) return;
      inView = true;
      var id = run;
      timers.push(setTimeout(function () { if (inView && id === run) play(); }, 700));
    };
    var leave = function () { inView = false; settle(); };
    new IntersectionObserver(function (entries) {
      entries.forEach(function (en) {
        ratio = en.intersectionRatio;
        if (ratio >= 0.15) enter(); else leave();
      });
    }, { threshold: [0, 0.15] }).observe(win);
    document.addEventListener("visibilitychange", function () { if (document.hidden) leave(); else if (ratio >= 0.15) enter(); });
    if (still.addEventListener) still.addEventListener("change", function () { if (still.matches) leave(); });
  }
})();
