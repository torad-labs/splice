// THE CONSOLE'S HANDS FOR AN AGENT (Oct 16, 2026; Marcos: "Everything that the user can do the agents should be able
// to do it too"). `splice console <action>` hands the daemon one order, the daemon hands it to the tab the person
// looked at last (ConsolePilot), and this script does it here the way a person would: it looks, reads, taps, types,
// picks, presses keys, hovers, scrolls, moves between pages, and points with a ring the person can see. Only a tab the
// person can see asks for orders. The composer is the agent's own door, so it is never listed, read or driven.
"use strict";

const PILOT = (() => {
  const NAME = crypto.randomUUID ? crypto.randomUUID() : `${Date.now().toString(36)}-${Math.random().toString(36).slice(2)}`;
  const CONTROLS = [
    "a[href]", "button", "input:not([type=hidden])", "select", "textarea", "summary", "[contenteditable=true]",
    "[role=button]", "[role=link]", "[role=tab]", "[role=switch]", "[role=checkbox]", "[role=radio]", "[role=menuitem]",
    "[role=option]", "[role=slider]", "[role=combobox]", "[data-act]", "[tabindex]:not([tabindex='-1'])",
  ].join(",");
  const OWN = ".cmp, .pilot-ring, .pilot-note";
  const ROW = "tr, li, [role=row], [role=listitem], article";
  const HEADING = "h1, h2, h3, h4, h5, h6, legend, caption, [role=heading]";
  const SECRET = /key|token|secret|passw|credential/i;
  const READ_CAP = 12000;
  const refs = new Map();
  let counter = 0;

  const squash = (s) => String(s ?? "").replace(/\s+/g, " ").trim();
  const clip = (s, n) => (s.length > n ? `${s.slice(0, n - 1)}…` : s);
  const sleep = (ms) => new Promise((resolve) => setTimeout(resolve, ms));
  const frames = () => new Promise((resolve) => requestAnimationFrame(() => requestAnimationFrame(resolve)));
  const refuse = (sentence) => new Error(sentence);

  // ---- where we are -------------------------------------------------------------------------------------------
  function here() {
    const file = PAGES.find(([f]) => location.pathname.endsWith(`/${f}`))?.[0] || "accounts.html";
    return { file, name: PAGES.find(([f]) => f === file)?.[1] || file };
  }
  const mainNode = () => document.querySelector("main.page");
  function modal() {
    try { return [...document.querySelectorAll("dialog")].find((d) => d.matches(":modal")) || null; } catch { return null; }
  }
  const scope = () => modal() || document;
  const composerOpen = () => Boolean(document.querySelector(".cmp .cmp-sheet")?.getClientRects().length);
  // The person may be typing to the agent in the composer: an agent's act never pulls the focus out of it.
  const typing = () => Boolean(document.activeElement?.closest?.(".cmp"));
  const take = (el) => { if (!typing()) el.focus?.({ preventScroll: true }); };

  // ---- what a person sees -------------------------------------------------------------------------------------
  function shown(el) {
    if (!el?.isConnected || el.closest(OWN) || el.closest("[inert]")) return false;
    if (el.checkVisibility) return el.checkVisibility({ visibilityProperty: true });
    return el.getClientRects().length > 0 && getComputedStyle(el).visibility !== "hidden";
  }
  const controls = () => [...scope().querySelectorAll(CONTROLS)].filter(shown);

  function nameOf(el) {
    const by = el.getAttribute("aria-labelledby");
    const labelled = by ? by.split(/\s+/).map((id) => document.getElementById(id)?.innerText).join(" ") : "";
    const labels = el.labels?.length ? [...el.labels].map((l) => l.innerText).join(" ") : "";
    const field = el.matches("input, textarea, select");
    const name = squash(el.getAttribute("aria-label")) || squash(labelled) || squash(labels)
      || (field ? "" : squash(el.innerText))
      || squash(el.getAttribute("title")) || squash(el.getAttribute("placeholder")) || squash(el.getAttribute("alt"))
      || (el.matches("input[type=button], input[type=submit], input[type=reset]") ? squash(el.value) : "")
      || squash(el.dataset.act) || squash(el.getAttribute("name"));
    return clip(name, 80);
  }
  function roleOf(el) {
    const role = el.getAttribute("role");
    if (role) return role;
    const tag = el.tagName.toLowerCase(), type = (el.getAttribute("type") || "text").toLowerCase();
    if (tag === "a") return "link";
    if (tag === "button" || (tag === "input" && ["button", "submit", "reset"].includes(type))) return "button";
    if (tag === "input" && ["checkbox", "radio"].includes(type)) return type;
    if (tag === "input" && type === "range") return "slider";
    if (tag === "select") return "picker";
    if (tag === "summary") return "disclosure";
    if (tag === "input" || tag === "textarea" || el.isContentEditable) return "field";
    return "control";
  }
  // The heading a person reads the control under: the last one before it in the nearest box that has one.
  function placeOf(el) {
    for (let box = el.parentElement; box && box !== document.body; box = box.parentElement) {
      let last = null;
      for (const h of box.querySelectorAll(HEADING)) {
        if (h.contains(el) || !shown(h)) continue;
        if (h.compareDocumentPosition(el) & Node.DOCUMENT_POSITION_FOLLOWING) last = h;
      }
      if (last) return clip(squash(last.innerText), 60);
    }
    return el.closest(".side, nav") ? "Navigation" : "";
  }
  // A row's first words that are not the control's own, so three "Remove" buttons read as three different rows.
  function rowOf(el) {
    const row = el.parentElement?.closest(ROW);
    if (!row || row.closest(OWN)) return "";
    const own = squash(el.innerText);
    const line = row.innerText.split("\n").map(squash).find((l) => l && !own.includes(l));
    return clip(line || "", 50);
  }
  const secret = (el) => el.type === "password" || SECRET.test(`${el.name} ${el.id} ${el.autocomplete} ${nameOf(el)}`);
  function stateOf(el) {
    const state = [];
    if (el.disabled || el.getAttribute("aria-disabled") === "true") state.push("disabled");
    const checked = el.matches("input[type=checkbox], input[type=radio]") ? String(el.checked) : el.getAttribute("aria-checked");
    if (checked === "true") state.push("on"); else if (checked === "false") state.push("off");
    if (el.getAttribute("aria-pressed") === "true") state.push("pressed");
    const expanded = el.getAttribute("aria-expanded");
    if (expanded) state.push(expanded === "true" ? "open" : "closed");
    if (el.getAttribute("aria-selected") === "true" || el.hasAttribute("aria-current")) state.push("current");
    if (el.matches("select")) state.push(`value "${squash(el.selectedOptions[0]?.text)}"`);
    else if (el.matches("input:not([type=checkbox]):not([type=radio]), textarea")) {
      state.push(secret(el) ? (el.value ? "holds a value it does not show" : "empty") : `value "${clip(el.value, 60)}"`);
    }
    if (el === document.activeElement) state.push("focused");
    const b = el.getBoundingClientRect();
    if (b.bottom < 0 || b.top > innerHeight) state.push("off screen");
    return state;
  }
  const describe = (el) => ({ role: roleOf(el), name: nameOf(el), place: placeOf(el), row: rowOf(el), state: stateOf(el) });
  const keyOf = (d) => `${d.role}\u0000${d.name}\u0000${d.place}\u0000${d.row}`;
  const label = (d) => `${d.role} "${d.name}"${d.place ? ` in ${d.place}` : ""}${d.row ? `, row "${d.row}"` : ""}`;
  const line = (ref, d) => `${ref} ${d.role} "${d.name}"${d.row ? ` row "${d.row}"` : ""}${d.state.length ? ` (${d.state.join(", ")})` : ""}`;

  // ---- refs: a name for a thing on this page that survives the page redrawing it ----------------------------
  function remember(el, d, again) {
    const ref = `e${++counter}`;
    refs.set(ref, { el: new WeakRef(el), file: here().file, page: here().name, label: label(d), again });
    return ref;
  }
  const controlAgain = (key, nth) => () => {
    const hits = controls().filter((c) => keyOf(describe(c)) === key);
    return hits[nth] || (hits.length === 1 ? hits[0] : null);
  };
  function byRef(word) {
    const ref = word.replace(/^@/, ""), kept = refs.get(ref);
    if (!kept) throw refuse(`${ref} is not from the last snapshot. Run \`splice console snapshot\` and use a ref from it.`);
    if (kept.file !== here().file) throw refuse(`${ref} was on ${kept.page}, and this is ${here().name}. Run \`splice console snapshot\` again.`);
    const el = kept.el.deref();
    if (shown(el)) return el;
    const again = kept.again?.();
    if (again) { kept.el = new WeakRef(again); return again; }
    throw refuse(`${ref} (${kept.label}) is no longer showing. Run \`splice console snapshot\` again.`);
  }
  function textHits(want) {
    const hits = [], walker = document.createTreeWalker(scope() === document ? document.body : scope(), NodeFilter.SHOW_TEXT);
    for (let node = walker.nextNode(); node; node = walker.nextNode()) {
      const parent = node.parentElement;
      if (!parent || hits.includes(parent) || !squash(node.data).toLowerCase().includes(want)) continue;
      if (!parent.matches("script, style") && shown(parent)) hits.push(parent);
    }
    return hits;
  }
  function ambiguous(target, hits) {
    const shown12 = hits.slice(0, 12).map((h) => `  ${remember(h.el, h.d, h.again)} ${label(h.d)}`);
    const more = hits.length > 12 ? `\n  and ${hits.length - 12} more` : "";
    return refuse(`"${target}" fits ${hits.length} things on this page. Use one of these refs:\n${shown12.join("\n")}${more}`);
  }
  // A target is a ref (e12), a CSS selector (css:…), or the words a person reads on it: a control's name first,
  // then any text showing on the page.
  function find(target) {
    const t = squash(target);
    if (/^@?e\d+$/.test(t)) return { el: byRef(t), ref: t.replace(/^@/, "") };
    if (t.startsWith("css:")) {
      let all;
      try { all = [...scope().querySelectorAll(t.slice(4))].filter(shown); } catch { throw refuse(`"${t.slice(4)}" is not a CSS selector.`); }
      if (!all.length) throw refuse(`Nothing showing matches ${t}.`);
      const sel = t.slice(4), nth = 0, d = describe(all[0]);
      return { el: all[0], ref: remember(all[0], d, () => [...scope().querySelectorAll(sel)].filter(shown)[nth] || null) };
    }
    const want = t.toLowerCase();
    const all = controls().map((el) => ({ el, d: describe(el) }));
    let hits = all.filter(({ d }) => d.name.toLowerCase() === want);
    if (!hits.length) hits = all.filter(({ d }) => d.name.toLowerCase().includes(want));
    const counts = new Map();
    hits = hits.map((h) => {
      const key = keyOf(h.d), nth = counts.get(key) || 0;
      counts.set(key, nth + 1);
      return { ...h, again: controlAgain(key, nth) };
    });
    if (!hits.length) {
      hits = textHits(want).map((el, nth) => ({ el, d: describe(el), again: () => textHits(want)[nth] || null }));
    }
    if (hits.length === 1) return { el: hits[0].el, ref: remember(hits[0].el, hits[0].d, hits[0].again) };
    if (hits.length > 1) throw ambiguous(target, hits);
    throw refuse(`Nothing showing on ${here().name} is called "${target}". Run \`splice console snapshot\` to see what is.`);
  }

  // ---- the clear part of the screen, and bringing a thing into it ---------------------------------------------
  let barHeight = 0;
  function band(rect) {
    if (!barHeight) {
      const probe = document.createElement("div");
      probe.style.cssText = "position:absolute;visibility:hidden;height:var(--bar-h)";
      document.body.append(probe);
      barHeight = probe.offsetHeight;
      probe.remove();
    }
    const cmp = document.querySelector(".cmp")?.getBoundingClientRect();
    const under = cmp && cmp.height && (!rect || (rect.right > cmp.left && rect.left < cmp.right));
    return { top: barHeight + 8, bottom: under ? cmp.top - 12 : innerHeight - 8 };
  }
  // Room under the page, so a thing near its foot can still be brought above the composer's open sheet.
  function room(extra) {
    const main = mainNode();
    if (!main) return;
    const now = parseFloat(getComputedStyle(main, "::after").height) || 0;
    document.documentElement.style.setProperty("--pilot-room", `${Math.ceil(now + extra)}px`);
    document.body.classList.add("pilot-room");
  }
  function reveal(el) {
    el.scrollIntoView?.({ block: "nearest", inline: "nearest", behavior: "instant" });
    const b = el.getBoundingClientRect(), clear = band(b);
    if (b.top >= clear.top && b.bottom <= clear.bottom) return;
    const want = clear.top + Math.max(0, (clear.bottom - clear.top - b.height) / 2);
    const by = b.top - want, most = document.documentElement.scrollHeight - innerHeight - scrollY;
    if (by > most) room(by - most);
    window.scrollBy({ top: by, left: 0, behavior: "instant" });
  }
  function covered(el) {
    const b = el.getBoundingClientRect(), clear = band(b);
    return b.bottom > clear.bottom + 12 || b.top < clear.top - 8;
  }

  // ---- marks the person sees -----------------------------------------------------------------------------------
  const rings = [];
  let tracking = 0;
  function frame(box, el) {
    const b = el.getBoundingClientRect(), pad = 4;
    Object.assign(box.style, { left: `${b.left - pad}px`, top: `${b.top - pad}px`, width: `${b.width + pad * 2}px`, height: `${b.height + pad * 2}px` });
    return b;
  }
  function flash(el) {
    const box = document.createElement("div");
    box.className = "pilot-ring flash";
    box.setAttribute("aria-hidden", "true");
    document.body.append(box);
    frame(box, el);
    setTimeout(() => box.remove(), 900);
  }
  function drop(mark) {
    mark.box.remove();
    mark.tag?.remove();
    rings.splice(rings.indexOf(mark), 1);
  }
  function clearRings() { [...rings].forEach(drop); }
  function follow(mark, now) {
    if (mark.file !== here().file) return drop(mark);
    if (!shown(mark.el)) {
      if (now - mark.tried > 250) { mark.tried = now; mark.el = refs.get(mark.ref)?.again?.() || mark.el; }
      const lost = !shown(mark.el);
      mark.box.hidden = lost;
      if (mark.tag) mark.tag.hidden = lost;
      if (!lost) mark.lostAt = 0; else if (!mark.lostAt) mark.lostAt = now; else if (now - mark.lostAt > 3000) drop(mark);
      if (lost) return;
    }
    const b = frame(mark.box, mark.el);
    if (!mark.tag) return;
    const clear = band(b), w = mark.tag.offsetWidth, h = mark.tag.offsetHeight;
    mark.tag.style.left = `${Math.max(8, Math.min(b.left, innerWidth - w - 8))}px`;
    mark.tag.style.top = `${b.bottom + 10 + h <= clear.bottom ? b.bottom + 10 : Math.max(clear.top, b.top - h - 10)}px`;
  }
  function track() {
    if (tracking) return;
    const step = (now) => {
      tracking = 0;
      [...rings].forEach((mark) => follow(mark, now));
      if (rings.length) tracking = requestAnimationFrame(step);
    };
    tracking = requestAnimationFrame(step);
  }
  function ring(el, ref, note) {
    const box = document.createElement("div");
    box.className = "pilot-ring";
    box.setAttribute("aria-hidden", "true");
    document.body.append(box);
    let tag = null;
    if (note) {
      tag = document.createElement("div");
      tag.className = "pilot-note";
      tag.setAttribute("role", "status");
      tag.textContent = note;
      document.body.append(tag);
    }
    rings.push({ el, ref, box, tag, file: here().file, tried: 0, lostAt: 0 });
    track();
  }
  // The person's own press anywhere but the composer, or their Escape, puts the rings away.
  addEventListener("pointerdown", (e) => { if (e.isTrusted && rings.length && !e.target.closest?.(".cmp")) clearRings(); }, true);
  addEventListener("keydown", (e) => { if (e.isTrusted && e.key === "Escape" && rings.length) clearRings(); }, true);

  // ---- doing things --------------------------------------------------------------------------------------------
  function point(el) {
    const b = el.getBoundingClientRect();
    return { bubbles: true, cancelable: true, composed: true, view: window, clientX: b.left + b.width / 2, clientY: b.top + b.height / 2, button: 0 };
  }
  // Notices only: a live region can be a whole board (Accounts' is), which is read with `read`, not reported.
  const alerts = () => [...document.querySelectorAll("[role=alert], [role=status]")]
    .filter((el) => shown(el)).map((el) => clip(squash(el.innerText), 200)).filter(Boolean);
  async function swapped(before, ms) {
    const until = Date.now() + ms;
    while (mainNode() === before && Date.now() < until) await sleep(50);
    return mainNode() !== before;
  }
  // What changed after an act, in the person's words: the page moved, the control now reads otherwise, a notice.
  async function after(el, d, before, saidBefore) {
    await frames();
    await sleep(150);
    const notes = [];
    if (mainNode() !== before.main || here().file !== before.file) notes.push(`Now on ${here().name}.`);
    else if (shown(el)) {
      const now = describe(el), lasting = (state) => state.filter((s) => s !== "focused" && s !== "off screen");
      if (now.name !== d.name) notes.push(`It now reads "${now.name}".`);
      if (lasting(now.state).join() !== lasting(d.state).join()) notes.push(`It is now ${lasting(now.state).join(", ") || "plain"}.`);
    }
    const fresh = alerts().filter((a) => !saidBefore.includes(a));
    if (fresh.length) notes.push(`The page says: ${fresh.join(" / ")}`);
    if (modal() && !before.modal) notes.push("A dialog opened. Run `splice console snapshot` to see it.");
    return notes.join(" ");
  }
  const moment = () => ({ main: mainNode(), file: here().file, modal: modal() });

  async function tap([target]) {
    const { el } = find(target), d = describe(el);
    if (d.state.includes("disabled")) throw refuse(`${label(d)} is disabled, so a tap does nothing.`);
    const before = moment(), said = alerts();
    reveal(el);
    flash(el);
    const at = point(el), touch = { pointerType: "mouse", isPrimary: true, pointerId: 1 };
    el.dispatchEvent(new PointerEvent("pointerdown", { ...at, ...touch, buttons: 1 }));
    el.dispatchEvent(new MouseEvent("mousedown", { ...at, buttons: 1 }));
    take(el);
    el.dispatchEvent(new PointerEvent("pointerup", { ...at, ...touch }));
    el.dispatchEvent(new MouseEvent("mouseup", at));
    if (typeof el.click === "function") el.click(); else el.dispatchEvent(new MouseEvent("click", at));
    const link = el.closest("a[href]"), to = link && new URL(link.href, location.href);
    if (to && to.origin === location.origin && to.pathname !== location.pathname && !link.target) await swapped(before.main, 8000);
    return { text: `Tapped ${label(d)}. ${await after(el, d, before, said)}`.trim() };
  }
  async function fill([target, text]) {
    const { el } = find(target), d = describe(el);
    if (!el.matches("input, textarea") && !el.isContentEditable) throw refuse(`${label(d)} is not a field you type into. Tap it, or use select for a picker.`);
    if (el.matches("input[type=checkbox], input[type=radio]")) throw refuse(`${label(d)} is a ${d.role}: tap it to turn it on or off.`);
    if (d.state.includes("disabled") || el.readOnly) throw refuse(`${label(d)} does not take typing right now.`);
    const before = moment(), said = alerts();
    reveal(el);
    flash(el);
    take(el);
    if (el.isContentEditable) el.textContent = text; else el.value = text;
    el.dispatchEvent(new InputEvent("input", { bubbles: true, inputType: "insertText", data: text }));
    el.dispatchEvent(new Event("change", { bubbles: true }));
    const shownText = secret(el) ? "the text" : `"${clip(text, 80)}"`;
    return { text: `Typed ${shownText} into ${label(d)}. ${await after(el, d, before, said)}`.trim() };
  }
  async function select([target, option]) {
    const { el } = find(target), d = describe(el), want = squash(option).toLowerCase();
    const before = moment(), said = alerts();
    reveal(el);
    flash(el);
    if (el.matches("select")) {
      const options = [...el.options];
      const pick = options.find((o) => squash(o.text).toLowerCase() === want || o.value === option)
        || options.find((o) => squash(o.text).toLowerCase().includes(want));
      if (!pick) throw refuse(`${label(d)} has no "${option}". It offers: ${options.map((o) => `"${squash(o.text)}"`).join(", ")}.`);
      take(el);
      el.value = pick.value;
      el.dispatchEvent(new Event("input", { bubbles: true }));
      el.dispatchEvent(new Event("change", { bubbles: true }));
      return { text: `Picked "${squash(pick.text)}" in ${label(d)}. ${await after(el, d, before, said)}`.trim() };
    }
    // A picker drawn as buttons or options: the person taps the choice inside it.
    const choice = [...el.querySelectorAll(CONTROLS)].filter(shown).find((c) => nameOf(c).toLowerCase() === want)
      || [...el.querySelectorAll(CONTROLS)].filter(shown).find((c) => nameOf(c).toLowerCase().includes(want));
    if (!choice) throw refuse(`${label(d)} has no choice called "${option}".`);
    const chosen = remember(choice, describe(choice), null);
    return tap([chosen]);
  }

  const KEYS = {
    esc: "Escape", escape: "Escape", enter: "Enter", return: "Enter", tab: "Tab", space: " ", spacebar: " ",
    up: "ArrowUp", down: "ArrowDown", left: "ArrowLeft", right: "ArrowRight", arrowup: "ArrowUp", arrowdown: "ArrowDown",
    arrowleft: "ArrowLeft", arrowright: "ArrowRight", backspace: "Backspace", delete: "Delete", del: "Delete",
    home: "Home", end: "End", pageup: "PageUp", pagedown: "PageDown",
  };
  function keyFrom(spec) {
    const parts = spec.split("+"), main = parts.pop() || "+", mods = new Set(parts.map((p) => p.toLowerCase()));
    const key = KEYS[main.toLowerCase()] || (main.length === 1 ? main : main[0].toUpperCase() + main.slice(1));
    const code = key === " " ? "Space" : /^[a-z]$/i.test(key) ? `Key${key.toUpperCase()}` : /^\d$/.test(key) ? `Digit${key}` : key;
    return {
      key, code, ctrlKey: mods.has("ctrl") || mods.has("control"), shiftKey: mods.has("shift"),
      altKey: mods.has("alt") || mods.has("option"), metaKey: mods.has("meta") || mods.has("cmd") || mods.has("command"),
    };
  }
  const FOCUSABLE = "a[href], button:not([disabled]), input:not([disabled]):not([type=hidden]), select:not([disabled]), textarea:not([disabled]), summary, [tabindex]:not([tabindex='-1'])";
  function moveFocus(from, back) {
    const all = [...scope().querySelectorAll(FOCUSABLE)].filter(shown);
    const at = all.indexOf(from), next = all[(at + (back ? -1 : 1) + all.length) % all.length];
    next?.focus();
    return next;
  }
  async function press([spec, target]) {
    const k = keyFrom(spec);
    let el = target ? find(target).el : document.activeElement;
    // The person may be typing in the composer: an agent's key never lands in it.
    if (!el || el === document.body || el.closest(OWN)) el = mainNode() || document.body;
    const d = describe(el), before = moment(), said = alerts();
    if (target) { reveal(el); flash(el); take(el); }
    const init = { ...k, bubbles: true, cancelable: true, composed: true };
    const go = el.dispatchEvent(new KeyboardEvent("keydown", init));
    const plain = !k.ctrlKey && !k.metaKey && !k.altKey;
    let moved = null;
    if (go && plain) {
      if ((k.key === "Enter" && el.matches("button, a[href], summary, [role=button], [role=link], [data-act]"))
        || (k.key === " " && el.matches("button, summary, [role=button], [role=checkbox], [role=switch], input[type=checkbox], input[type=radio]"))) el.click();
      else if (k.key === "Enter" && el.matches("input") && el.form) el.form.requestSubmit?.();
      else if (k.key === "Tab") moved = moveFocus(el, k.shiftKey);
    }
    el.dispatchEvent(new KeyboardEvent("keyup", init));
    const where = moved ? ` Focus is now on ${label(describe(moved))}.` : "";
    return { text: `Pressed ${spec}${target ? ` on ${label(d)}` : ""}.${where} ${await after(el, d, before, said)}`.trim() };
  }
  async function hover([target]) {
    const { el } = find(target), d = describe(el);
    reveal(el);
    flash(el);
    const at = point(el), touch = { pointerType: "mouse", isPrimary: true, pointerId: 1 };
    for (const type of ["pointerover", "pointerenter", "mouseover", "mouseenter", "pointermove", "mousemove"]) {
      const Kind = type.startsWith("pointer") ? PointerEvent : MouseEvent;
      el.dispatchEvent(new Kind(type, { ...at, ...touch, bubbles: !type.endsWith("enter") }));
    }
    await frames();
    const tips = [squash(el.closest("[title]")?.getAttribute("title")),
      ...[...document.querySelectorAll("[role=tooltip]")].filter(shown).map((t) => squash(t.innerText))].filter(Boolean);
    return { text: `Hovered ${label(d)}.${tips.length ? ` Its tip reads: ${tips.join(" / ")}` : ""}` };
  }
  function scroll([where]) {
    const w = where.toLowerCase(), step = { up: -0.8, down: 0.8 }[w];
    if (step) window.scrollBy({ top: step * innerHeight, behavior: "instant" });
    else if (w === "top") window.scrollTo({ top: 0, behavior: "instant" });
    else if (w === "bottom") window.scrollTo({ top: document.documentElement.scrollHeight, behavior: "instant" });
    else {
      const { el } = find(where);
      reveal(el);
      return { text: `Brought ${label(describe(el))} into view.` };
    }
    const most = Math.max(1, document.documentElement.scrollHeight - innerHeight);
    return { text: `Scrolled ${w}. The view is ${Math.round((100 * scrollY) / most)}% of the way down the page.` };
  }
  function highlight(words, order) {
    clearRings();
    if (order.clear) return { text: "Cleared the highlights." };
    if (!words.length) throw refuse("Name what to highlight, or clear the highlights with --clear.");
    const found = words.map((word) => find(word));
    reveal(found[0].el);
    found.forEach(({ el, ref }, i) => ring(el, ref, i === 0 ? order.note : null));
    const named = found.map(({ el }) => label(describe(el))).join("; ");
    const hidden = found.some(({ el }) => covered(el)) ? " Part of it sits behind the composer's sheet, so the person sees it only once the sheet is closed." : "";
    return { text: `Highlighted ${named}.${hidden} The ring stays until the person presses anywhere, or you clear it.` };
  }
  async function go([where]) {
    const want = where.toLowerCase(), before = mainNode();
    if (want === "back") {
      history.back();
      if (!(await swapped(before, 8000))) throw refuse("There is no page to go back to.");
      return { text: `Went back. Now on ${here().name}.` };
    }
    const page = PAGES.find(([f, name]) => f === want || f === `${want}.html` || name.toLowerCase() === want)
      || PAGES.find(([, name]) => name.toLowerCase().startsWith(want));
    if (!page) throw refuse(`There is no page called "${where}". The pages are: ${PAGES.map(([f]) => f.replace(".html", "")).join(", ")}.`);
    if (here().file === page[0]) return { text: `Already on ${page[1]}.` };
    clearRings();
    document.body.classList.remove("pilot-room");
    await CONSOLE.navigate(page[0], { focus: !typing() });
    if (here().file !== page[0]) throw refuse(`${page[1]} did not open. The page shows why.`);
    return { text: `Now on ${page[1]}.` };
  }
  function read([target]) {
    const el = target ? find(target).el : modal() || mainNode() || document.body;
    const text = (el.innerText || "").replace(/\n{3,}/g, "\n\n").trim();
    const cut = text.length > READ_CAP ? `${text.slice(0, READ_CAP)}\n… (${text.length - READ_CAP} more characters; read a part by its name)` : text;
    return { text: cut || "Nothing to read there." };
  }
  function snapshot() {
    refs.clear();
    counter = 0;
    const page = here(), dialog = modal(), theme = document.documentElement.dataset.theme || "night";
    const lines = [`${page.name} (${page.file}), ${theme} theme, ${innerWidth}x${innerHeight}, scrolled ${Math.round(scrollY)}px.`];
    if (dialog) lines.push(`A dialog is open ("${nameOf(dialog) || squash(dialog.querySelector(HEADING)?.innerText)}"). Only its controls are listed.`);
    if (composerOpen()) lines.push("The composer's sheet is open over the lower middle of the page.");
    const counts = new Map(), elements = [];
    let place = null;
    for (const el of controls()) {
      const d = describe(el), key = keyOf(d), nth = counts.get(key) || 0;
      counts.set(key, nth + 1);
      const ref = remember(el, d, controlAgain(key, nth));
      if (d.place !== place) { place = d.place; lines.push("", `${place || "On the page"}:`); }
      lines.push(`  ${line(ref, d)}`);
      elements.push({ ref, ...d });
    }
    if (!elements.length) lines.push("", "No controls are showing.");
    const notes = alerts();
    if (notes.length) lines.push("", `The page says: ${notes.join(" / ")}`);
    return { text: lines.join("\n"), elements };
  }

  const ACTIONS = { snapshot, read, tap, fill, select, press, hover, scroll, highlight, goto: go };
  async function obey(order) {
    let said;
    try {
      const act = ACTIONS[order.action];
      if (!act) throw refuse(`The console has no action called "${order.action}".`);
      said = { ok: true, ...(await act(order.words || [], order)) };
    } catch (error) {
      said = { ok: false, error: String(error?.message || error) };
    }
    await API.post("/api/console/pilot/answer", { ...said, page: here().file, id: order.id });
  }

  // ---- asking the daemon for orders ----------------------------------------------------------------------------
  let asking = null, listening = false;
  async function listen() {
    if (listening) return;
    listening = true;
    let wait = 0;
    try {
      while (!document.hidden) {
        const ask = new AbortController();
        asking = ask;
        const res = await API.scoped(ask.signal).get(`/api/console/pilot/next?tab=${NAME}&focused=${document.hasFocus() ? 1 : 0}`);
        asking = null;
        if (ask.signal.aborted) continue;
        if (res.status === 200 && res.body?.id) { wait = 0; await obey(res.body); continue; }
        if (res.status === 204) { wait = 0; continue; }
        wait = Math.min(Math.max(wait * 2, 1000), 30000); // refused or unreachable: splice may be restarting
        await sleep(wait);
      }
    } finally {
      listening = false;
    }
  }
  const leave = () => API.parting(`/api/console/pilot/leave?tab=${NAME}`);
  document.addEventListener("visibilitychange", () => {
    if (document.hidden) { asking?.abort(); leave(); } else listen();
  });
  // A tab the person just looked at tells the daemon at once, so it is the one an order goes to.
  addEventListener("focus", () => asking?.abort());
  addEventListener("pagehide", leave);
  listen();

  return { name: NAME };
})();
