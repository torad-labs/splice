// Usage › How each command answered: the per-command table (v0.4.0 spec §3, its Resolved block), in the place the
// Commands cards held, so each command is said once (marlin, Oct 12). Every figure is read from
// GET /api/perf/summary?window=1h|24h|7d, which the daemon folds over every request each command recorded, so the table
// ignores the page's project pick and filters and says "All requests" when one is set. Nothing is invented: a figure the
// summary does not carry reads "Not reported" or "Not measured", never a zero, and a column no command has a figure for
// is left out (marlin). Every count opens its requests on Requests, in the table's window and with no pick: the
// command's requests, its failures, and each kind of failure by its outcome tag.
//
// Mounted by usage.js: render() draws PERF_HEADS.html() beside the Breakdown. This file reads the page's own helpers at
// draw time (chip, colorOf, providerOfHead, cmdOf, flagOf, flagHtml, hasFilter, ui, n0, secs, OPEN, OUTCOME_WORD,
// tagWord, countWord, isFailed, GHOST, esc).
"use strict";

const PERF_HEADS = (() => {
  const WINDOWS = { "1h": "1 hour", "24h": "24 hours", "7d": "7 days" };
  const st = { win: "24h", body: null, error: null, reading: false, gen: 0, warned: new Set() };

  async function read() {
    const gen = ++st.gen;
    st.reading = true;
    const r = await API.get(`/api/perf/summary?window=${st.win}`);
    if (gen !== st.gen) return; // a newer window was asked for while this one was read
    st.reading = false;
    st.body = r.ok ? r.body : null;
    st.error = r.ok ? null : !r.status || r.status === 502 ? "Not answering" : NOT_READ;
    if (!r.ok) console.warn(`perf summary: ${r.status}`);
    render();
  }

  // Requests in the table's window, every project, for one command and what else names the door
  const door = (key, extra = {}) => `requests.html?${new URLSearchParams({ win: st.win, project: "", cmd: cmdOf(key), ...extra })}`;
  const pct = (x) => (x == null ? null : x > 0 && x < 0.005 ? "under 1%" : `${Math.round(x * 100)}%`);
  const none = (word) => `<i class="nr">${word}</i>`;
  const KEY = `<small class="tkey">Typical <i>Slowest 5%</i></small>`;
  const time = (s) => (s?.p50 == null ? none("Not measured") : `<span class="two">${secs(s.p50)}<i>${secs(s.p95)}</i></span>`);
  const count = (n) => (n == null ? none("Not reported") : n0(n));

  // The columns after Command. [has] says whether some command carries a figure for it; a column no command has one for
  // is left out. Requests and Failed always stand: they are the table.
  const COLUMNS = [
    { label: "Requests", has: () => true, cell: (h) => `<a class="open" href="${door(h.key)}">${n0(h.count)}${OPEN}</a>` },
    { label: "Failed", has: () => true, cell: (h) => {
      const n = Object.entries(h.outcomes || {}).reduce((a, [tag, k]) => a + (isFailed(tag) ? k : 0), 0);
      return n ? `<a class="open bad" href="${door(h.key, { outcome: "fail" })}">${n0(n)}${OPEN}</a><span class="sh">${pct(h.failure_share)}</span>` : "0";
    } },
    { label: "First byte", key: true, has: (h) => h.time_before_first_byte_ms?.p50 != null, cell: (h) => time(h.time_before_first_byte_ms) },
    { label: "Streaming", key: true, has: (h) => h.time_streaming_ms?.p50 != null, cell: (h) => time(h.time_streaming_ms) },
    { label: "Whole request", key: true, has: (h) => h.total_ms?.p50 != null, cell: (h) => time(h.total_ms) },
    { label: "Retries", has: (h) => h.retries > 0, cell: (h) => count(h.retries) },
    { label: "Sign-ins renewed", has: (h) => h.refreshes > 0, cell: (h) => count(h.refreshes) },
    { label: "Cache hit", has: (h) => h.cache_hit_ratio != null, cell: (h) => pct(h.cache_hit_ratio) ?? none("Not reported") },
    { label: "Cold cache", has: (h) => h.cache_cold > 0, cell: (h) => count(h.cache_cold) },
    { label: "Most at once", has: (h) => h.peak_inflight > 0, cell: (h) => count(h.peak_inflight) },
    // The spec's count (§3, "telemetry dropped"): requests during which splice dropped some of the records it keeps on
    // this disk, so their row is missing some figures. The word telemetry stays off the screen: it reads as
    // phoning home (marlin). Absent when no row carried
    // the counter, which reads "Not measured", never a zero.
    { label: "Record gaps", has: (h) => h.telemetry_dropped > 0, cell: (h) => (h.telemetry_dropped == null ? none("Not measured") : n0(h.telemetry_dropped)) },
  ];
  const head = (c) => `${c.label}${c.key ? KEY : ""}`;

  /** Failures by kind, the largest share first, in the words every page uses for an outcome; each opens its requests. A
   *  share is printed only from 1% up: below that the rank says it, not an "under 1%" before every reason (marlin). */
  function kinds(h) {
    const shares = Object.entries(h.failure_shares || {}).filter(([, s]) => s > 0).sort((a, b) => b[1] - a[1]);
    return shares.map(([tag, s]) => `<a class="k" href="${door(h.key, { tag })}">${s >= 0.005 ? `<b>${pct(s)}</b> ` : ""}${esc(countWord(tag) || OUTCOME_WORD[tag] || tagWord(tag) || "Failed")}</a>`).join("");
  }
  /** The compactions among the command's requests, which open on Requests with its Compactions toggle on, and its side
   *  queries: Claude Code's activity asks the head answers itself with no model (PerfKeys.ACTIVITY_QUERY), so they cost
   *  nothing. Plain text, since Requests never lists them, and only where there are any; the spec counts turn kind per
   *  command (v0.4.0 §3, the President's Oct 13 ruling; marlin's form). */
  function sorts(h) {
    const n = h.kinds?.compaction ?? 0, q = h.kinds?.activity_query ?? 0;
    const compactions = n > 0 ? `<a class="m" href="${door(h.key, { compact: "1" })}">${n0(n)} ${n === 1 ? "compaction" : "compactions"}</a>` : "";
    return compactions + (q > 0 ? `<span class="m">${n0(q)} ${q === 1 ? "side query" : "side queries"} · $0</span>` : "");
  }
  /** What the record behind a row could not cover, in the fewest words; the cause goes to the console. */
  function gaps(h) {
    const out = [];
    if (h.read_error || h.coverage_known === false || h.skipped_lines) {
      out.push("Partly read");
      const said = `${st.win} ${h.key}`;
      if (!st.warned.has(said)) st.warned.add(said) && console.warn(`perf ${h.key}: ${h.read_error ?? ""} coverage_known=${h.coverage_known} skipped_lines=${h.skipped_lines ?? 0}`);
    }
    if (h.clamped) out.push(`Last ${h.covers_ms >= 3600e3 ? `${Math.floor(h.covers_ms / 3600e3)} h` : `${Math.max(1, Math.floor(h.covers_ms / 60e3))} min`} only`);
    return out.map((g) => `<span class="gap">${g}</span>`).join("");
  }
  /** Slow or failing today, as the Commands cards said it; only with no pick, since the rows behind it follow the pick. */
  function flag(h) {
    if (ui.project != null || hasFilter()) return "";
    const f = flagOf(h.key);
    return f ? flagHtml(h.key, f) : "";
  }
  function row(h, cols) {
    const below = flag(h) + kinds(h) + sorts(h) + gaps(h);
    return `<div class="prow" role="row" style="--c:${colorOf(providerOfHead(h.key))}"><span class="pf cmd">${chip(h.key)}</span>`
      + cols.map((c) => `<span class="pf"><em>${head(c)}</em>${c.cell(h)}</span>`).join("")
      + (below ? `<p class="below">${below}</p>` : "") + `</div>`;
  }

  function html() {
    if (!st.body && !st.error && !st.reading) read();
    const sw = `<div class="switch" role="group" aria-label="Window">${Object.entries(WINDOWS).map(([k, v]) => `<button data-act="perfwin" data-v="${k}" aria-pressed="${st.win === k}">${v}</button>`).join("")}</div>`;
    const scoped = ui.project != null || hasFilter() ? `<span class="hn">All requests</span>` : "";
    const header = `<header><h2>How each command answered</h2>${scoped}${sw}</header>`;
    if (st.error) return `<section class="perf-heads">${header}<p class="empty">${st.error}</p></section>`;
    if (!st.body) return `<section class="perf-heads" aria-busy="true">${header}${GHOST.card([12, 30, 26, 22])}</section>`;
    const heads = st.body.heads || [];
    const used = heads.filter((h) => h.count > 0), idle = heads.filter((h) => !(h.count > 0));
    const cols = COLUMNS.filter((c) => used.some(c.has));
    const table = used.length
      ? `<div class="ptable card" role="table" style="--n:${cols.length}"><div class="prow pthead" role="row"><span class="pf cmd">Command</span>${cols.map((c) => `<span class="pf">${head(c)}</span>`).join("")}</div>${used.map((h) => row(h, cols)).join("")}</div>`
      : `<p class="empty">No requests in the last ${WINDOWS[st.win]}</p>`;
    const quiet = used.length && idle.length ? `<p class="quiet-heads"><span>No requests in the last ${WINDOWS[st.win]}</span>${idle.map((h) => chip(h.key)).join("")}</p>` : "";
    return `<section class="perf-heads">${header}${table}${quiet}</section>`;
  }

  document.addEventListener("click", (e) => {
    const el = e.target.closest("[data-act=perfwin]");
    if (!el || el.dataset.v === st.win) return;
    st.win = el.dataset.v; st.body = null; st.error = null; st.reading = false;
    render();
  });
  return { html };
})();
