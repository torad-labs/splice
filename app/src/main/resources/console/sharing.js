// Sharing: this machine's commands offered to his other machines, and the machines whose commands this one uses
// (docs/specs/lan-share.md). The left column is this machine: sharing on or off, the port it listens on, the certificate
// a joining machine pins, which commands it offers, and every machine that joined with when it was last heard from. The
// right column is the other machines: each one this machine uses, whether it answers and its commands here, and the
// machines found on his networks with what each offers. Reads: GET /api/share every 5 s while the page is in view.
// Acts: PUT /api/share (on, off, port, which commands), DELETE /api/share/machines/{name}, POST /api/remotes/scan,
// POST /api/remotes, POST /api/remotes/{name}/sync, DELETE /api/remotes/{name}. The last three change which commands
// splice runs, so they end in the console's own restart (POST /api/daemon/restart), asked once when it would cut a request.
// While splice.toml does not load, GET /api/share answers its refusal (ok false and what is wrong with the file), and
// the page says those lines with nothing that acts until the file loads again (round 17 of PR #419's review).
CONSOLE.page("sharing.html", (scope) => {
  "use strict";
  const { document, API, setTimeout, setInterval } = scope;

const WAIT = '<svg class="wait" viewBox="0 0 40 40" aria-hidden="true"><circle cx="20" cy="20" r="15"/></svg>';
const COPY = G('<rect x="9" y="9" width="12" height="12" rx="2"/><path d="M5 15V5a2 2 0 0 1 2-2h10"/>', 'aria-hidden="true"');
const root = document.getElementById("board");

// what the daemon says: GET /api/share, what it said when splice.toml did not load, and the last scan of his networks
const data = { share: null, err: null, refused: null, found: null };
// what he is doing: the press armed for a second press, the act in flight, the words an act came back with (by card),
// the port field's draft, the scan in flight, the address typed to add, and the restart an act left due
const ui = { armed: null, busy: null, said: {}, port: null, scanning: false, host: "", restart: null, cut: null };

async function read() {
  const res = await API.get("/api/share");
  if (!res.ok) {
    if (res.status !== 0) console.warn(`sharing: /api/share answered ${res.status}`);
    data.err = res.status === 0 ? "Not answering" : res.status === 503 ? "Sharing is not wired in this splice" : "Not read";
    return;
  }
  data.err = null;
  if (res.body?.ok === false || !res.body?.sharing) {
    data.share = null;
    data.refused = res.body?.said?.length ? res.body.said : [{ text: "splice.toml does not load", bad: true }];
    return;
  }
  data.refused = null;
  data.share = res.body;
}

// ---------- words ----------
/** An instant as a time today, else the day and the time ("4:18 AM", "Oct 22, 4:18 AM"). */
const when = (iso) => { const d = new Date(iso); return Number.isNaN(d.getTime()) ? "" : d.toDateString() === NOW.toDateString() ? clock(d) : `${dayWord(d)}, ${clock(d)}`; };
const turns = (n) => (n === 1 ? "1 turn" : `${n} turns`);
/** A certificate's fingerprint short enough to read and compare by eye: its first and last eight. */
const shortPin = (pin) => { const hex = String(pin).replace(/^sha256:/, ""); return hex.length > 20 ? `${hex.slice(0, 8)}…${hex.slice(-8)}` : hex; };
/** Lines the daemon said, the refusals in the limit's ink. */
const toldHtml = (lines) => {
  if (!lines?.length) return "";
  return `<ul class="told">${lines.map((l) => `<li${l.bad ? ' class="limit"' : ""}>${esc(l.text)}</li>`).join("")}</ul>`;
};
/** The lines an act came back with, under the card that asked. */
const saidHtml = (key) => toldHtml(ui.said[key]);
const armed = (key) => ui.armed === key;
const busy = (key) => ui.busy === key;

// ---------- this machine ----------
function shareHtml(s) {
  const sh = s.sharing, on = sh.enabled;
  const sw = `<div class="switch" role="group" aria-label="Sharing">
    <button data-act="share" data-on="0" aria-pressed="${!on}"${busy("share") ? " disabled" : ""}>Off</button>
    <button data-act="share" data-on="1" aria-pressed="${on}"${busy("share") ? " disabled" : ""}>On</button></div>`;
  return `<section class="provider here" style="--c:var(--key-light)"><header><h2><span class="blot"></span>${esc(s.machine)}</h2>${sw}</header>
    <article class="card shared">${listenHtml(sh)}${saidHtml("share")}${commandsHtml(sh)}</article>
    <h3 class="sect">Machines that joined</h3>${joinedHtml(s.joined, on)}</section>`;
}
/** Whether it listens, on which port, and the certificate a joining machine pins; why not, in the daemon's words. */
function listenHtml(sh) {
  const draft = ui.port ?? String(sh.port);
  const port = `<span class="portbox"><label class="lbl" for="port">Port</label><input class="field port" id="port" inputmode="numeric" value="${esc(draft)}" aria-invalid="${!/^\d{1,5}$/.test(draft)}">${
    ui.port != null && ui.port !== String(sh.port) ? `<button class="act small" data-act="port">Save</button>` : ""}</span>`;
  let state;
  if (!sh.enabled) state = `<span class="state mute">Off: no other machine can use this one's commands</span>`;
  else if (!sh.following) state = `<span class="state">${WAIT}Opening</span>`;
  else if (sh.listening) state = `<span class="state live"><i aria-hidden="true"></i>Listening on your private networks</span>`;
  else state = `<span class="state limit">Not listening: ${esc(sh.why ?? "its log says why")}</span>`;
  const pin = sh.fingerprint
    ? `<div class="pin"><span class="lbl">Certificate</span><code title="${esc(sh.fingerprint)}">${esc(shortPin(sh.fingerprint))}</code><button class="icon" data-act="copy-pin" aria-label="Copy the whole fingerprint">${COPY}</button><span class="hint">A machine that joins pins this; compare it with what it printed</span></div>`
    : `<div class="pin"><span class="lbl">Certificate</span><span class="hint">Made the first time sharing turns on</span></div>`;
  return `<div class="top">${state}${port}</div>${pin}`;
}
/** Each command, and whether other machines may use it. A command that cannot be offered says why instead. */
function commandsHtml(sh) {
  const rows = sh.commands.map((c) => {
    const ctl = c.why
      ? `<span class="state mute">${esc(c.why[0].toUpperCase() + c.why.slice(1))}</span>`
      : `<div class="switch small" role="group" aria-label="${esc(c.command)}">
        <button data-act="offer" data-key="${esc(c.key)}" data-on="1" aria-pressed="${c.offered}"${busy("share") ? " disabled" : ""}>Shared</button>
        <button data-act="offer" data-key="${esc(c.key)}" data-on="0" aria-pressed="${!c.offered}"${busy("share") ? " disabled" : ""}>Only here</button></div>`;
    const off = sh.enabled && sh.listening && c.offered && !c.serving ? `<span class="state limit">Not running here</span>` : "";
    return `<li><span class="chip">${esc(c.command)}</span><span class="model">${esc(c.model ?? "")}</span>${off}${ctl}</li>`;
  }).join("");
  return `<h3 class="sub-h">Commands</h3><ul class="cmdlist">${rows || `<li class="none">No commands here yet</li>`}</ul>`;
}
function joinedHtml(joined, on) {
  if (!joined.length) {
    const how = on ? "On another of your machines, run <code>splice remote</code>, or open its Sharing page and find this one." : "Turn sharing on, then find this machine from another one.";
    return `<p class="empty">No machine has joined. ${how}</p>`;
  }
  return `<div class="rail plain">${joined.map((m) => {
    const key = `machine:${m.name}`;
    const seen = m.last_seen ? `Last seen ${when(m.last_seen)}, ${turns(m.turns_today ?? 0)} today` : "Not heard from since it joined";
    const act = armed(key)
      ? `<span class="armed"><span class="said">Its next turn is refused until it joins again</span><button class="act small danger" data-act="revoke" data-name="${esc(m.name)}">Revoke</button><button class="act small quiet" data-act="keep">Keep</button></span>`
      : `<button class="act small quiet" data-act="arm" data-key="${esc(key)}">Revoke</button>`;
    return `<article class="card m" style="--c:var(--key-light)"><div class="top"><span class="name">${esc(m.name)}</span><span class="slot">${act}</span></div>
      <p class="facts">${esc(seen)}</p><p class="facts mute">From ${esc(m.address)}, joined ${esc(when(m.joined_at))}</p>${crossingHtml(m, "machines", key)}${saidHtml(key)}</article>`;
  }).join("")}</div>`;
}

/** Whether Claude Code sessions cross with this machine, and this card's switch: off until he turns it on. [side] is
 *  "machines" for one that joined this machine and "remotes" for one this machine joined. A machine on both lists has a
 *  switch on each card, and nothing crosses until both are on. A card of one that joined names the join it shows, so a
 *  machine that joined since under its name is not turned on by it. */
function crossingHtml(m, side, key) {
  const on = m.sessions === true;
  const other = side === "machines" ? "Other machines" : "Machines that joined";
  let says;
  if (!on) says = `<span class="hint">Off: its sessions and this machine's don't see or message each other</span>`;
  else if (m.crossing === false) says = `<span class="hint">On here, Off on its card under ${other}: nothing crosses until both are on</span>`;
  else says = countsHtml(m);
  const joined = side === "machines" ? ` data-joined="${esc(m.joined_at ?? "")}"` : "";
  const btn = (want, label) => `<button data-act="sessions" data-side="${side}" data-name="${esc(m.name)}"${joined} data-on="${want ? 1 : 0}" aria-pressed="${on === want}"${busy(key) ? " disabled" : ""}>${label}</button>`;
  return `<div class="crossing"><span class="sub">Sessions</span>${says}<div class="switch small" role="group" aria-label="Sessions with ${esc(m.name)}">${btn(false, "Off")}${btn(true, "On")}</div></div>`;
}

/** How many of its sessions are listed here and how many of this machine's there, beside the switch that is on. */
function countsHtml(m) {
  if (m.sessions_here == null) return "";
  const there = m.sessions_there == null ? "this machine's not listed there yet" : `${m.sessions_there} of this machine's listed there`;
  return `<span class="hint sessions">${esc(`${m.sessions_here} of its listed here · ${there}`)}</span>`;
}

// ---------- other machines ----------
function othersHtml(s) {
  const find = `<button class="act small" data-act="scan"${ui.scanning ? " disabled" : ""}>${ui.scanning ? WAIT : ""}Find machines</button>`;
  const add = `<form class="addhost" data-form="add"><input class="field" id="host" placeholder="Name or address, for one on Tailscale or another network" value="${esc(ui.host)}" aria-label="A machine's name or address"><button class="act small" data-act="add"${!ui.host.trim() || busy("add") ? " disabled" : ""}>${busy("add") ? WAIT : ""}Add</button></form>`;
  const mine = s.remotes.length ? `<div class="rail plain">${s.remotes.map(remoteHtml).join("")}</div>` : `<p class="empty">This machine uses no other machine's commands yet.</p>`;
  return `<section class="provider there" style="--c:var(--live)"><header><h2><span class="blot"></span>Other machines</h2>${find}</header>
    ${restartHtml()}${mine}${foundHtml(s)}${add}${saidHtml("add")}</section>`;
}
/** One machine this one uses: whether it answers, its commands here, and Update models and Remove. */
function remoteHtml(r) {
  const key = `remote:${r.name}`;
  let state;
  if (!r.token) state = `<span class="state limit">No token here: add it again to rejoin</span>`;
  else if (r.reachable === true) state = `<span class="state live"><i aria-hidden="true"></i>Answering since ${esc(when(r.since))}</span>`;
  else if (r.reachable === false) state = `<span class="state limit">Not answering since ${esc(when(r.since))}: ${esc(r.why ?? "")}</span>`;
  else state = `<span class="state mute">Not checked yet: its commands and sessions start with splice's next restart</span>`;
  const cmds = r.commands.map((c) => `<li><span class="chip">${esc(c.command)}</span><span class="model">${esc(c.model ?? "")}</span>${c.down ? `<span class="state limit">Not running there</span>` : ""}</li>`).join("");
  const acts = armed(key)
    ? `<span class="armed"><span class="said">Its commands leave this machine</span><button class="act small danger" data-act="remove" data-name="${esc(r.name)}">Remove</button><button class="act small quiet" data-act="keep">Keep</button></span>`
    : `<button class="act small" data-act="sync" data-name="${esc(r.name)}"${busy(key) ? " disabled" : ""}>${busy(key) ? WAIT : ""}Update models</button><button class="act small quiet" data-act="arm" data-key="${esc(key)}">Remove</button>`;
  const at = r.url.replace(/^https?:\/\//, "");
  return `<article class="card m" style="--c:var(${r.reachable === false || !r.token ? "--limit" : "--live"})"><div class="top"><span class="name">${esc(r.name)}</span><span class="at">${esc(at)}</span><span class="slot">${acts}</span></div>
    <p class="facts">${state}</p>${crossingHtml(r, "remotes", key)}<ul class="cmdlist">${cmds || `<li class="none">It offers no commands</li>`}</ul>${saidHtml(key)}</article>`;
}
/** The machines the last scan found that this one does not use yet, each with what it offers. */
function foundHtml(s) {
  if (data.found == null) return "";
  const fresh = data.found.filter((m) => !m.joined);
  if (!fresh.length) {
    const none = data.found.length ? "Every machine found is already in use here." : "No machine on your networks shares commands. On the machine with the logins, turn sharing on.";
    return `<p class="empty">${none}</p>`;
  }
  return `<h3 class="sect">Found on your networks</h3><div class="rail plain">${fresh.map((m) => {
    const key = `found:${m.server}`;
    const cmds = m.commands.map((c) => `<li><span class="chip">${esc(c.command)}</span><span class="model">${esc(c.model ?? "")}</span></li>`).join("");
    return `<article class="card m" style="--c:var(--track-line)"><div class="top"><span class="name">${esc(m.server)}</span><span class="at">${esc(m.address)}, splice ${esc(m.version)}</span>
      <span class="slot"><button class="act small primary" data-act="use" data-host="${esc(m.address)}" data-port="${m.port}" data-key="${esc(key)}"${busy(key) ? " disabled" : ""}>${busy(key) ? WAIT : ""}Use its commands</button></span></div>
      <ul class="cmdlist">${cmds}</ul>${saidHtml(key)}</article>`;
  }).join("")}</div>`;
}
/** The restart an act left due: once now, or armed with the sessions it would cut mid-request. */
function restartHtml() {
  if (!ui.restart) return "";
  if (ui.restart === "going") return `<p class="restart"><span class="state">${WAIT}Restarting splice to start the commands</span></p>`;
  if (ui.restart === "failed") return `<p class="restart"><span class="state limit">Not restarted</span><button class="act small" data-act="restart">Restart splice</button></p>`;
  if (ui.cut?.length) {
    return `<p class="restart">${armedAsk("Mid-request", ui.cut, '<button class="act small danger" data-act="restart-now">Restart splice</button>', '<button class="act small quiet" data-act="later">Not now</button>')}</p>`;
  }
  return `<p class="restart"><span class="state">The commands start once splice restarts</span><button class="act small" data-act="restart">Restart splice</button><button class="act small quiet" data-act="later">Later</button></p>`;
}

function render() {
  if (!data.share) {
    root.innerHTML = data.err
      ? `<p class="empty limit">${esc(data.err)}</p>`
      : data.refused
      ? `<div class="col"><p class="empty limit">splice.toml does not load, so sharing can't be read or changed here until it does.</p>${toldHtml(data.refused)}</div>`
      : `<div class="col">${GHOST.card([14, 22, 18, 26], 0)}</div><div class="col">${GHOST.card([12, 20, 24], 0)}</div>`;
    return;
  }
  const keep = document.activeElement?.id, sel = keep && document.activeElement.selectionStart;
  root.innerHTML = `<div class="col">${shareHtml(data.share)}</div><div class="col">${othersHtml(data.share)}</div>`;
  if (keep) { const f = document.getElementById(keep); if (f) { f.focus(); if (sel != null) f.setSelectionRange(sel, sel); } }
}

// ---------- acts ----------
/** PUT /api/share with [change]; the page draws what the daemon answers, or its refusal under the switch. */
async function putShare(change) {
  ui.busy = "share"; delete ui.said.share; render();
  const res = await API.put("/api/share", change);
  ui.busy = null;
  if (res.ok && res.body?.ok) { data.share = res.body.share; ui.port = null; }
  else ui.said.share = res.body?.said ?? [{ text: res.body?.error ?? "Not saved", bad: true }];
  render();
}
/** An act on a card that answers {ok, restart, said}: its lines under the card, and the restart it left due. */
async function act(key, call) {
  ui.busy = key; ui.armed = null; delete ui.said[key]; render();
  const res = await call();
  ui.busy = null;
  ui.said[key] = res.body?.said?.length ? res.body.said : res.ok && res.body?.ok ? [] : [{ text: res.body?.error ?? "Not done", bad: true }];
  if (res.body?.restart) ui.restart = "due";
  await read(); render();
  return res.ok && res.body?.ok;
}
async function scan() {
  ui.scanning = true; render();
  const res = await API.post("/api/remotes/scan", {});
  ui.scanning = false;
  data.found = res.ok ? res.body?.machines ?? [] : [];
  if (!res.ok) ui.said.add = [{ text: res.body?.error ?? "Not looked", bad: true }];
  render();
}
async function pressRestart() {
  const cut = await midRequest();
  if (!cut.length) { restart(); return; }
  ui.cut = cut; render();
}
/** The console's own restart; every read fails while splice is down, and GET /api/daemon/restart answers idle once it is back. */
async function restart() {
  ui.restart = "going"; ui.cut = null; render();
  const go = await API.post("/api/daemon/restart", {});
  if (!go.ok) { if (go.body?.error) console.warn(`sharing: restart: ${go.body.error}`); ui.restart = "failed"; render(); return; }
  for (let i = 0; i < 60; i++) {
    await new Promise((r) => setTimeout(r, 2000));
    const r = await API.get("/api/daemon/restart");
    if (r.ok && r.body?.status === "idle") { ui.restart = null; await read(); render(); return; }
  }
  ui.restart = "failed"; render();
}

root.addEventListener("input", (e) => {
  if (e.target.id === "port") { ui.port = e.target.value.trim(); render(); }
  if (e.target.id === "host") { ui.host = e.target.value; const b = root.querySelector('[data-act="add"]'); if (b) b.disabled = !ui.host.trim() || busy("add"); }
});
root.addEventListener("submit", (e) => {
  e.preventDefault();
  if (e.target.dataset.form === "add") root.querySelector('[data-act="add"]')?.click();
});
root.addEventListener("keydown", (e) => { if (e.target.id === "port" && e.key === "Enter") root.querySelector('[data-act="port"]')?.click(); });
document.addEventListener("click", async (e) => {
  const t = e.target.closest("[data-act]");
  if (!t || t.disabled || e.target.closest("a")) return;
  const sh = data.share?.sharing;
  switch (t.dataset.act) {
    case "theme": toggleTheme(); return;
    case "share": if (sh && (t.dataset.on === "1") !== sh.enabled) putShare({ enabled: t.dataset.on === "1" }); return;
    case "port": if (/^\d{1,5}$/.test(ui.port ?? "")) putShare({ port: Number(ui.port) }); return;
    case "offer": {
      const want = t.dataset.on === "1", keys = sh.commands.filter((c) => !c.why && (c.key === t.dataset.key ? want : c.offered)).map((c) => c.key);
      putShare({ heads: keys }); return;
    }
    case "copy-pin": try { await navigator.clipboard.writeText(sh.fingerprint); t.classList.add("copied"); setTimeout(() => t.classList.remove("copied"), 1200); } catch { /* no clipboard here: the title carries it whole */ } return;
    case "arm": ui.armed = t.dataset.key; render(); return;
    case "keep": ui.armed = null; render(); return;
    case "revoke": act(`machine:${t.dataset.name}`, () => API.del(`/api/share/machines/${encodeURIComponent(t.dataset.name)}`)); return;
    case "sessions": {
      if (t.getAttribute("aria-pressed") === "true") return;
      const machines = t.dataset.side === "machines", name = encodeURIComponent(t.dataset.name);
      const body = machines ? { on: t.dataset.on === "1", joined_at: t.dataset.joined } : { on: t.dataset.on === "1" };
      act(`${machines ? "machine" : "remote"}:${t.dataset.name}`, () => API.put(`/api/${machines ? "share/machines" : "remotes"}/${name}/sessions`, body));
      return;
    }
    case "remove": act(`remote:${t.dataset.name}`, () => API.del(`/api/remotes/${encodeURIComponent(t.dataset.name)}`)); return;
    case "sync": act(`remote:${t.dataset.name}`, () => API.post(`/api/remotes/${encodeURIComponent(t.dataset.name)}/sync`, {})); return;
    case "scan": scan(); return;
    case "use": {
      const ok = await act(t.dataset.key, () => API.post("/api/remotes", { host: t.dataset.host, port: Number(t.dataset.port) }));
      if (ok && data.found) { data.found = data.found.map((m) => (m.address === t.dataset.host ? { ...m, joined: true } : m)); render(); }
      return;
    }
    case "add": {
      // The daemon reads the address as `splice remote add` does: a port after a name, [v6]:port, or a bare IPv6 address.
      if (await act("add", () => API.post("/api/remotes", { host: ui.host.trim() }))) { ui.host = ""; render(); }
      return;
    }
    case "restart": pressRestart(); return;
    case "restart-now": restart(); return;
    case "later": ui.restart = null; ui.cut = null; render(); return;
    default:
  }
});

// whether api.js locked the page for a key splice does not take, as composer.js and models.js read it
const locked = () => document.body.classList.contains("locked");
setInterval(async () => {
  if (document.hidden || locked() || ui.busy || ui.restart === "going") return;
  await read(); render();
}, 5000);
(async () => { render(); await read(); render(); })();

});
