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
// tagWord, isFailed, GHOST, esc).
"use strict";

const createPerfHeads = (scope, helpers) => {
  const { API, document } = scope;
  const { render, chip, providerOfHead, cmdOf, flagOf, flagHtml, hasFilter, ui, S, n0, secs, OPEN, OUTCOME_WORD, tagWord, isFailed, NOT_READ } = helpers;
  const WINDOWS = { "1h": "1 hour", "24h": "24 hours", "7d": "7 days" };
  // a compaction's ending, in words (StreamCompact's outcome); an outcome not named here reads as its own key
  const ENDED = {
    model_text: "summarized", model_thinking: "thought but wrote no summary", tooled_no_text: "called a tool instead of summarizing",
    empty_model: "came back empty", upstream_error: "refused by the provider", stream_error: "cut off mid-stream",
  };
  const st = { win: "24h", body: null, error: null, reading: false, gen: 0, warned: new Set(), models: new Set() };

  async function read() {
    const gen = ++st.gen;
    st.reading = true;
    // the compactions ledger beside the summary (TurnsMount /api/compact): its own counts by outcome, which perf rows lack
    const [r, c] = await Promise.all([API.get(`/api/perf/summary?window=${st.win}`), API.get("/api/compact")]);
    if (gen !== st.gen) return; // a newer window was asked for while this one was read
    st.reading = false;
    st.body = r.ok ? r.body : null;
    st.compact = c.ok ? c.body?.stats?.heads ?? null : null;
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
    // The time the gate held a request behind its command's full limit, before splice sent it (queue_wait_ms, from
    // admit_held_ms): only held requests carry it, so the times are theirs and the line under them says how many waited.
    // "Waited in line" is the words Requests and Settings already use for it.
    { label: "Waited in line", key: true, has: (h) => h.queue_wait_ms?.p50 != null, cell: (h) => (h.queue_wait_ms?.p50 == null
      ? none("None waited") : `${time(h.queue_wait_ms)}<span class="held">${n0(h.queue_wait_ms.count)} of ${n0(h.count)} waited</span>`) },
    { label: "Time before first byte", key: true, has: (h) => h.time_before_first_byte_ms?.p50 != null, cell: (h) => time(h.time_before_first_byte_ms) },
    { label: "Time streaming", key: true, has: (h) => h.time_streaming_ms?.p50 != null, cell: (h) => time(h.time_streaming_ms) },
    { label: "Whole request", key: true, has: (h) => h.total_ms?.p50 != null, cell: (h) => time(h.total_ms) },
    { label: "Retries", has: (h) => h.retries > 0, cell: (h) => count(h.retries) },
    { label: "Sign-ins renewed", has: (h) => h.refreshes > 0, cell: (h) => count(h.refreshes) },
    { label: "Cache hit", has: (h) => h.cache_hit_ratio != null, cell: (h) => pct(h.cache_hit_ratio) ?? none("Not reported") },
    { label: "Cold cache", has: (h) => h.cache_cold > 0, cell: (h) => count(h.cache_cold) },
    { label: "Most at once", has: (h) => h.peak_inflight > 0, cell: (h) => count(h.peak_inflight) },
    // The spec's count (§3), shown as "Record gaps" by a recorded Deviation: requests during which splice dropped some
    // of the records it keeps on this disk. Telemetry reads as data leaving the machine, and these are local writes. The
    // lost writes are other records, never the row that counts them, so no Requests row carries a mark (marlin, gap
    // 410). Shown only above zero; "Not measured" where no row carried the counter.
    { label: "Record gaps", has: (h) => h.telemetry_dropped > 0, cell: (h) => (h.telemetry_dropped == null ? none("Not measured") : n0(h.telemetry_dropped)) },
  ];
  const head = (c) => `${c.label}${c.key ? KEY : ""}`;

  /** Failures by kind, the largest share first, in the words every page uses for an outcome; each opens its requests. A
   *  share is printed only from 1% up: below that the rank says it, not an "under 1%" before every reason (marlin).
   *  Tags that read as one word are one reason with their shares summed: error:rate-limited, error:rate-limit-relayed
   *  and failure:rate_limit_error all read "Rate limited" (kit.js OUTCOME_WORD), and a row listed it twice (marlin,
   *  desk c at 3828, Oct 13). The reason opens every tag under it on Requests, so the click shows all of its share. */
  function kinds(h) {
    const byWord = new Map();
    for (const [tag, s] of Object.entries(h.failure_shares || {})) {
      if (!(s > 0)) continue;
      // a share, never a count, so the outcome's own word: countWord's "when splice restarted" stood bare under 1%
      const word = OUTCOME_WORD[tag] || tagWord(tag) || "Failed";
      const seen = byWord.get(word);
      if (!seen) byWord.set(word, { tags: [tag], s });
      else Object.assign(seen, { tags: [...seen.tags, tag], s: seen.s + s });
    }
    return [...byWord].sort((a, b) => b[1].s - a[1].s)
      .map(([word, { tags, s }]) => `<a class="k" href="${door(h.key, { tag: tags.join(",") })}">${s >= 0.005 ? `<b>${pct(s)}</b> ` : ""}${esc(word)}</a>`).join("");
  }
  /** How the command's compactions ended over the last seven days, from the compactions ledger (CompactPayloads'
   *  by_outcome_7d): it keeps its own week whatever window the table reads, so the line names its span. Absent when the
   *  ledger has no compaction for the command in that week or did not answer. */
  function endings(h) {
    const by = st.compact?.[h.key]?.by_outcome_7d ?? {};
    const parts = Object.entries(by).filter(([, n]) => n > 0).sort((a, b) => b[1] - a[1])
      .map(([o, n]) => `${n0(n)} ${ENDED[o] ?? esc(o.replace(/_/g, " "))}`);
    return parts.length ? `<span class="m">Compactions in the last 7 days: ${parts.join(", ")}</span>` : "";
  }
  /** The compactions among the command's requests, which open on Requests with its Compactions toggle on, and the status
   *  labels splice wrote itself from the last tool call with no model (PerfKeys.ACTIVITY_QUERY, ActivityLabel.kt), so
   *  they cost nothing; fin's words, the ones that count has carried since Oct 12. Plain text, since Requests never
   *  lists them, and only where there are any; the spec counts turn kind per
   *  command (v0.4.0 §3, the President's Oct 13 ruling; marlin's form). */
  function sorts(h) {
    const n = h.kinds?.compaction ?? 0, q = h.kinds?.activity_query ?? 0;
    const compactions = n > 0 ? `<a class="m" href="${door(h.key, { compact: "1" })}">${n0(n)} ${n === 1 ? "compaction" : "compactions"}</a>` : "";
    return compactions + endings(h) + (q > 0 ? `<span class="m">${n0(q)} ${q === 1 ? "status label" : "status labels"} splice wrote · $0</span>` : "");
  }
  /** Each model a command answered with, where it used more than one, so a slow or failing model is named (v0.4.0 §3,
   *  model attribution; PerfSummary.byModel). Two read as one line under the row. Three or more stand as sub-rows in the
   *  table's own Requests, Failed and Whole request columns, so they stack and compare at a glance, the three biggest by
   *  requests shown and the rest behind "N more models" (marlin, desk c at 3828, Oct 13). A row with one model already
   *  says it in its figures. A time needs an answer: a request that named no model failed before one answered, and a
   *  model every request to failed never answered, so neither prints one. A failed share prints from 1% up. */
  const SHOWN_MODELS = 3;
  // a model id carrying its release date (claude-haiku-4-5-20251001) reads by its name, never the date (marlin)
  // a request with no model ended before splice picked one, cut by a restart or refused first (no_request rows; live
  // Oct 13, claudex's 23 were all error:restarted), so its row says that and never reads as a model of that name
  const modelWord = (id) => (id === "?" ? "Ended before a model was picked" :modelName(S.labels, id.replace(/-\d{8}$/, "")) ?? id);
  const answered = (id, m) => id !== "?" && m.failure_share < 1 && m.total_ms?.p50 != null;
  const byCount = (h) => Object.entries(h.models || {}).sort((x, y) => y[1].count - x[1].count);
  function modelLine(h) {
    const ms = byCount(h);
    if (ms.length !== 2) return "";
    return ms.map(([id, m]) => {
      const typical = answered(id, m) ? ` · ${secs(m.total_ms.p50)} typical` : "";
      const failed = m.failure_share >= 0.005 ? ` · ${pct(m.failure_share)} failed` : "";
      return `<span class="m">${esc(modelWord(id))} · ${n0(m.count)}${typical}${failed}</span>`;
    }).join("");
  }
  /** The model sub-rows under a command with three or more, in the columns the table already shows. */
  function modelRows(h, cols) {
    const ms = byCount(h);
    if (ms.length < 3) return "";
    const open = st.models.has(h.key), shown = open ? ms : ms.slice(0, SHOWN_MODELS), rest = ms.length - SHOWN_MODELS;
    const cell = (c, id, m) => {
      if (c.label === "Requests") return n0(m.count);
      if (c.label === "Failed") return m.failure_share >= 0.005 ? `<span class="bad">${pct(m.failure_share)}</span>` : "";
      if (c.label === "Whole request") return answered(id, m) ? time(m.total_ms) : "";
      return "";
    };
    const rows = shown.map(([id, m]) => `<div class="prow msub" role="row"><span class="pf cmd">${esc(modelWord(id))}</span>`
      + cols.map((c) => { const v = cell(c, id, m); return `<span class="pf">${v ? `<em>${head(c)}</em>${v}` : ""}</span>`; }).join("")
      + `</div>`).join("");
    const more = rest > 0 ? `<div class="prow msub more" role="row"><span class="pf cmd"><button class="act quiet small" data-act="perfmods" `
      + `data-k="${esc(h.key)}">${open ? "Fewer models" : `${n0(rest)} more ${rest === 1 ? "model" : "models"}`}</button></span></div>` : "";
    return rows + more;
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
    const below = flag(h) + kinds(h) + sorts(h) + gaps(h), mods = modelLine(h);
    return `<div class="prow" role="row" style="--c:${colorOf(providerOfHead(h.key))}"><span class="pf cmd">${chip(h.key)}</span>`
      + cols.map((c) => `<span class="pf"><em>${head(c)}</em>${c.cell(h)}</span>`).join("")
      + (below ? `<p class="below">${below}</p>` : "") + (mods ? `<p class="below mods">${mods}</p>` : "") + `</div>` + modelRows(h, cols);
  }

  function html() {
    if (!st.body && !st.error && !st.reading) read();
    const sw = `<div class="switch" role="group" aria-label="Window">${Object.entries(WINDOWS).map(([k, v]) => `<button data-act="perfwin" data-v="${k}" aria-pressed="${st.win === k}">${v}</button>`).join("")}</div>`;
    const scoped = ui.project != null || hasFilter() ? `<span class="hn">All requests</span>` : "";
    const header = `<header><h2>Results by command</h2>${scoped}${sw}</header>`;
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
    const mo = e.target.closest("[data-act=perfmods]");
    if (mo) { const k = mo.dataset.k; st.models.has(k) ? st.models.delete(k) : st.models.add(k); render(); return; }
    const el = e.target.closest("[data-act=perfwin]");
    if (!el || el.dataset.v === st.win) return;
    st.win = el.dataset.v; st.body = null; st.error = null; st.reading = false;
    render();
  });
  return { html };
};
