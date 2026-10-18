// What every console page shares to reach splice: the management key, handed over once by `splice console` in the
// address fragment (#k=…, which never reaches the daemon or a log) and kept in this browser, and one call that sends it.
"use strict";

const API = (() => {
  const STORE = "splice-console-key";
  const handed = /^#k=([^&/]+)$/.exec(location.hash);
  if (handed) {
    localStorage.setItem(STORE, decodeURIComponent(handed[1]));
    history.replaceState(history.state, "", `${location.pathname}${location.search}`); // not left in view or history
  }
  // A 401 means this browser holds no key splice takes: every page is locked the same way, its body set aside for the
  // one way back, `splice console` with Copy, which hands the key over again whatever lost it (fin, Marlin, Oct 12).
  function lock() {
    const main = document.querySelector("main.page");
    if (!main || main.querySelector(".lockedby")) return;
    document.body.classList.add("locked");
    const box = document.createElement("section");
    box.className = "lockedby";
    box.setAttribute("role", "alert");
    box.innerHTML = `<p class="word">Locked</p><div class="way"><code>splice console</code><button class="act quiet small" type="button">Copy</button></div>`;
    const copy = box.querySelector("button");
    copy.addEventListener("click", async (e) => {
      e.stopPropagation();
      try { await navigator.clipboard.writeText("splice console"); copy.textContent = "Copied"; } catch { copy.textContent = "Copy"; }
      setTimeout(() => { copy.textContent = "Copy"; }, 1600);
    });
    (main.querySelector(":scope > .head") || main.firstChild)?.after(box);
  }
  // Calls a page has in flight, so the manager's acts (pilot.js) can wait for what a press started before saying what
  // it did. The pilot's own calls are not counted: its ask is always open.
  let pending = 0;
  // Every answer comes back as { ok, status, body }; a refused or unreachable call is an answer too, never a throw.
  async function call(method, path, body, extra, signal, keepalive) {
    const init = { method, signal, keepalive, headers: { Authorization: `Bearer ${localStorage.getItem(STORE) || ""}`, ...extra } };
    if (body !== undefined) { init.headers["Content-Type"] = "application/json"; init.body = JSON.stringify(body); }
    const counted = !path.startsWith("/api/console/pilot");
    if (counted) pending++;
    try {
      const res = await fetch(path, init);
      const text = await res.text();
      let parsed = null;
      try { parsed = text ? JSON.parse(text) : null; } catch { parsed = null; }
      if (res.status === 401 && !signal?.aborted) lock();
      return { ok: res.ok, status: res.status, body: parsed };
    } catch {
      return { ok: false, status: 0, body: null };
    } finally {
      if (counted) pending--;
    }
  }
  const client = (signal) => ({
    pending: () => pending,
    get: (p) => call("GET", p, undefined, undefined, signal),
    // a read that answers at once with what is ready, marking what splice is still reading, rather than waiting on it
    getReady: (p) => call("GET", p, undefined, { "x-splice-read-pending": "1" }, signal),
    post: (p, b) => call("POST", p, b ?? {}, undefined, signal),
    put: (p, b) => call("PUT", p, b, undefined, signal),
    patch: (p, b) => call("PATCH", p, b, undefined, signal),
    del: (p) => call("DELETE", p, undefined, undefined, signal),
    // a last word as the tab hides or closes: the browser still delivers it after the page is gone (pilot.js)
    parting: (p) => call("POST", p, {}, undefined, undefined, true),
    scoped: (nextSignal) => client(signal ? AbortSignal.any([signal, nextSignal]) : nextSignal),
  });
  return client();
})();
