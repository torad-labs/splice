// What every console page shares to reach splice: the console key, kept in this browser, and one call that sends it.
// `splice console` hands a page a single-use code in the address fragment (#c=…, which never reaches the daemon or a
// log). The page takes it out of the address before anything else and trades it once, in a POST body, for the console
// key (Oct 26, 2026), so an address that a history, a log or a browser tool keeps holds a code that is spent or expired
// and opens nothing. Until then the address carried the management key itself (#k=…), and on Sep 29 a browser tool
// printed one into a conversation that went to a model provider: such an address is still cleared, and its key is
// never kept or sent. A browser signed in before then kept that management key under the old name, and would send it on
// every call until a trade replaced it: it is removed as the page loads, so the console key alone is ever kept or sent.
"use strict";

const API = (() => {
  const STORE = "splice-console-access-key";
  localStorage.removeItem("splice-console-key"); // where a page before Oct 26 kept the management key itself
  const handed = /^#([ck])=([^&/]*)$/.exec(location.hash);
  if (handed) history.replaceState(history.state, "", `${location.pathname}${location.search}`); // not left in view or history
  // Every call waits for the trade, so none goes out keyless, or with an older key, while the page is being signed in.
  const signedIn = handed?.[1] === "c" ? trade(handed[2]) : Promise.resolve();
  async function trade(code) {
    try {
      const res = await fetch("/api/console/key", {
        method: "POST", cache: "no-store", headers: { "Content-Type": "application/json" }, body: JSON.stringify({ code }),
      });
      const key = res.ok ? (await res.json())?.key : null;
      if (typeof key === "string" && key) localStorage.setItem(STORE, key);
    } catch {
      // A daemon that did not answer signs nothing in, as a refused code does: the key this browser held stays.
    }
  }
  // A 401 means this browser holds no key splice takes: every page is locked the same way, its body set aside for the
  // one way back, `splice console` with Copy, which signs this browser in again whatever lost it (fin, Marlin, Oct 12).
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
    const counted = !path.startsWith("/api/console/pilot");
    if (counted) pending++;
    try {
      await signedIn;
      const init = { method, signal, keepalive, headers: { Authorization: `Bearer ${localStorage.getItem(STORE) || ""}`, ...extra } };
      if (body !== undefined) { init.headers["Content-Type"] = "application/json"; init.body = JSON.stringify(body); }
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
    put: (p, b, extra) => call("PUT", p, b, extra, signal),
    patch: (p, b) => call("PATCH", p, b, undefined, signal),
    del: (p) => call("DELETE", p, undefined, undefined, signal),
    // a last word as the tab hides or closes: the browser still delivers it after the page is gone (pilot.js)
    parting: (p) => call("POST", p, {}, undefined, undefined, true),
    scoped: (nextSignal) => client(signal ? AbortSignal.any([signal, nextSignal]) : nextSignal),
  });
  return client();
})();
