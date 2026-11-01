// team band: the team works once. The markup at rest is the end of it (the lead waiting, the other three idle, the three
// messages in, 18 of 18 tests). When the band first scrolls into view the page starts it from the beginning: the lead
// works on the prompt with no messages and no tests, the builder and tester start, each message arrives while its
// sender's lamp is working, the tests count to 11 and then 18, and the seats settle. Without scripts, motion or an
// observer, the page keeps the end.
(function () {
  "use strict";
  var live = document.querySelector(".tm-live");
  if (!live) return;
  if (!document.documentElement.classList.contains("pg-js") || live.classList.contains("is-seen")) return;
  if (!window.MutationObserver || (window.matchMedia && window.matchMedia("(prefers-reduced-motion: reduce)").matches)) return;

  var seats = live.querySelectorAll(".tm-seats > .card.m");
  var chat = live.querySelector(".tm-chat");
  var grid = live.querySelector(".tm-grid");
  var talk = live.querySelector(".tm-talk");
  var count = live.querySelector(".tm-count");
  var tbar = live.querySelector(".tm-tbar");
  var seg = tbar ? tbar.querySelector(".pd-seg") : null;
  var promptChip = live.querySelector(".tm-prompt > .chip");
  if (seats.length !== 4 || !chat || !grid || !talk || !count || !seg || !promptChip) return;
  var bubbles = Array.prototype.slice.call(chat.children).filter(function (el) { return el.classList.contains("tm-bub"); });
  if (bubbles.length !== 3) return;

  var lead = seats[0];
  var builder = seats[1];
  var tester = seats[2];
  var reviewer = seats[3];
  var TOTAL = 18;
  var WAVE = '<svg class="wave" viewBox="0 0 40 40" aria-hidden="true"><polyline class="base" points="5,20 13,20 16,12 20,28 24,15 27,20 35,20"></polyline><polyline class="beat" points="5,20 13,20 16,12 20,28 24,15 27,20 35,20"></polyline></svg>';

  // a class that plays an animation once is dropped when it ends, so the markup returns to what it was at rest
  function once(el, cls) {
    el.classList.add(cls);
    el.addEventListener("animationend", function (e) {
      if (e.target === el) el.classList.remove(cls);
    });
  }

  // the dot a chip wears while its session works
  function dot(chip, on) {
    var has = chip.firstElementChild && chip.firstElementChild.tagName === "I";
    if (on && !has) {
      var i = document.createElement("i");
      i.setAttribute("aria-hidden", "true");
      chip.insertBefore(i, chip.firstChild);
    } else if (!on && has) chip.firstElementChild.remove();
  }

  // a seat: "working" with its age, "idle", or "wait" (the lead's neutral idle). A change of kind fades the new lamp over the old.
  function seat(card, kind, age) {
    var working = kind === "working";
    var changed = card.getAttribute("data-tm-kind") !== kind;
    card.setAttribute("data-tm-kind", kind);
    card.classList.toggle("working", working);
    card.classList.toggle("idle", !working);
    var top = card.querySelector(":scope > .top");
    var word = top ? top.querySelector(".word") : null;
    if (!top || !word) return;
    if (changed) {
      var old = card.querySelector(":scope > .lamp");
      var lamp = document.createElement("span");
      lamp.className = "lamp " + (working ? "working" : kind === "wait" ? "idle tm-wait" : "idle");
      lamp.setAttribute("aria-hidden", "true");
      lamp.innerHTML = working ? WAVE : "<i></i>";
      card.insertBefore(lamp, card.firstChild);
      once(lamp, "tm-fadein");
      if (old) {
        old.classList.add("tm-fadeout");
        setTimeout(function () { old.remove(); }, 300);
      }
      word.className = "word " + (working ? "working" : kind === "wait" ? "tm-waiting" : "idle");
      word.textContent = working ? "Working" : kind === "wait" ? "Waiting" : "Idle";
      once(word, "tm-fadein");
      var chip = card.querySelector(":scope > .meta > .chip");
      if (chip) dot(chip, working);
    }
    var detail = top.querySelector(".detail.age");
    if (working) {
      if (!detail) {
        detail = document.createElement("span");
        detail.className = "detail age";
        top.appendChild(detail);
      }
      detail.textContent = age || "";
    } else if (detail) detail.remove();
  }

  function tests(n) {
    count.style.setProperty("--tm-n", String(n));
    seg.style.width = (n / TOTAL * 100).toFixed(2) + "%";
    tbar.setAttribute("aria-label", n + " of " + TOTAL + " pass");
  }

  var ghost = document.createElement("div");
  ghost.className = "tm tm-bub tm-ghost";
  ghost.innerHTML = '<p class="empty">No messages yet</p>';

  // message i arrives at the foot of the thread, and is the latest
  function arrive(i) {
    if (ghost.parentNode) ghost.remove();
    bubbles.forEach(function (b) { b.classList.remove("latest"); });
    var b = bubbles[i];
    b.classList.add("latest");
    chat.appendChild(b);
    once(b, "tm-arriving");
  }

  function begin() {
    // the stage keeps the height it ends with (the messages' own where the page stacks them), so nothing below moves while it plays
    grid.style.setProperty("--tm-grid-h", grid.offsetHeight + "px");
    talk.style.setProperty("--tm-talk-h", talk.offsetHeight + "px");
    bubbles.forEach(function (b) { b.remove(); });
    chat.appendChild(ghost);
    dot(promptChip, true);
    // the seats as the markup draws them at rest
    lead.setAttribute("data-tm-kind", "wait");
    [builder, tester, reviewer].forEach(function (c) { c.setAttribute("data-tm-kind", "idle"); });
    seat(lead, "working", "0:08");
    tests(0);
  }

  var steps = [
    // the builder and the tester start; the lead's message reaches the builder
    [1500, function () {
      seat(lead, "working", "0:19");
      seat(builder, "working", "0:02");
      seat(tester, "working", "0:04");
      arrive(0);
    }],
    // the reviewer's lamp is working while its message arrives; 11 of 18 pass
    [2900, function () {
      seat(lead, "working", "0:41");
      seat(builder, "working", "0:09");
      seat(tester, "working", "0:23");
      seat(reviewer, "working", "0:03");
      arrive(1);
      tests(11);
    }],
    // the builder's message reaches the lead; all 18 pass
    [4300, function () {
      seat(lead, "working", "1:02");
      seat(builder, "working", "0:31");
      seat(tester, "working", "0:45");
      seat(reviewer, "idle");
      arrive(2);
      tests(TOTAL);
    }],
    // the end: the lead waits, the other three are idle
    [5400, function () {
      seat(lead, "wait");
      seat(builder, "idle");
      seat(tester, "idle");
      seat(reviewer, "idle");
      dot(promptChip, false);
      setTimeout(function () {
        grid.style.removeProperty("--tm-grid-h");
        talk.style.removeProperty("--tm-talk-h");
        [lead, builder, tester, reviewer].forEach(function (c) { c.removeAttribute("data-tm-kind"); });
      }, 600);
    }]
  ];

  // the callback runs before the frame that first shows the band, so the beginning is what the reveal draws
  var watcher = new MutationObserver(function () {
    if (!live.classList.contains("is-seen")) return;
    watcher.disconnect();
    begin();
    steps.forEach(function (s) { setTimeout(s[1], s[0]); });
  });
  watcher.observe(live, { attributes: true, attributeFilter: ["class"] });
})();
