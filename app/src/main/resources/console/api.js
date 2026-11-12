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
  // All console performance calls share the daemon's two-running/two-waiting admission budget.
  // Keep sent calls in their slots until the response body drains: aborting fetch is not proof the
  // server released its admission. A stale generation may stop queued work and ignore sent results.
  const PERF_ACTIVE = 4, PERF_WAITING = 32, perfWaiting = new Map();
  let perfRunning = 0;
  const localPerfBusy = () => ({ ok: false, status: 429, body: { status: "busy", reason: "console_query_capacity" } });
  const requestsOwn = (key) => key.startsWith("requests:");
  // A measurement a person just asked for goes ahead of Requests' own counts. Over 30 days that page lines up one count
  // per project and command (88 live on Nov 10), each taking seconds, and the Performance panel's three reads behind
  // them were refused here unsent and drawn as "Error" for two minutes while the page was only still reading.
  function nextPerf() {
    for (const entry of perfWaiting) if (!requestsOwn(entry[0])) return entry;
    return perfWaiting.entries().next().value;
  }
  const panelWaiting = () => { let n = 0; for (const key of perfWaiting.keys()) if (!requestsOwn(key)) n++; return n; };
  function drainPerf() {
    while (perfRunning < PERF_ACTIVE && perfWaiting.size) {
      const [key, next] = nextPerf();
      perfWaiting.delete(key);
      if (!next.current()) { next.resolve(null); continue; }
      perfRunning++;
      Promise.resolve().then(() => /^BLOB$/i.test(next.method)
        ? client(undefined, next.current).blob(next.path, next.body)
        : call(next.method, next.path, next.body,
          next.ready ? { "x-splice-read-pending": "1" } : undefined,
          undefined, undefined, next.current)).then(next.resolve, next.reject)
        .finally(() => { perfRunning--; drainPerf(); });
    }
  }
  function perfRequest(key, method, path, body, current = () => true, options = {}) {
    if (typeof key !== "string" || !key.startsWith("perf:") && !key.startsWith("requests:")) {
      return Promise.reject(new Error("performance request key is invalid"));
    }
    return new Promise((resolve, reject) => {
      const pendingRead = perfWaiting.get(key);
      if (pendingRead && options.replace === false) {
        reject(new Error("Another performance action is pending. Wait for it to finish."));
        return;
      }
      pendingRead?.resolve(null);
      // Requests' own reads wait their turn rather than be refused here: its project tiles alone ask one count per project
      // and command at once (14 projects by 11 commands live on Nov 10), and every read past the 32nd came back as a
      // local refusal that drew the tile as a dash. They still go four at a time, inside the daemon's admission. Nor do
      // they count against another panel's reads: only a panel's own backlog refuses a panel's read.
      if (!pendingRead && !requestsOwn(key) && perfRunning >= PERF_ACTIVE && panelWaiting() >= PERF_WAITING) {
        resolve(localPerfBusy());
        return;
      }
      perfWaiting.set(key, { method, path, body, current, ready: options.ready === true, resolve, reject });
      drainPerf();
    });
  }
  function cancelPerf(prefix) {
    for (const [key, pendingRead] of perfWaiting) {
      if (!key.startsWith(prefix) || !/^GET$/i.test(pendingRead.method) || pendingRead.current()) continue;
      perfWaiting.delete(key);
      pendingRead.resolve(null);
    }
  }
  // Every answer comes back as { ok, status, body }; a refused or unreachable call is an answer too, never a throw.
  async function call(method, path, body, extra, signal, keepalive, maySend = () => true) {
    const counted = !path.startsWith("/api/console/pilot");
    if (counted) pending++;
    try {
      await signedIn;
      if (!maySend()) return { ok: false, status: 0, body: null };
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
  // Capacity refusals are retried only after their caller's whole batch settles. The 15-second
  // window gates new sends; a sent request keeps its queue slot until the normal HTTP response drains.
  async function retryCapacity(refusals, current, resend, parentSignal, inheritedSignal, phase) {
    const pending = refusals.filter((item) => item?.answer?.status === 429 && item.answer.body?.reason === "control_query_capacity");
    if (!pending.length) return [];
    let windowOpen = true;
    const timer = phase ? null : setTimeout(() => { windowOpen = false; }, 15000);
    phase?.start();
    const canStart = () => current() && !inheritedSignal?.aborted && !parentSignal?.aborted
      && (phase ? phase.open() : windowOpen);
    const retryClient = client(undefined, canStart);
    const completed = [];
    try {
      for (const item of pending) {
        if (!canStart()) break;
        let answer;
        try { answer = await resend(item, retryClient, canStart); } catch { break; }
        if (!current() || inheritedSignal?.aborted || parentSignal?.aborted) break;
        if (!Number.isInteger(answer?.status) || answer.status <= 0) break;
        completed.push({ item, answer });
      }
      return completed;
    } finally {
      if (timer) clearTimeout(timer);
    }
  }
  // Artifact callers can opt into fixed machine refusal codes. Never return the error document:
  // it can contain paths or credentials, and even an error stream must have a small read bound.
  async function blobRefusal(res, options) {
    const allowed = options?.refusalReasons;
    if (!Array.isArray(allowed) || !allowed.length || res.headers.get("Content-Type")?.split(";")[0].trim().toLowerCase() !== "application/json" || !res.body) return null;
    const reader = res.body.getReader(), bytes = new Uint8Array(4096); let size = 0;
    try {
      for (;;) {
        const { done, value } = await reader.read();
        if (done) break;
        if (value.byteLength > bytes.length - size) { await reader.cancel(); return null; }
        bytes.set(value, size); size += value.byteLength;
      }
      const reason = JSON.parse(new TextDecoder().decode(bytes.subarray(0, size)))?.reason;
      return typeof reason === "string" && /^[a-z][a-z0-9_]{0,63}$/.test(reason) && allowed.slice(0, 16).includes(reason) ? { reason } : null;
    } catch { return null; }
    finally { reader.releaseLock(); }
  }
  const client = (signal, maySend = () => true) => ({
    pending: () => pending,
      get: (p) => call("GET", p, undefined, undefined, signal, undefined, maySend),
    // Managed profile artifacts use the same bearer guard; never put credentials into a download URL.
    async blob(path, options) {
      pending++;
      try {
        await signedIn;
        if (!maySend()) return { ok: false, status: 0, body: null };
        const res = await fetch(path, { signal, headers: { Authorization: `Bearer ${localStorage.getItem(STORE) || ""}` } });
        if (res.status === 401 && !signal?.aborted) lock();
        return { ok: res.ok, status: res.status, body: res.ok ? await res.blob() : await blobRefusal(res, options) };
      } catch { return { ok: false, status: 0, body: null }; }
      finally { pending--; }
    },
    // a read that answers at once with what is ready, marking what splice is still reading, rather than waiting on it
    getReady: (p) => call("GET", p, undefined, { "x-splice-read-pending": "1" }, signal, undefined, maySend),
    perfRequest: (key, method, path, body, current = () => true, options = {}) =>
      perfRequest(key, method, path, body, () => !signal?.aborted && maySend() && current(), options),
    cancelPerf,
    post: (p, b) => call("POST", p, b ?? {}, undefined, signal, undefined, maySend),
    put: (p, b, extra) => call("PUT", p, b, extra, signal, undefined, maySend),
    patch: (p, b) => call("PATCH", p, b, undefined, signal, undefined, maySend),
    del: (p) => call("DELETE", p, undefined, undefined, signal, undefined, maySend),
    // a last word as the tab hides or closes: the browser still delivers it after the page is gone (pilot.js)
    parting: (p) => call("POST", p, {}, undefined, undefined, true),
    scoped: (nextSignal) => client(signal ? AbortSignal.any([signal, nextSignal]) : nextSignal, maySend),
    retryCapacity: (refusals, current, resend, parentSignal, phase) => retryCapacity(refusals, current, resend, parentSignal, signal, phase),
    recent,
  });
  return client();
})();

