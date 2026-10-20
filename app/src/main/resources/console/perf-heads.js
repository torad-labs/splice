// Usage › Results by command: the per-command table (v0.4.0 spec §3, its Resolved block), in the place the Commands cards
// held, so each command is said once (marlin, Oct 12). It answers one question: which command is healthy and which one
// is hurting. A row is five columns, in the worst first: the command (the provider's colour on its dot alone), its
// requests as a number over a bar, its failure rate as one bar that is coloured only when it is bad, and the whole
// request's typical time and slowest 5%. Everything else a command has opens under its row, collapsed by default, as
// shapes: every request of the command in one bar, sized by outcome; the cache and load as a ring and small glyphs; the
// stages as ticks on one axis; the compactions as a flow; the models as small multiples. Every figure is read from
// GET /api/perf/summary?window=1h|24h|7d, which the daemon folds over every request each command recorded, so the table
// ignores the page's project pick and filters and says "All requests" when one is set. Nothing is invented: a figure the
// summary does not carry is a dash, never a zero, and a count that has no command with a figure is left out (marlin).
// Every count in the panel opens its requests on Requests, in the table's window and with no pick.
//
// Mounted by usage.js: render() draws PERF_HEADS.html() beside the Breakdown. This file reads the page's own helpers at
// draw time (colorOf, providerOfHead, cmdOf, flagOf, flagHtml, hasFilter, ui, n0, secs, OPEN, OUTCOME_WORD, tagWord,
// isFailed, GHOST, esc).
"use strict";

