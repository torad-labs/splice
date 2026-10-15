// The composer: one field at the bottom of every console page that types a message into a live session, the way
// Sessions' own Message field does, with the session picked from the live ones grouped per project (Marcos, Oct 15:
// "let's bring a composer into the picture", after the Temple's).
//
// WHERE EVERY VALUE COMES FROM. The composer carries no data of its own:
//   GET  /api/sessions               every 10 s while the tab is in view and the console unlocked: the live sessions
//   GET  /api/sessions/{id}/screen   just before a send: a session showing a menu takes no typing, since a typed Enter
//                                    would press its highlighted choice
//   POST /api/sessions/{id}/say      the message, typed into the terminal splice opened for it (200 = typed)
//   POST /api/sessions/{id}/message  a note to a session splice did not open (202 = submitted, never "delivered")
//
// It mounts once, after console.js has taken the page's own nodes, outside main.page, so the router's swap of
// main.page never removes it; and it draws the count of sessions that need him as a lamp on the nav's Sessions item.
"use strict";

(() => {
  const STORE = "splice-console-composer-target";
  const EVERY_MS = 10_000;
  const SAID_MS = 4_000;
  const MAC = /Mac|iPhone|iPad/.test(navigator.platform || navigator.userAgent || "");
  const PLACEHOLDER = `Message a session. ${MAC ? "⌘K" : "Ctrl+K"}`;
  const NONE_LIVE = "No live session to message";
  // the cards' rank (kit rankOf): waiting on him, in trouble, working, the rest; a row not heard from in 30 min last
  const RANK = { needs: 0, stalled: 1, working: 2, idle: 3, quiet: 4, ended: 5 };
  const WORD = { needs: "Needs you", stalled: "Stalled", working: "Working", idle: "Idle", quiet: "Not heard from", ended: "Ended" };
  const COUNTED = ["needs", "stalled", "working", "idle", "quiet"];

  const h = (v) => String(v ?? "").replace(/[&<>"']/g, (c) => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" })[c]);
  const oneLine = (v) => String(v ?? "").replace(/\s+/g, " ").trim();
  const path = (s, act) => `/api/sessions/${encodeURIComponent(s.id)}/${act}`;
  const locked = () => document.body.classList.contains("locked");

  const st = {
    rows: [], byId: new Map(), notes: new Set(), read: false, reading: false, readAt: 0,
    target: null, seen: null, pinned: false, open: false, q: "", active: 0, flat: [], busy: false, line: null, href: "",
  };
  let dock, chip, lamp, who, where, field, send, line, pop, q, list, lineTimer = 0, listHtml = "";

  // ---------- reading ----------
  /** One row in the lamp's words, read the way Sessions reads it (kit rowLook), without the live turns it also asks. */
  function lookOf(row) {
    let state = row.waiting_for ? "needs" : row.availability !== "stale" && (row.status === "working" || row.status === "busy") ? "working" : "idle";
    let stall = null;
    try { ({ state, stall } = rowLook(row, null, null)); } catch { /* kit.js absent: the row's own status stands */ }
    if (state === "needs") return "needs";
    if (stall) return "stalled";
    if (state === "working") return "working";
    return row.availability === "stale" ? "quiet" : "idle";
  }
  function sessionOf(row) {
    return {
      id: row.session_id, row, look: lookOf(row),
      name: oneLine(row.name || row.title) || row.session_id.slice(0, 8),
      project: row.project?.label || row.project?.name || "No project",
      key: row.project?.id || "-", // the project pick's key (kit PICK, NO_PROJECT_KEY)
      last: oneLine(row.last?.text) || oneLine(row.waiting_for),
      at: +new Date(row.updated_at || row.status_updated_at || row.started_at || 0),
    };
  }
  const urgent = (a, b) => RANK[a.look] - RANK[b.look] || b.at - a.at;

  async function refresh() {
    if (st.reading || document.hidden || locked()) return;
    st.reading = true;
    const res = await API.get("/api/sessions");
    st.reading = false; st.readAt = Date.now();
    if (!res.ok) return; // what was read last stands; a locked console hides the composer (body.locked)
    st.read = true;
    st.notes = new Set(res.body?.note_versions || []);
    st.rows = (res.body?.sessions || []).filter((r) => r?.session_id && r.availability !== "gone").map(sessionOf);
    st.byId = new Map(st.rows.map((s) => [s.id, s]));
    settle();
    draw();
  }

  // ---------- the target ----------
  const pickedProject = () => {
    try { return PICK.get(); } catch { return new URLSearchParams(location.search).get("project") || null; }
  };
  /** The session open on Sessions, else the most urgent live one of the picked project, else the one last used. */
  function defaultTarget() {
    if (/(^|\/)sessions(\.html)?$/.test(location.pathname)) {
      const id = decodeURIComponent(location.hash.slice(1));
      const open = id && (st.byId.get(id) ?? (id.length >= 8 ? st.rows.find((s) => s.id.startsWith(id)) : null));
      if (open) return open.id;
    }
    const pick = pickedProject();
    const top = pick ? st.rows.filter((s) => s.key === pick).sort(urgent)[0] : null;
    if (top) return top.id;
    let last = null;
    try { last = localStorage.getItem(STORE); } catch { /* no storage: no memory */ }
    return last && st.byId.has(last) ? last : null;
  }
  /** Follow the page until he takes the session himself: picking one, sending to one, or typing to one fixes it, so a
   *  read never moves his words to another session under him. */
  function settle() {
    const held = st.pinned || st.busy || st.line?.held != null || field.value.trim() !== "" || document.activeElement === field;
    if (!held && st.read) st.target = defaultTarget();
    const s = st.byId.get(st.target);
    if (s) st.seen = s;
  }
  /** The target as last read; one that left the list reads Ended under the name it had. */
  const cur = () => st.byId.get(st.target) ?? (st.target && st.read && st.seen?.id === st.target ? { ...st.seen, look: "ended" } : null);
  const notable = (s) => Boolean(s.row.address) && st.notes.has(s.row.version);
  function remember(id) {
    try { localStorage.setItem(STORE, id); } catch { /* no storage: no memory */ }
  }

  // ---------- drawing ----------
  function draw() {
    drawChip(); drawField(); drawNeeds();
    if (st.open) drawPop();
  }
  function drawChip() {
    const s = cur();
    lamp.className = `cmp-lamp ${s ? s.look : "none"}`;
    who.textContent = s ? s.name : st.read && !st.rows.length ? "No session" : "Choose a session";
    where.textContent = s ? s.project : "";
    where.hidden = !s;
    const said = s ? `${s.name}, ${s.project}, ${WORD[s.look]}` : who.textContent;
    chip.title = s ? `${said}. Choose another session` : "Choose a session";
    chip.setAttribute("aria-label", s ? `To ${said}. Choose another session` : "Choose a session");
  }
  function drawField() {
    field.placeholder = st.read && !st.rows.length && !cur() ? NONE_LIVE : PLACEHOLDER;
    field.readOnly = st.busy;
    send.disabled = st.busy || !field.value.trim() || cur()?.look === "ended";
    send.textContent = st.busy ? "Sending" : "Send";
    send.setAttribute("aria-busy", String(st.busy));
  }
  /** The count of sessions that need him, as a quiet gold lamp on the nav's Sessions item; the number is in its title. */
  function drawNeeds() {
    const a = document.querySelector('.nav a[href="sessions.html"]');
    if (!a || !st.read) return;
    const n = st.rows.filter((s) => s.look === "needs").length;
    if (n) {
      a.dataset.needs = String(n);
      a.title = n === 1 ? "1 session needs you" : `${n} sessions need you`;
    } else if (a.dataset.needs) {
      delete a.dataset.needs;
      a.removeAttribute("title");
    }
  }
  function fit() {
    field.style.height = "auto";
    field.style.height = `${field.scrollHeight}px`;
  }

  // the line above the field: what a send did, or why it did not, with the one way out it offers
  function say(next) {
    clearTimeout(lineTimer);
    st.line = next;
    if (next?.tone === "ok") lineTimer = setTimeout(() => { if (st.line === next) say(null); }, SAID_MS);
    line.hidden = !next;
    if (!next) { line.innerHTML = ""; return; }
    line.className = `cmp-line ${next.tone}`;
    const full = next.held != null ? `${next.text} “${next.held}”` : next.text;
    line.innerHTML = `<span class="cmp-mark" aria-hidden="true"></span><span class="cmp-text" title="${h(next.title || full)}">${h(next.text)}`
      + `${next.held != null ? ` <q>${h(next.held)}</q>` : ""}</span>`
      + (next.acts?.length ? `<span class="cmp-acts">${next.acts.map((x, i) => `<button type="button" class="act small${x.primary ? " primary" : " quiet"}" data-i="${i}">${h(x.word)}</button>`).join("")}</span>` : "");
  }
  function needsLine(s) {
    say({ tone: "held", text: `${s.name} is asking you something. Answer it on Sessions.`, acts: [{ word: "Open on Sessions", primary: true, run: () => go(s) }] });
  }
  function go(s) {
    say(null);
    const to = `sessions.html#${encodeURIComponent(s.id)}`;
    if (typeof CONSOLE !== "undefined") CONSOLE.navigate(to); else location.href = to;
  }

  // ---------- the picker ----------
  function groups() {
    const words = st.q.toLowerCase().split(/\s+/).filter(Boolean);
    const hay = (s) => `${s.name} ${s.row.title || ""} ${s.project} ${s.row.head || ""} ${s.row.team_name || ""} ${s.id}`.toLowerCase();
    const by = new Map();
    for (const s of st.rows.filter((r) => words.every((w) => hay(r).includes(w))).sort(urgent)) {
      if (!by.has(s.key)) by.set(s.key, { key: s.key, label: s.project, rows: [] });
      by.get(s.key).rows.push(s);
    }
    const pick = pickedProject();
    const latest = (g) => Math.max(...g.rows.map((s) => s.at));
    // the projects with a session waiting on him first, then the picked project, then the most recently active
    return [...by.values()].sort((a, b) => (a.rows[0].look === "needs" ? 0 : 1) - (b.rows[0].look === "needs" ? 0 : 1)
      || (a.key === pick ? 0 : 1) - (b.key === pick ? 0 : 1) || latest(b) - latest(a));
  }
  const counts = (rows) => COUNTED.map((k) => [k, rows.filter((s) => s.look === k).length]).filter(([, n]) => n)
    .map(([k, n]) => `${n} ${WORD[k].toLowerCase()}`).join(" · ");
  function drawPop() {
    const gs = groups();
    st.flat = gs.flatMap((g) => g.rows);
    st.active = Math.max(0, Math.min(st.active, st.flat.length - 1));
    let i = 0;
    const html = gs.length ? gs.map((g, gi) => `<div class="cmp-group" role="group" aria-labelledby="cmp-g${gi}">`
      + `<p class="cmp-gh" id="cmp-g${gi}"><b>${h(g.label)}</b><span>${h(counts(g.rows))}</span></p>`
      + g.rows.map((s) => {
        const n = i++;
        return `<div class="cmp-o" id="cmp-o${n}" role="option" aria-selected="${s.id === st.target}" data-n="${n}">`
          + `<span class="cmp-lamp ${s.look}" aria-hidden="true"><i></i></span><span class="n">${h(s.name)}</span>`
          + `<span class="w ${s.look}">${WORD[s.look]}</span><span class="l">${h(s.last)}</span></div>`;
      }).join("") + `</div>`).join("")
      : `<p class="cmp-empty">${st.q.trim() ? `No live session matches “${h(st.q.trim())}”.` : st.read ? "No live session." : "Reading the sessions."}</p>`;
    // drawn again only when it changed, so a working lamp breathes as the list opens and then holds
    if (html !== listHtml) { list.innerHTML = html; listHtml = html; }
    mark(false);
  }
  /** The row the keys are on: lit, in view, and named to the search field for a screen reader. */
  function mark(scroll) {
    for (const el of list.querySelectorAll(".cmp-o.on")) el.classList.remove("on");
    const el = st.flat.length ? list.querySelector(`#cmp-o${st.active}`) : null;
    el?.classList.add("on");
    if (scroll) el?.scrollIntoView({ block: "nearest" });
    if (el) q.setAttribute("aria-activedescendant", el.id); else q.removeAttribute("aria-activedescendant");
  }
  function setActive(n) {
    if (!st.flat.length) return;
    st.active = (n + st.flat.length) % st.flat.length;
    mark(true);
  }
  function openPop() {
    st.open = true; st.q = ""; q.value = "";
    const gs = groups();
    st.active = Math.max(0, gs.flatMap((g) => g.rows).findIndex((s) => s.id === st.target));
    listHtml = "";
    drawPop();
    pop.hidden = false;
    chip.setAttribute("aria-expanded", "true");
    q.focus();
    mark(true);
    if (Date.now() - st.readAt > 3000) refresh();
  }
  function closePop() {
    if (!st.open) return;
    st.open = false;
    pop.hidden = true;
    chip.setAttribute("aria-expanded", "false");
  }
  function pick(s) {
    if (!s) return;
    st.target = s.id; st.seen = s; st.pinned = true;
    remember(s.id);
    say(null);
    closePop();
    draw();
    field.focus();
    if (s.look === "needs") needsLine(s);
  }

  // ---------- sending ----------
  /** His message, whole, to the target. Words that did not go through stay in the field. */
  async function submit(clear = false) {
    if (st.busy) return;
    const text = field.value.trim();
    if (!text) { field.focus(); return; }
    const s = cur();
    if (!s) { openPop(); return; }
    if (s.look === "ended") { say({ tone: "refused", text: `${s.name} has ended. Choose another session.` }); return; }
    if (s.look === "needs") { needsLine(s); return; }
    st.busy = true; st.pinned = true; say(null); drawField();
    // a session that began asking since the last read shows a menu, which typed words and their Enter would answer
    const screen = await API.get(path(s, "screen"));
    if (screen.ok && (screen.body?.choices || []).length) { st.busy = false; drawField(); needsLine(s); return; }
    const res = await API.post(path(s, "say"), clear ? { text, clear: true } : { text });
    st.busy = false;
    if (res.ok) { done(s, `Typed into ${s.name}.`); return; }
    refused(s, res);
  }
  /** A note to a session splice did not open: handed to its messaging socket, which gives no receipt. */
  async function note(s) {
    const text = field.value.trim();
    if (st.busy || !text) return;
    st.busy = true; say(null); drawField();
    const res = await API.post(path(s, "message"), { text });
    st.busy = false;
    if (res.ok) { done(s, `Note submitted to ${s.name}.`); return; }
    drawField();
    say({ tone: "refused", text: `Note not sent: ${res.status === 0 ? "splice is not answering" : res.body?.error || `splice answered ${res.status}`}.` });
  }
  function done(s, word) {
    field.value = ""; fit();
    remember(s.id);
    drawField();
    say({ tone: "ok", text: word });
    lamp.classList.remove("sent"); void lamp.offsetWidth; lamp.classList.add("sent");
    setTimeout(() => lamp.classList.remove("sent"), 900);
    field.focus();
    setTimeout(refresh, 1500); // its state moves once the words land
  }
  /** Why /say did not type, in plain words, with the way out it offers (SessionDrive's refusals, by their reason). */
  function refused(s, res) {
    drawField();
    const why = res.body?.error || "";
    const plain = (text) => say({ tone: "refused", text, title: why || text });
    switch (res.body?.reason) {
      case "draft":
        say({ tone: "held", text: `${s.name}'s prompt already holds`, held: oneLine(res.body.draft),
          acts: [{ word: "Cancel", run: () => { say(null); field.focus(); } }, { word: "Clear and send", primary: true, run: () => submit(true) }] });
        return;
      case "not_ours":
        say({ tone: "refused", text: notable(s) ? `splice did not open ${s.name}, so it cannot type there.` : `splice did not open ${s.name}, so it cannot type there. Write in the terminal it runs in.`,
          title: why, acts: notable(s) ? [{ word: "Send as a note", primary: true, run: () => note(s) }] : [] });
        return;
      case "closed": plain(`${s.name}'s terminal is closed. Nothing was sent.`); return;
      case "pane_gone": plain(`The terminal ${s.name} started in is gone. Nothing was typed.`); return;
      case "pane_taken": plain(`${s.name}'s terminal runs something else now. Nothing was typed.`); return;
      case "no_terminal": plain("splice has no terminal to type into yet. Nothing was typed."); return;
      case "ended": plain(`${s.name} has ended. Nothing was sent.`); return;
      case "refused": plain(`The terminal did not take it. Look at ${s.name} before sending again.`); return;
      default:
        plain(res.status === 0 ? `splice is not answering. Look at ${s.name} before sending again.`
          : why ? `Not typed: ${why}.` : `splice answered ${res.status}. Look at ${s.name} before sending again.`);
    }
  }

  // ---------- mounting ----------
  function mount() {
    dock = document.createElement("div");
    dock.className = "cmp";
    dock.setAttribute("role", "region");
    dock.setAttribute("aria-label", "Composer");
    dock.innerHTML = `<div class="cmp-line" role="status" aria-live="polite" hidden></div>`
      + `<div class="cmp-base">`
      + `<div class="cmp-pop" id="cmp-pop" hidden><label class="cmp-find"><svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" aria-hidden="true"><circle cx="10.5" cy="10.5" r="6.5"/><path d="M15.5 15.5L21 21"/></svg>`
      + `<input id="cmp-q" type="search" placeholder="Find a session" aria-label="Find a session" role="combobox" aria-expanded="true" aria-controls="cmp-list" aria-autocomplete="list" autocomplete="off" spellcheck="false"></label>`
      + `<div class="cmp-list" id="cmp-list" role="listbox" aria-label="Live sessions"></div></div>`
      + `<form class="cmp-pill" novalidate><button type="button" class="cmp-chip" aria-haspopup="listbox" aria-expanded="false" aria-controls="cmp-pop">`
      + `<span class="cmp-lamp none" aria-hidden="true"><i></i></span><span class="cmp-who"><b></b><span></span></span>`
      + `<svg class="cmp-caret" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.4" aria-hidden="true"><path d="M6 15l6-6 6 6"/></svg></button>`
      + `<textarea class="cmp-field" rows="1" aria-label="Message" autocomplete="off"></textarea>`
      + `<button type="submit" class="act primary cmp-send" disabled>Send</button></form></div>`;
    chip = dock.querySelector(".cmp-chip"); lamp = chip.querySelector(".cmp-lamp");
    who = chip.querySelector(".cmp-who b"); where = chip.querySelector(".cmp-who span");
    field = dock.querySelector(".cmp-field"); send = dock.querySelector(".cmp-send");
    line = dock.querySelector(".cmp-line"); pop = dock.querySelector(".cmp-pop");
    q = dock.querySelector("#cmp-q"); list = dock.querySelector(".cmp-list");
    document.body.append(dock);

    dock.querySelector("form").addEventListener("submit", (e) => { e.preventDefault(); submit(); });
    chip.addEventListener("click", () => (st.open ? (closePop(), field.focus()) : openPop()));
    field.addEventListener("input", () => { fit(); drawField(); });
    field.addEventListener("focus", () => {
      closePop();
      const s = cur();
      if (s?.look === "needs" && !st.line) needsLine(s);
    });
    field.addEventListener("keydown", (e) => {
      if (e.key !== "Enter" || e.shiftKey || e.altKey || e.isComposing) return;
      e.preventDefault();
      submit();
    });
    q.addEventListener("input", () => { st.q = q.value; st.active = 0; drawPop(); });
    q.addEventListener("keydown", (e) => {
      if (e.key === "ArrowDown" || e.key === "ArrowUp") { e.preventDefault(); setActive(st.active + (e.key === "ArrowDown" ? 1 : -1)); }
      else if (e.key === "Enter") { e.preventDefault(); pick(st.flat[st.active]); }
    });
    list.addEventListener("pointerdown", (e) => e.preventDefault()); // the search keeps the focus
    list.addEventListener("pointermove", (e) => { const o = e.target.closest(".cmp-o"); if (o && +o.dataset.n !== st.active) setActive(+o.dataset.n); });
    list.addEventListener("click", (e) => { const o = e.target.closest(".cmp-o"); if (o) pick(st.flat[+o.dataset.n]); });
    line.addEventListener("click", (e) => { const b = e.target.closest("button[data-i]"); if (b) st.line?.acts?.[+b.dataset.i]?.run(); });
    // its keys are its own: Escape here never also closes what the page has open (Sessions' in-prompt card, a menu)
    dock.addEventListener("keydown", (e) => {
      e.stopPropagation();
      if (e.key !== "Escape") return;
      e.preventDefault();
      if (st.open) { closePop(); field.focus(); return; }
      if (st.line?.acts?.length) { say(null); return; }
      document.activeElement?.blur();
    });
    dock.addEventListener("focusout", (e) => { if (st.open && !dock.contains(e.relatedTarget)) closePop(); });
    document.addEventListener("pointerdown", (e) => { if (st.open && !dock.contains(e.target)) closePop(); }, true);
    addEventListener("keydown", (e) => {
      if (!(e.ctrlKey || e.metaKey) || e.altKey || e.shiftKey || String(e.key).toLowerCase() !== "k") return;
      if (locked() || document.querySelector(".scrim")) return;
      e.preventDefault(); e.stopPropagation();
      closePop();
      field.focus();
    }, true);

    // the page's address names the open session on Sessions; the router and Sessions move it without an event
    st.href = location.href;
    setInterval(() => { if (location.href !== st.href) { st.href = location.href; settle(); draw(); } }, 700);
    setInterval(refresh, EVERY_MS);
    document.addEventListener("visibilitychange", () => { if (!document.hidden && Date.now() - st.readAt > EVERY_MS / 2) refresh(); });
    draw();
    refresh();
  }
  // after console.js's own DOMContentLoaded (it loads first), so the router never counts the dock among the page's nodes
  if (document.readyState === "loading") addEventListener("DOMContentLoaded", mount); else mount();
})();