// ---------- one sign-in, as splice holds it ----------
// A sign-in is splice's: splice starts it, holds it and ends it, and a page shows the one splice holds. Accounts, Usage,
// Models and Compare each carried their own copy of these steps, and the copies drifted. On Nov 11, 2026 Cancel was
// mended on Accounts while three pages still only forgot the sign-in; Models and Compare went on drawing "Signing in"
// for five minutes over a sign-in a restarted splice no longer held; and only Accounts said in what way one had
// failed (Marcos: "TRhe whol state machine system for login is broken"). The steps are here once. A page brings what
// is its own: where its sign-in starts (how), the object it draws from (s), and what it does as the sign-in changes (on).
//   how: { start: the POST that begins it, body, poll: the head its status is read under, native: Claude Code's own
//          sign-in, which takes a pasted code; an add's sign-in is carried by its add instead: watch, the GET that
//          answers it as sign_in, and end, the DELETE that ends it }
//   s:   { how, state: "wait" | "ending" | "fail", id, url, code, by: "code" | "paste" | "browser", swapped, fail, why }
//   on:  { held(): the page still shows this sign-in, draw(), done(status), failed(status) }
const SIGNIN = (() => {
  // A failed sign-in says its kind in a word or two where it was pressed, with splice's own sentence beside the word:
  // a bare red "In use" on a Sign in left him nothing to act on (Oct 27). splice names each failure's kind
  // (failure_kind, ConsoleAccounts.kt LoginFailure); a kind it doesn't name reads Not completed. A file or config
  // failure fails again until it's fixed, so it has no Try again.
  const FAILS = {
    not_completed: { word: "Not completed", retry: true }, network: { word: "Network error", retry: true },
    file: { word: "File error" }, config: { word: "Config error" }, expired: { word: "Expired", retry: true },
    stopped: { word: "Stopped", retry: true }, cancelled: { word: "Cancelled", retry: true },
    already_added: { word: "Already added", pulse: true }, other_account: { word: "Other account", retry: true },
  };
  // splice holds no sign-in under this id: it restarted since, and the sign-in ended with the daemon that ran it
  const STOPPED = { failure_kind: "stopped", failure_reason: "splice restarted, and this sign-in ended with it" };
  const NOT_CANCELLED = "splice did not answer, so the sign-in may still be open. Try again starts a new one in its place.";
  // one sign-in on splice: read with GET, ended with DELETE (LoginRoutes.kt)
  const at = (s, id) => `/api/auth/${encodeURIComponent(s.how.poll)}/login/${encodeURIComponent(id)}`;
  const endPath = (s, id) => (s.how.watch ? s.how.end || null : id && s.how.poll ? at(s, id) : null);
  // An add's answer carries its sign-in inside it (AddViews.kt:63); a command's sign-in answers as itself.
  const read = (s, body) => (s.how.watch ? body?.sign_in || {} : body);
  const quiet = (s) => clearTimeout(s?.timer);
  // A sign-in the page stopped showing without ending it (its card, pane or form went) ends on splice too, so none
  // stays open there with nobody to finish or cancel it. Nobody waits on that answer.
  function gone(s, on, gen) {
    if (on.held() && s.gen === gen) return false;
    if (s.gen === gen && s.state === "wait") leave(s);
    return true;
  }
  function leave(s, id = s.id) {
    quiet(s);
    const path = s.state === "wait" ? endPath(s, id) : null;
    Object.assign(s, { state: "left", gen: (s.gen || 0) + 1 });
    if (path) API.del(path);
  }
  /** Starts the sign-in, or starts it again in place after one that failed. */
  async function start(s, on) {
    quiet(s);
    Object.assign(s, { state: "wait", by: s.how.native ? "paste" : "browser", code: null, url: null, id: null, fail: null, why: "", gen: (s.gen || 0) + 1 });
    on.draw();
    const gen = s.gen, res = await API.post(s.how.start, s.how.body || {});
    if (!on.held() || s.gen !== gen) {
      // Let go of while splice was starting it: what splice started ends too. One started again in place ends a
      // command's earlier sign-in by its id, and keeps an add's, which holds one sign-in and it is now this start's.
      const kept = s.how.watch && on.held() && s.state === "wait";
      const path = res.ok && !kept ? endPath(s, res.body?.id) : null;
      if (path) API.del(path);
      return;
    }
    if (!res.ok || !(s.how.watch || res.body?.id)) { fail(s, on, res.body); return; }
    s.id = res.body?.id ?? null;
    seen(s, on, read(s, res.body));
  }
  /** Takes up a sign-in splice already holds (GET /api/auth/logins), as a page opened after it began. */
  function adopt(s, on, status) {
    Object.assign(s, { state: "wait", by: s.how.native ? "paste" : "browser", code: null, url: null, id: status.id, gen: (s.gen || 0) + 1 });
    seen(s, on, status);
  }
  // one status answer: show its step, land, fail, or ask again in a second
  function seen(s, on, st) {
    if (s.state !== "wait") return;
    if (st?.state === "failed") { fail(s, on, st); return; }
    if (st?.state === "signed_in" || st?.state === "live_after_restart") { quiet(s); s.state = "done"; on.done(st); return; }
    const url = st?.verification_uri || st?.browser_url || null, code = st?.user_code || null;
    // The step is splice's to say: a code to type on the provider's page, a code that page shows to paste back here
    // (paste_code: Claude Code's own sign-in, whichever card started it), or a page that finishes by itself. Until
    // Nov 11, 2026 the page decided the paste step from which card was pressed, and only a command's own Claude Code
    // login had the field: an added Claude login signed in again, and + Add account, opened the same page and left
    // its code with nowhere to go. An add's sign-in takes no code (code, below).
    const paste = !s.how.watch && (st?.paste_code || s.how.native);
    const by = code ? "code" : paste ? "paste" : "browser";
    const changed = url !== s.url || by !== s.by || code !== s.code;
    s.swapped = !!(s.code && code && code !== s.code); // a device code that expired is replaced (DeviceLoginFlow.kt:88-93)
    Object.assign(s, { url, by, code });
    if (changed) { on.draw(); s.swapped = false; }
    ask(s, on);
  }
  // Asks splice how the sign-in stands, a second from now. Only splice's answer ends it here: a read splice does not
  // answer keeps the page waiting, and a splice that restarted, and so knows no such sign-in, says that. Until Nov 11,
  // 2026 one read that failed ended it on the card as "Not completed" with no reason, while splice went on holding it.
  function ask(s, on) {
    // one chain of reads: an ask made while a read is out (a pasted code's answer) takes over from it
    const gen = s.gen, turn = s.turn = (s.turn || 0) + 1;
    quiet(s);
    s.timer = setTimeout(async () => {
      if (gone(s, on, gen)) return;
      const res = await API.get(s.how.watch || at(s, s.id));
      if (gone(s, on, gen) || s.turn !== turn) return;
      if (res.ok) { seen(s, on, read(s, res.body)); return; }
      if (res.status === 404) { fail(s, on, STOPPED); return; }
      ask(s, on);
    }, 1000);
  }
  /** Ends the sign-in here as failed, in splice's words: its status, or the answer of a call splice refused. */
  function fail(s, on, st = null) {
    quiet(s);
    const why = st?.failure_reason ?? st?.error;
    Object.assign(s, { state: "fail", fail: FAILS[st?.failure_kind] || FAILS.not_completed, why: typeof why === "string" ? why.trim() : "" });
    on.failed(st);
  }
  /** Cancel. The sign-in ends on splice, and the page lets go of it only once splice has answered: true when it is over
   *  there (one that already failed, or that splice never knew, is over), false when splice did not answer, with the
   *  sign-in left as "Not cancelled" for the page to draw. Until Nov 11, 2026 Cancel only forgot the sign-in in the
   *  page (Marcos: "it said it canceled ... so it didnt really cancelled"). */
  async function cancel(s, on) {
    if (s.state === "ending") return false;
    quiet(s);
    const path = s.state === "wait" ? endPath(s, s.id) : null;
    // a start still on its way is ended when it answers (start)
    Object.assign(s, { state: path ? "ending" : "left", gen: (s.gen || 0) + 1 });
    if (!path) return true;
    on.draw();
    const res = await API.del(path);
    if (res.ok || res.status === 404) { s.state = "left"; return true; }
    Object.assign(s, { state: "fail", fail: { word: "Not cancelled", retry: true }, why: NOT_CANCELLED });
    if (on.held()) on.failed(null);
    return false;
  }
  /** A code pasted back. One splice does not take ends the sign-in there too, with splice's own reason here, so the
   *  page and splice agree it is over and Try again starts a new one. */
  async function code(s, on, text) {
    if (!s.id || s.how.watch || s.state !== "wait") return;
    const gen = s.gen, res = await API.post(`${at(s, s.id)}/code`, { code: text });
    if (gone(s, on, gen)) return;
    if (!res.ok) { API.del(at(s, s.id)); fail(s, on, res.body); return; }
    seen(s, on, res.body);
  }
  return { FAILS, start, adopt, cancel, code, fail, quiet };
})();
