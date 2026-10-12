// Usage › How each command answered: the per-command performance table (v0.4.0 spec §3). Every figure is read from
// GET /api/perf/summary?window=1h|24h|7d, which the daemon folds over every request each command recorded, so the table
// ignores the page's project pick and filters, and says so when one is set. Nothing is invented: a figure the summary
// does not carry reads "Not reported" or "Not measured", never a zero.
//
// Mounted by usage.js: render() draws PERF_HEADS.html() after the Breakdown and Commands row. This file reads the page's
// own helpers at draw time (chip, cmdOf, secs, n0, failShare, OUTCOME_WORD, tagWord, isFailed, GHOST, esc).
"use strict";

const PERF_HEADS = (() => {
  const WINDOWS = { "1h": "1 hour", "24h": "24 hours", "7d": "7 days" };
  const st = { win: "24h", body: null, error: null, reading: false, gen: 0 };

  async function read() {
    const gen = ++st.gen;
    st.reading = true;
    const r = await API.get(`/api/perf/summary?window=${st.win}`);
    if (gen !== st.gen) return; // a newer window was asked for while this one was read
    st.reading = false;
    st.body = r.ok ? r.body : null;
    st.error = r.ok ? null : r.status === 502 || !r.status ? "the connection to splice dropped before it answered" : "splice gave no reason";
    render();
  }

  const pct = (x) => (x == null ? null : x > 0 && x < 0.005 ? "under 1%" : `${Math.round(x * 100)}%`);
  const cell = (label, value, cls = "") => `<span class="pf${cls ? ` ${cls}` : ""}"><em>${label}</em>${value}</span>`;
  const none = (word) => `<i class="nr">${word}</i>`;
  const time = (stats) => (stats?.p50 == null ? none("Not measured") : `${secs(stats.p50)}<span class="p95">${secs(stats.p95)}</span>`);
  const hours = (ms) => (ms >= 3600e3 ? `${Math.floor(ms / 3600e3)} h` : `${Math.max(1, Math.floor(ms / 60e3))} min`);

  /** Failures by kind, the largest share first, in the words every page uses for an outcome. */
  function kinds(h) {
    const shares = Object.entries(h.failure_shares || {}).filter(([, s]) => s > 0).sort((a, b) => b[1] - a[1]);
    return shares.map(([tag, s]) => `<span class="k"><b>${pct(s)}</b> ${esc(countWord(tag) || OUTCOME_WORD[tag] || tagWord(tag) || "Failed")}</span>`).join("");
  }
  /** What the record behind a row could not cover, in a sentence: a short file, an unread part, unreadable lines. */
  function gaps(h) {
    const out = [];
    if (h.read_error || h.coverage_known === false) out.push("Part of this command's record could not be read");
    else if (h.clamped) out.push(`The record holds only the last ${hours(h.covers_ms)}`);
    if (h.skipped_lines) out.push(`${n0(h.skipped_lines)} unreadable ${h.skipped_lines === 1 ? "line" : "lines"} skipped`);
    return out;
  }
  function row(h) {
    const failedN = Object.entries(h.outcomes || {}).reduce((a, [tag, n]) => a + (isFailed(tag) ? n : 0), 0);
    const failed = failedN ? `<b class="bad">${n0(failedN)}</b><span class="sh">${pct(h.failure_share)}</span>` : "0";
    const drops = h.io_drops_in_window > 0 ? `at least ${n0(h.io_drops_in_window)}` : "None";
    const below = [kinds(h), ...gaps(h).map((g) => `<span class="gap">${esc(g)}</span>`)].join("");
    return `<div class="prow" role="row" style="--c:${colorOf(providerOfHead(h.key))}">`
      + `<span class="pf cmd">${chip(h.key)}</span>`
      + cell("Requests", n0(h.count))
      + cell("Failed", failed)
      + cell("First byte", time(h.time_before_first_byte_ms), "t")
      + cell("Streaming", time(h.time_streaming_ms), "t")
      + cell("Retries", n0(h.retries ?? 0))
      + cell("Sign-ins renewed", n0(h.refreshes ?? 0))
      + cell("Cache hit", pct(h.cache_hit_ratio) ?? none("Not reported"))
      + cell("Cold starts", h.cache_cold == null ? none("Not reported") : n0(h.cache_cold))
      + cell("Most at once", h.peak_inflight == null ? none("Not reported") : n0(h.peak_inflight))
      + cell("Records dropped", drops)
      + (below ? `<p class="below">${below}</p>` : "")
      + `</div>`;
  }
  const HEAD = ["Requests", "Failed", "First byte", "Streaming", "Retries", "Sign-ins renewed", "Cache hit", "Cold starts", "Most at once", "Records dropped"];

  function html() {
    if (!st.body && !st.error && !st.reading) read();
    const sw = `<div class="switch" role="group" aria-label="Performance window">${Object.entries(WINDOWS).map(([k, v]) => `<button data-act="perfwin" data-v="${k}" aria-pressed="${st.win === k}">${v}</button>`).join("")}</div>`;
    const scoped = ui.project != null || hasFilter() ? `<span class="hn">Every request, whatever is picked above</span>` : "";
    const header = `<header><h2>How each command answered</h2>${scoped}${sw}</header>`;
    if (st.error) return `<section class="perf-heads">${header}<p class="empty">${NOT_READ}: ${esc(st.error)}</p></section>`;
    if (!st.body) return `<section class="perf-heads" aria-busy="true">${header}${GHOST.card([12, 30, 26, 22])}</section>`;
    const heads = st.body.heads || [];
    const used = heads.filter((h) => h.count > 0), idle = heads.filter((h) => !(h.count > 0));
    const table = used.length
      ? `<div class="ptable card" role="table"><div class="prow pthead" role="row"><span class="pf cmd">Command</span>${HEAD.map((l) => `<span class="pf">${l}</span>`).join("")}</div>${used.map(row).join("")}</div>`
      : `<p class="empty">No requests in the last ${WINDOWS[st.win]}</p>`;
    const quiet = used.length && idle.length ? `<p class="quiet-heads"><span>No requests in the last ${WINDOWS[st.win]}</span>${idle.map((h) => chip(h.key)).join("")}</p>` : "";
    const key = used.length ? `<p class="pkey">First byte and Streaming read typical, then the slowest 5% in grey.</p>` : "";
    return `<section class="perf-heads">${header}${table}${key}${quiet}</section>`;
  }

  document.addEventListener("click", (e) => {
    const el = e.target.closest("[data-act=perfwin]");
    if (!el || el.dataset.v === st.win) return;
    st.win = el.dataset.v; st.body = null; st.error = null; st.reading = false;
    render();
  });
  return { html };
})();
