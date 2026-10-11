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
  // Every answer comes back as { ok, status, body }; a refused or unreachable call is an answer too, never a throw.
  async function call(method, path, body) {
    const init = { method, headers: { Authorization: `Bearer ${localStorage.getItem(STORE) || ""}` } };
    if (body !== undefined) { init.headers["Content-Type"] = "application/json"; init.body = JSON.stringify(body); }
    try {
      const res = await fetch(path, init);
      const text = await res.text();
      let parsed = null;
      try { parsed = text ? JSON.parse(text) : null; } catch { parsed = null; }
      return { ok: res.ok, status: res.status, body: parsed };
    } catch {
      return { ok: false, status: 0, body: null };
    }
  }
  return {
    get: (p) => call("GET", p),
    post: (p, b) => call("POST", p, b ?? {}),
    put: (p, b) => call("PUT", p, b),
    patch: (p, b) => call("PATCH", p, b),
    del: (p) => call("DELETE", p),
  };
})();