const createPerfHeads = (scope, helpers) => {
  const { API, document } = scope;
  const { render, providerOfHead, cmdOf, flagOf, flagHtml, hasFilter, ui, S, n0, secs, OPEN, OUTCOME_WORD, tagWord, isFailed, NOT_READ } = helpers;
  const WINDOWS = { "1h": "1 hour", "24h": "24 hours", "7d": "7 days" };
  // a compaction's ending, in words (StreamCompact's outcome); an outcome not named here reads as its own key
  const ENDED = {
    model_text: "Summarized", model_thinking: "Thought but wrote no summary", tooled_no_text: "Called a tool instead of summarizing",
    empty_model: "Came back empty", upstream_error: "Refused by the provider", stream_error: "Cut off mid-stream",
  };
  // a clean outcome that no page words: kit.js reads only a prefixed tag, and ok or empty_message carries no prefix
  const PLAIN = { ok: "Answered", empty_message: "Empty message" };
  const st = { win: "24h", body: null, error: null, reading: false, gen: 0, warned: new Set(), models: new Set(), open: new Set() };
  const SHOWN_MODELS = 3;
  // the failure rate that reads as hurting (red) and as worth a look (Key Light); below, the bar stays neutral
  const BAD = 0.05, WATCH = 0.01;

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
  const pct = (x) => (x == null ? null : x > 0 && x < 0.005 ? "<1%" : `${Math.round(x * 100)}%`);
  // a failure rate as printed: one decimal under 10% (1.4%, 0.8%) so two rows never both read 1% with one tinted, whole
  // numbers from 10%. [shown] is the number the row prints, and the tint is read from it, never from the raw share.
  const shown = (x) => (x == null ? null : x >= 0.0995 ? Math.round(x * 100) : Math.round(x * 1000) / 10);
  const rate = (x) => { const s = shown(x); return s == null ? null : s === 0 ? (x > 0 ? "<0.1%" : "0%") : `${x >= 0.0995 ? s : s.toFixed(1)}%`; };
  const dash = `<i class="nr" title="Not reported">—</i>`;
  const failedOf = (h) => Object.entries(h.outcomes || {}).reduce((a, [tag, k]) => a + (isFailed(tag) ? k : 0), 0);
  const shareOf = (h) => h.failure_share ?? (h.count > 0 ? failedOf(h) / h.count : 0);
  const level = (share) => { const s = shown(share) / 100; return s >= BAD ? "bad" : s >= WATCH ? "watch" : "ok"; };
  const bar = (w) => `<span class="vbar" aria-hidden="true"><b style="width:${Math.round(Math.max(0, Math.min(100, w)) * 10) / 10}%"></b></span>`;
  const CHEV = `<svg class="chev" viewBox="0 0 10 10" aria-hidden="true"><path d="M3 1.5 7 5 3 8.5"/></svg>`;

  /** Failures by kind, the largest count first, in the words every page uses for an outcome. Tags that read as one word
   *  are one reason with their counts summed: error:rate-limited, error:rate-limit-relayed and failure:rate_limit_error
   *  all read "Rate limited" (kit.js OUTCOME_WORD), and a row listed it twice (marlin, desk c at 3828, Oct 13). The reason
   *  opens every tag under it on Requests, so the click shows all of its count. */
  function kindsOf(h) {
    const byWord = new Map();
    for (const [tag, s] of Object.entries(h.failure_shares || {})) {
      if (!(s > 0)) continue;
      const n = h.outcomes?.[tag] ?? Math.round(s * h.count);
      const word = OUTCOME_WORD[tag] || tagWord(tag) || "Failed";
      const seen = byWord.get(word);
      if (!seen) byWord.set(word, { word, tags: [tag], n });
      else Object.assign(seen, { tags: [...seen.tags, tag], n: seen.n + n });
    }
    return [...byWord.values()].sort((a, b) => b.n - a.n);
  }

  // The panel's shapes. Each is drawn from the summary's own figures; a figure that is absent or zero draws nothing.
  const BW = 40;          // rem, about how wide a panel bar is drawn (a block wide enough carries its own word)
  const labelRem = (text) => text.length * 0.42 + 0.8;   // rem a label takes at the panel's type size (an estimate)
  const STEP = ["f0", "f1", "f2"];   // failure blocks by rank: the largest is the full limit red, the rest step down
  const GLYPH = {
    retry: `<svg viewBox="0 0 16 16" aria-hidden="true"><path d="M13.2 6.2A5.3 5.3 0 1 0 13.6 10"/><path d="M13.4 2.8v3.4H10"/></svg>`,
    cold: `<svg viewBox="0 0 16 16" aria-hidden="true"><path d="M8 1.5v13M2.3 4.75l11.4 6.5M13.7 4.75l-11.4 6.5"/></svg>`,
    key: `<svg viewBox="0 0 16 16" aria-hidden="true"><circle cx="5.2" cy="10.8" r="2.6"/><path d="M7.2 8.8l6.3-6.3M11.2 4.8l1.6 1.6M13.5 2.5l1.2 1.2"/></svg>`,
    peak: `<svg viewBox="0 0 16 16" aria-hidden="true"><path d="M3.5 14.5V1.5M3.5 2.3h8.5l-2 2.6 2 2.6H3.5"/></svg>`,
    gap: `<svg viewBox="0 0 16 16" aria-hidden="true"><path d="M1.5 8h4.5M10 8h4.5M7 8h2" stroke-dasharray=".5 1.5"/></svg>`,
    label: `<svg viewBox="0 0 16 16" aria-hidden="true"><path d="M2 3.5h8.4L14 8l-3.6 4.5H2z"/></svg>`,
  };
  // The counts the stat strip carries, each shown only above zero, in the order the panel reads them.
  const FACTS = [
    { label: "Most at once", glyph: "peak", has: (h) => h.peak_inflight > 0, n: (h) => h.peak_inflight },
    { label: "Retries", glyph: "retry", has: (h) => h.retries > 0, n: (h) => h.retries },
    { label: "Cold cache", glyph: "cold", has: (h) => h.cache_cold > 0, n: (h) => h.cache_cold },
    { label: "Sign-ins renewed", glyph: "key", has: (h) => h.refreshes > 0, n: (h) => h.refreshes },
    // The spec's count (§3), shown as "Record gaps" by a recorded Deviation: requests during which splice dropped some of
    // the records it keeps on this disk. Telemetry reads as data leaving the machine, and these are local writes. The lost
    // writes are other records, never the row that counts them, so no Requests row carries a mark (marlin, gap 410).
    { label: "Record gaps", glyph: "gap", has: (h) => h.telemetry_dropped > 0, n: (h) => h.telemetry_dropped },
  ];
  const RING_R = 16, RING_C = 2 * Math.PI * RING_R;
  // the times on a log axis, from a round figure under the smallest typical to one over the largest slowest 5%
  const NICE = [1, 3, 10, 30, 100, 300, 1e3, 3e3, 1e4, 3e4, 6e4, 18e4, 6e5];
  const axisOf = (typicals, slowests) => {
    const low = Math.min(...typicals.map((x) => Math.max(1, x))), high = Math.max(...slowests);
    const lo = [...NICE].reverse().find((v) => v <= low) ?? 1, hi = NICE.find((v) => v >= high) ?? high;
    const span = Math.log(hi) - Math.log(lo) || 1;
    const at = (x) => Math.max(0, Math.min(100, ((Math.log(Math.max(x, lo)) - Math.log(lo)) / span) * 100));
    // no more than six labels along the axis, always the last one, and none within 15% of it, so no two labels touch
    const all = NICE.filter((v) => v >= lo && v <= hi), step = Math.ceil(all.length / 6);
    const picked = all.filter((v, i) => (all.length - 1 - i) % step === 0);
    // each label is as wide as its text (about 2.4% of the axis per character, the stage axis being about 225 px); the first sits
    // on the left edge, the last on the right edge, the rest centred on their mark, and a middle label that comes within 7% of
    // the one before it, or of the last, is left out
    const wide = (v) => tickWord(v).length * 2.4, GAP = 7;
    const kept = [];
    picked.forEach((v, i) => {
      const first = i === 0, last = i === picked.length - 1 && picked.length > 1;
      if (first) { kept.push({ v, l: 0, r: wide(v) }); return; }
      if (last) { const l = 100 - wide(v); while (kept.length > 1 && l - kept.at(-1).r < GAP) kept.pop(); kept.push({ v, l, r: 100 }); return; }
      const l = at(v) - wide(v) / 2, r = at(v) + wide(v) / 2;
      if (l - kept.at(-1).r >= GAP) kept.push({ v, l, r });
    });
    return { lo, hi, ticks: kept.map((k) => k.v), at };
  };
  // an axis label: a round figure, never the table's two-part reading (60 000 ms is "1 min", not "1 min 0 s")
  const tickWord = (ms) => (ms < 1000 ? `${ms} ms` : ms < 60000 ? `${ms / 1000} s` : `${ms / 60000} min`);
  const LEGEND = `<span class="pd-leg"><i class="pd-lt"></i>Typical<i class="pd-ls"></i>Slowest 5%</span>`;
  // one bar on the shared time axis: a solid bar to the typical time, a thin line on to the slowest 5%, whose end is the tick
  const timeBar = (ax, typical, slowest) => `<span class="pd-trk"><i class="hi" style="width:${ax.at(slowest).toFixed(2)}%"></i><i class="mid" style="width:${ax.at(typical).toFixed(2)}%"></i></span>`;

  /** One proportional bar over [total] from [{n, word, cls, href}], in order. A block is as wide as its count says (a
   *  tiny one keeps a hairline) and is a link when it has an href. A block wide enough carries its word and count; the
   *  rest are named in one tidy row under the bar, the largest first. Blocks that are neither answered nor failed come
   *  last and say so, so the figures add up. */
  function segBar(segs, total) {
    const blocks = [], legend = [];
    for (const s of segs) {
      if (!(s.n > 0) || !(total > 0)) continue;
      const w = (s.n / total) * 100, text = `${s.word} ${n0(s.n)}`;
      const inside = s.cls !== "nt" && w >= (labelRem(text) / (0.9 * BW)) * 100;
      const tag = s.href ? "a" : "span", href = s.href ? ` href="${s.href}"` : "";
      blocks.push(`<${tag} class="pd-seg ${s.cls}"${href} style="width:max(${w.toFixed(3)}%, .16rem)" title="${esc(text)}">`
        + `${inside ? `<span>${esc(s.word)} <b>${n0(s.n)}</b></span>` : ""}</${tag}>`);
      if (!inside) legend.push(s);
    }
    const item = (s, bare) => {
      const inner = `${bare ? "" : `<i class="pd-sw ${s.cls}"></i>`}${esc(s.word)} <b>${n0(s.n)}</b>`;
      return s.href ? `<a class="pd-lb" href="${s.href}">${inner}</a>` : `<span class="pd-lb">${inner}</span>`;
    };
    const quiet = legend.filter((s) => s.cls === "nt"), rest = legend.filter((s) => s.cls !== "nt").sort((a, b) => b.n - a.n);
    // the quiet outcomes are one grey block in the bar: its total leads, and the outcomes it holds follow the colon, so they
    // read as parts of it and never as peers of the failures
    const neither = quiet.length ? `<div class="pd-lgrp"><span class="pd-neither"><i class="pd-sw nt"></i>Neither answered nor failed <b>${n0(quiet.reduce((a, s) => a + s.n, 0))}</b>:</span> ${quiet.map((s) => item(s, true)).join(`<span class="pd-comma">, </span>`)}</div>` : "";
    return `<div class="pd-pop"><div class="pd-bar">${blocks.join("")}</div>${legend.length ? `<div class="pd-legend">${rest.map((s) => item(s)).join("")}${neither}</div>` : ""}</div>`;
  }

  /** The command's whole population: the failure rate as printed, over one bar of every request in the window. The ok
   *  share and the other clean outcomes stay in the neutral part; each failure kind is a block of the limit red, and the
   *  failed total opens every failure on Requests. */
  const heroSection = (h) => {
    const n = failedOf(h), share = shareOf(h), lv = level(share);
    const others = Object.entries(h.outcomes || {}).filter(([t, k]) => k > 0 && t !== "ok" && !isFailed(t)).sort((a, b) => b[1] - a[1]);
    const ok = Math.max(0, h.count - n - others.reduce((a, [, k]) => a + k, 0));
    const segs = [
      { n: ok, word: PLAIN.ok, cls: "ok", href: door(h.key, { tag: "ok" }) },
      ...others.map(([t, k]) => ({ n: k, word: OUTCOME_WORD[t] || PLAIN[t] || t.replace(/_/g, " "), cls: "nt", href: door(h.key, { tag: t }) })),
      ...kindsOf(h).map((k, i) => ({ n: k.n, word: k.word, cls: STEP[Math.min(i, STEP.length - 1)], href: door(h.key, { tag: k.tags.join(",") }) })),
    ];
    const failed = n > 0
      ? `<a class="pd-failed" href="${door(h.key, { outcome: "fail" })}"><b>${n0(n)}</b> failed<span>of ${n0(h.count)} requests</span>${OPEN}</a>`
      : `<span class="pd-failed"><span>No failures of ${n0(h.count)} requests</span></span>`;
    return `<section class="psec pd-hero"><div class="pd-h"><h3>Outcomes</h3></div><div class="pd-top"><b class="pd-rate lv-${lv}">${n > 0 ? (rate(share) ?? "0%") : "0%"}</b>${failed}</div>${segBar(segs, h.count)}</section>`;
  };

  /** The stat strip: the cache hit as a ring, then the counts that are above zero, each a small glyph, its number and its
   *  name on one baseline. The status labels are the ones splice wrote itself from the last tool call with no model
   *  (PerfKeys.ACTIVITY_QUERY, ActivityLabel.kt), so they cost nothing; fin's words, the ones that count has carried since
   *  Oct 12. */
  const statTile = (icon, number, label, title, focal) => `<span class="pd-stat${focal ? " focal" : ""}"${title ? ` title="${title}"` : ""}>${icon}<span><b>${number}</b><small>${label}</small></span></span>`;
  const statStrip = (h, facts) => {
    const r = h.cache_hit_ratio, q = h.kinds?.activity_query ?? 0;
    const ring = r > 0 ? statTile(`<svg class="pd-ring" viewBox="0 0 44 44" role="img" aria-label="Cache hit ${pct(r)}"><circle class="rt" cx="22" cy="22" r="${RING_R}"/>`
      + `<circle class="rv" cx="22" cy="22" r="${RING_R}" transform="rotate(-90 22 22)" stroke-dasharray="${(RING_C * r).toFixed(2)} ${RING_C.toFixed(2)}"/></svg>`, pct(r), "Cache hit", null, true) : "";
    const tiles = [ring, ...facts.filter((c) => c.glyph && c.has(h)).map((c) => statTile(GLYPH[c.glyph], n0(c.n(h)), c.label)),
      q > 0 ? statTile(GLYPH.label, n0(q), "Status labels", "Written by splice, no model, $0") : ""].filter(Boolean);
    return tiles.length ? `<section class="psec"><div class="pd-h"><h3>Cache and activity</h3></div><div class="pd-stats">${tiles.join("")}</div></section>` : "";
  };

  /** The stages of a request, for the ones this command measured, on one log axis: the solid bar runs to the typical time,
   *  the light bar on to the slowest 5%, whose end is the tick. "Waited in line" is the time the gate held a request
   *  behind its command's full limit, before splice sent it (queue_wait_ms, from admit_held_ms): only held requests carry
   *  it, so the times are theirs and the line under the name says how many waited. Each stage is its own subset of
   *  requests, so the stages are never drawn as adding up to the total. */
  const stagesSection = (h) => {
    const q = h.queue_wait_ms, rows = [
      q?.p50 != null ? ["Waited in line", q, `${n0(q.count)} of ${n0(h.count)} requests`] : null,
      h.time_before_first_byte_ms?.p50 != null ? ["Before first byte", h.time_before_first_byte_ms] : null,
      h.time_streaming_ms?.p50 != null ? ["Streaming", h.time_streaming_ms] : null,
    ].filter(Boolean);
    if (!rows.length) return "";
    const ax = axisOf(rows.map(([, s]) => s.p50), rows.map(([, s]) => s.p95 ?? s.p50)), longest = Math.max(...rows.map(([, s]) => s.p50));
    const body = rows.map(([label, s, sub]) => {
      const hi = s.p95 ?? s.p50;
      return `<div class="pd-st pd-grid${s.p50 === longest ? " pd-focus" : ""}"><span class="pd-sl">${label}${sub ? `<small>${sub}</small>` : ""}</span>`
        + timeBar(ax, s.p50, hi)
        + `<span class="pd-n t">${secs(s.p50)}</span><span class="pd-n s">${s.p95 == null ? dash : secs(s.p95)}</span></div>`;
    }).join("");
    const ticks = ax.ticks.map((v, i) => `<span class="pd-tk${i === 0 ? " pd-t-first" : i === ax.ticks.length - 1 ? " pd-t-last" : ""}" style="left:${ax.at(v).toFixed(2)}%">${tickWord(v)}</span>`).join("");
    return `<section class="psec pd-stages"><div class="pd-h pd-grid"><h3>Stages</h3>${LEGEND}<span class="pd-n">Typical</span><span class="pd-n">Slowest 5%</span></div>`
      + `${body}<div class="pd-axis pd-grid"><span class="pd-sl"><small>Time, log scale</small></span><span class="pd-ax">${ticks}</span><span></span><span></span></div></section>`;
  };

  /** How the command's compactions ended over the last seven days, from the compactions ledger (CompactPayloads'
   *  by_outcome_7d): it keeps its own week whatever window the table reads, so the bar names its span. The count beside the
   *  heading is the table's window, named, and opens the compactions on Requests with its Compactions toggle on; the bar is its
   *  own seven days, named, with its own total, so the two never read as one figure. Only where there
   *  are any; the spec counts turn kind per command (v0.4.0 §3, the President's Oct 13 ruling; marlin's form). */
  const turnsSection = (h) => {
    const n = h.kinds?.compaction ?? 0;
    const by = Object.entries(st.compact?.[h.key]?.by_outcome_7d ?? {}).filter(([, k]) => k > 0)
      .sort((a, b) => (b[0] === "model_text") - (a[0] === "model_text") || b[1] - a[1]);
    if (!(n > 0) && !by.length) return "";
    const count = n > 0 ? `<a class="pd-count" href="${door(h.key, { compact: "1" })}"><b>${n0(n)}</b> in ${WINDOWS[st.win]}${OPEN}</a>` : "";
    const total = by.reduce((a, [, k]) => a + k, 0);
    let p = 0;
    const segs = by.map(([o, k]) => ({ n: k, word: ENDED[o] ?? o.replace(/_/g, " "), cls: o === "model_text" ? "ok" : STEP[Math.min(p++, STEP.length - 1)] }));
    return `<section class="psec"><div class="pd-h"><h3>Compactions</h3>${count}</div>`
      + (by.length ? `<div class="pd-sub"><b>${n0(total)}</b> ended, last 7 days</div>${segBar(segs, total)}` : "") + `</section>`;
  };

  // a model id carrying its release date (claude-haiku-4-5-20251001) reads by its name, never the date (marlin)
  // a request with no model ended before splice picked one, cut by a restart or refused first (no_request rows; live
  // Oct 13, claudex's 23 were all error:restarted), so its row says that and never reads as a model of that name
  const modelWord = (id) => (id === "?" ? "Ended before a model was picked" : modelName(S.labels, id.replace(/-\d{8}$/, "")) ?? id);
  const answered = (id, m) => id !== "?" && m.failure_share < 1 && m.total_ms?.p50 != null;
  const byCount = (h) => Object.entries(h.models || {}).sort((x, y) => y[1].count - x[1].count);
  /** Each model a command answered with, where it used more than one, as small cards on shared scales: the requests on the
   *  busiest model's count and the failure rate on 0 to 100%, as the table's own bars are, and the typical to slowest 5%
   *  on one log axis across the cards. A model that fails is the loud card, in the limit red. A time needs an answer: a
   *  request that named no model failed before one answered, and a model every request to failed never answered, so
   *  neither prints one. The three biggest by requests are shown and the rest sit behind "N more" (marlin, Oct 13). A
   *  command with one model has no cards: its own figures already say it. */
  const modelsSection = (h) => {
    const ms = byCount(h);
    if (ms.length < 2) return "";
    const open = st.models.has(h.key), shown = open ? ms : ms.slice(0, SHOWN_MODELS), rest = ms.length - SHOWN_MODELS;
    const most = Math.max(1, ms[0][1].count);
    const timed = shown.filter(([id, m]) => answered(id, m));
    const ax = timed.length ? axisOf(timed.map(([, m]) => m.total_ms.p50), timed.map(([, m]) => m.total_ms.p95 ?? m.total_ms.p50)) : null;
    // the one card that reads loud: the model failing most, when its rate is worth a look at all
    const worst = ms.reduce((w, e) => (e[1].failure_share > (w?.[1].failure_share ?? 0) ? e : w), null);
    const loud = worst && level(worst[1].failure_share) !== "ok" ? worst[0] : null;
    const cards = shown.map(([id, m]) => {
      const lv = level(m.failure_share), ok = answered(id, m);
      const reqW = (m.count / most) * 100, failW = m.failure_share > 0 ? Math.max(1.5, m.failure_share * 100) : 0;
      const t = ok ? m.total_ms : null, hi = t ? t.p95 ?? t.p50 : null;
      const time = t
        ? `${timeBar(ax, t.p50, hi)}<span class="pd-ends"><span><small>Typical</small><b>${secs(t.p50)}</b></span><span><small>Slowest 5%</small><b>${t.p95 == null ? dash : secs(t.p95)}</b></span></span>`
        : `<span class="pd-trk"></span><span class="pd-ends"><span><small>Typical</small><b>${dash}</b></span><span><small>Slowest 5%</small><b>${dash}</b></span></span>`;
      return `<div class="pd-card lv-${lv}${id === loud ? " loud" : ""}"><span class="pd-cn">${esc(modelWord(id))}</span><div class="pd-pair">`
        + `<div class="pd-q"><span class="k">Requests</span><b>${n0(m.count)}</b><span class="pd-mini"><i style="width:${reqW.toFixed(2)}%"></i></span></div>`
        + `<div class="pd-q"><span class="k">Failed</span><b class="lv-${lv}">${rate(m.failure_share) ?? "0%"}</b><span class="pd-mini fail lv-${lv}"><i style="width:${failW.toFixed(2)}%"></i></span></div></div>`
        + `<div class="pd-time">${time}</div></div>`;
    }).join("");
    const more = rest > 0 ? `<button class="plink" type="button" data-act="perfmods" data-k="${esc(h.key)}">${open ? "Fewer models" : `${n0(rest)} more ${rest === 1 ? "model" : "models"}`}</button>` : "";
    return `<section class="psec"><div class="pd-h"><h3>Models</h3>${LEGEND}</div><div class="pd-cards">${cards}</div>${more}</section>`;
  };

  /** What the record behind a row could not cover, in the fewest words; the cause goes to the console. */
  function gaps(h) {
    const out = [];
    if (h.read_error || h.coverage_known === false || h.skipped_lines) {
      // what the summary says could not be read (PerfSummary: an unparseable perf row is skipped and left out of every count;
      // a generation that could not be read leaves the window's coverage unknown), in words, never a bare "Partly read"
      if (h.read_error || h.coverage_known === false) out.push("Part of the request log could not be read, so counts may be low");
      if (h.skipped_lines) out.push(`${n0(h.skipped_lines)} request ${h.skipped_lines === 1 ? "record" : "records"} could not be read and ${h.skipped_lines === 1 ? "is" : "are"} not counted`);
      const said = `${st.win} ${h.key}`;
      if (!st.warned.has(said)) st.warned.add(said) && console.warn(`perf ${h.key}: ${h.read_error ?? ""} coverage_known=${h.coverage_known} skipped_lines=${h.skipped_lines ?? 0}`);
    }
    if (h.clamped) out.push(`Last ${h.covers_ms >= 3600e3 ? `${Math.floor(h.covers_ms / 3600e3)} h` : `${Math.max(1, Math.floor(h.covers_ms / 60e3))} min`} only`);
    return out;
  }
  /** Slow or failing today, as the Commands cards said it; only with no pick, since the rows behind it follow the pick. */
  const flagFor = (h) => (ui.project != null || hasFilter() ? null : flagOf(h.key));

  function detail(h, facts) {
    const f = flagFor(h), g = gaps(h);
    const note = (f ? flagHtml(h.key, f) : "") + g.map((x) => `<span class="gap">${x}</span>`).join("");
    const body = (note ? `<p class="pnote">${note}</p>` : "") + heroSection(h) + statStrip(h, facts)
      + stagesSection(h) + turnsSection(h) + modelsSection(h);
    return `<div class="pdetail" id="perf-${esc(h.key)}">${body}</div>`;
  }

  function item(h, ctx) {
    const open = st.open.has(h.key), share = shareOf(h), n = failedOf(h), lv = level(share), f = flagFor(h);
    const shownRate = n > 0 ? rate(share) ?? "0%" : "0%";
    const reqW = ctx.max > 0 ? Math.max(2, (h.count / ctx.max) * 100) : 0;
    const failW = n > 0 ? Math.max(2, share * 100) : 0;
    const typ = h.total_ms?.p50, slow = h.total_ms?.p95;
    return `<div class="pitem${open ? " open" : ""}" role="rowgroup" style="--c:${colorOf(providerOfHead(h.key))}">`
      + `<div class="prow pcols" role="row" data-act="perfopen" data-k="${esc(h.key)}">`
      + `<span class="pf cmd" role="cell"><button class="plbl" type="button" aria-expanded="${open}" aria-controls="perf-${esc(h.key)}">${CHEV}<i class="dot" aria-hidden="true"></i><span class="nm">${esc(h.key)}</span></button></span>`
      + `<span class="pf req" role="cell"><em>Requests</em>${bar(reqW)}<b class="v">${n0(h.count)}</b></span>`
      + `<span class="pf fail lv-${lv}" role="cell"><em>Failed</em>${bar(failW)}<b class="v">${shownRate}</b></span>`
      + `<span class="pf num tm${f?.kind === "slow" ? " slow" : ""}" role="cell"><em>Typical</em>${typ == null ? dash : secs(typ)}</span>`
      + `<span class="pf num tm" role="cell"><em>Slowest 5%</em>${slow == null ? dash : secs(slow)}</span>`
      + `</div>${open ? detail(h, ctx.facts) : ""}</div>`;
  }

  function html() {
    if (!st.body && !st.error && !st.reading) read();
    const sw = `<div class="switch" role="group" aria-label="Window">${Object.entries(WINDOWS).map(([k, v]) => `<button data-act="perfwin" data-v="${k}" aria-pressed="${st.win === k}">${v}</button>`).join("")}</div>`;
    const scoped = ui.project != null || hasFilter() ? `<span class="hn">All requests</span>` : "";
    const header = `<header><h2>Results by command</h2>${scoped}${sw}</header>`;
    if (st.error) return `<section class="perf-heads">${header}<p class="empty">${st.error}</p></section>`;
    if (!st.body) return `<section class="perf-heads" aria-busy="true">${header}${GHOST.card([12, 30, 26, 22])}</section>`;
    const heads = st.body.heads || [];
    // the one that is hurting reads first: the highest failure rate, then the busiest
    const used = heads.filter((h) => h.count > 0).sort((a, b) => shareOf(b) - shareOf(a) || b.count - a.count);
    const idle = heads.filter((h) => !(h.count > 0));
    const ctx = { max: Math.max(0, ...used.map((h) => h.count)), facts: FACTS.filter((c) => used.some(c.has)) };
    const table = used.length
      ? `<div class="ptable card" role="table"><div class="pthead pcols" role="row"><span class="pf cmd">Command</span><span class="pf">Requests</span><span class="pf">Failed</span><span class="pf num" title="Counts every request, failed ones included">Typical</span><span class="pf num" title="Counts every request, failed ones included">Slowest 5%</span></div>${used.map((h) => item(h, ctx)).join("")}</div>`
      : `<p class="empty">No requests in the last ${WINDOWS[st.win]}</p>`;
    const basis = used.length ? `<p class="pquiet pbasis">Times count every request, failed ones included, so fast rejections pull a typical time down.</p>` : "";
    const quiet = used.length && idle.length ? `<p class="pquiet">No requests in the last ${WINDOWS[st.win]}: <span>${idle.map((h) => esc(h.key)).join(", ")}</span></p>` : "";
    return `<section class="perf-heads">${header}${table}${basis}${quiet}</section>`;
  }

  document.addEventListener("click", (e) => {
    const toggle = (set, k) => { set.has(k) ? set.delete(k) : set.add(k); render(); };
    const mo = e.target.closest("[data-act=perfmods]");
    if (mo) { toggle(st.models, mo.dataset.k); return; }
    const row = e.target.closest("[data-act=perfopen]");
    if (row && !e.target.closest("a")) { toggle(st.open, row.dataset.k); return; }
    const el = e.target.closest("[data-act=perfwin]");
    if (!el || el.dataset.v === st.win) return;
    st.win = el.dataset.v; st.body = null; st.error = null; st.reading = false;
    render();
  });
  return { html };
};
