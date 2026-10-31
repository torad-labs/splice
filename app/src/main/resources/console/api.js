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
  // one way back, `splice console` with Copy, which signs this browser in again whatever lost it.
  // THE LOCK SHOWS WHAT IS HAPPENING, IN ORDER (Oct 29): the lock and its state, a ring turning beside "Waiting for sign-in"
  // while no key has arrived, the command to run with Copy, and the three things that follow it as three cards in a row, so
  // the page needs no sentence. Copy says what it did beside itself (DRAW.result) instead of renaming the button, and stays
  // pressable after a failure. The moment this browser's key is kept (the tab the command opens trades its code and stores
  // the key, which every other tab of this browser hears as a storage event) the lock lifts and the page reads again where it
  // stood, with no reload.
  const GLYPH = (d) => `<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">${d}</svg>`;
  const LOCK_PATH = '<rect x="5" y="10.8" width="14" height="9.7" rx="2.2"/><path d="M8.2 10.8V8a3.8 3.8 0 0 1 7.6 0v2.8"/>';
  const STEP_ICON = {
    run: GLYPH('<rect x="3" y="4.5" width="18" height="15" rx="2.2"/><path d="M7.5 9.5l3 2.5-3 2.5M12.8 14.8h4"/>'),
    tab: GLYPH('<rect x="3" y="5" width="18" height="14" rx="2"/><path d="M3 9.5h18M7 5v4.5"/>'),
    lift: GLYPH('<rect x="5" y="10.8" width="14" height="9.7" rx="2.2"/><path d="M8.2 10.8V7.6a3.8 3.8 0 0 1 7.6 0"/>'),
  };
  function unlock(box) {
    box.remove();
    document.body.classList.remove("locked");
    if (typeof CONSOLE !== "undefined") CONSOLE.navigate(location.href, { restore: true }); else location.reload();
  }
  function lock() {
    const main = document.querySelector("main.page");
    if (!main || main.querySelector(".lockedby")) return;
    document.body.classList.add("locked");
    const box = document.createElement("section");
    box.className = "lockedby";
    box.setAttribute("role", "alert");
    // one row across the plate: the lock with its word and what it waits on, then the three steps joined by dashed arrows
    box.innerHTML = `<div class="shl-hero"><svg class="shl-ring" viewBox="0 0 120 120" aria-hidden="true"><circle class="shl-r0" cx="60" cy="60" r="56"/><circle class="shl-arc" cx="60" cy="60" r="56"/>`
      + `<g class="shl-glyph" transform="translate(60 60) scale(3.6) translate(-12 -12)">${LOCK_PATH}</g></svg>`
      + `<div><p class="shl-word">Locked</p><p class="shl-wait">Waiting for sign-in</p></div></div>`
      + `<div class="shl-path"><div class="shl-node"><div class="way shl-row">${STEP_ICON.run}<p class="kicker">Run in a terminal</p><code>splice console</code>`
      // the one act left on the page stands at the console's own act height, never the small one
      + `<button class="act quiet" type="button">Copy</button></div></div><span class="shl-link" aria-hidden="true"></span>`
      + `<div class="shl-node"><div class="shl-row">${STEP_ICON.tab}<p class="kicker">A tab opens</p></div><p class="shl-say">Signs this browser in</p></div><span class="shl-link" aria-hidden="true"></span>`
      + `<div class="shl-node"><div class="shl-row">${STEP_ICON.lift}<p class="kicker">The lock lifts</p></div><p class="shl-say pending">Page reads again</p></div></div>`;
    const copy = box.querySelector("button"), way = box.querySelector(".way");
    copy.addEventListener("click", async (e) => {
      e.stopPropagation();
      let said = DRAW.result("Copied", "good");
      // one phrase for a copy that did not land, the one the request pane uses
      try { await navigator.clipboard.writeText("splice console"); } catch { said = DRAW.result("Could not copy", "bad"); }
      way.querySelector(".result")?.remove();
      copy.insertAdjacentHTML("afterend", said);
      setTimeout(() => way.querySelector(".result")?.remove(), 1600);
    });
    (main.querySelector(":scope > .head") || main.firstChild)?.after(box);
    addEventListener("storage", function lifted(e) {
      if (e.key !== STORE || !e.newValue) return;
      removeEventListener("storage", lifted);
      unlock(box);
    });
  }
  // Calls a page has in flight, so the manager's acts (pilot.js) can wait for what a press started before saying what
  // it did. The pilot's own calls are not counted: its ask is always open.
  let pending = 0;
  // WHAT THE SHELL READS, IT READS ONCE WITH THE PAGE (Oct 27). The top bar's links carry what waits behind them
  // (kit.js markNav), which takes the sessions and the commands' live turns, and the page beside it often has just
  // read the same. The newest answer to each of those reads is kept here, and any of them landing is announced, so the
  // bar redraws off the page's own read the moment it lands, and reads for itself only when no page has lately.
  const KEPT = /^\/api\/(?:sessions|heads|heads\/[^/]+\/turns\/live)$/;
  const heard = new Map();
  function hear(path, answer) {
    if (!answer.ok || !KEPT.test(path)) return;
    heard.set(path, { answer, at: Date.now() });
    dispatchEvent(new CustomEvent("splice:heard", { detail: path }));
  }
  /** The newest answer to [path] if one landed within [ms], else a fresh read of it. */
  async function recent(path, ms) {
    const kept = heard.get(path);
    if (kept && Date.now() - kept.at < ms) return kept.answer;
    return call("GET", path);
  }
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
      const answer = { ok: res.ok, status: res.status, body: parsed };
      if (method === "GET") hear(path, answer);
      if (method === "GET" && res.ok && path === "/api/accounts" && typeof DRAW !== "undefined") DRAW.loginRoster(parsed?.accounts);
      return answer;
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
    recent,
  });
  return client();
})();
