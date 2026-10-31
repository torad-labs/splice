// Usage › Results by command: the per-command table (v0.4.0 spec §3, its Resolved block), in the place the Commands cards
// held, so each command is said once. It answers one question: which command is healthy and which one
// is hurting. A row is five columns, in the worst first: the command (the provider's colour on its dot alone), its
// requests as a number over a bar, its failure rate as one bar that is coloured only when it is bad, and the whole
// request's typical time and slowest 5%. Everything else a command has opens under its row, collapsed by default, as
// shapes: every request of the command in one bar, sized by outcome; the cache and load as a ring and small glyphs; the
// stages as ticks on one axis; the compactions as a flow; the models as small multiples. Every figure is read from
// GET /api/perf/summary?window=1h|24h|7d, which the daemon folds over every request each command recorded, so the table
// ignores the page's project pick and filters and says so on its head when one is set. It reads the page's window (the
// page's 5 hours as 24 hours, the nearest the summary keeps) until a window is picked on its own switch. Nothing is
// invented: a figure the summary does not carry is a dash, never a zero, and a count that has no command with a figure is
// left out. Every count in the panel opens its requests on Requests, in the table's window and with no pick.
//
// Mounted by usage.js: render() draws PERF_HEADS.html() beside Where it went and Tokens by command. This file reads the page's own helpers at
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
  // [pick] is the window he chose on this table's own switch; until he does, the table reads the page's window, or the
  // nearest one the summary keeps (it folds 1 hour, 24 hours and 7 days, so the page's 5 hours reads as 24 hours)
  const st = { pick: null, readFor: null, body: null, error: null, reading: false, gen: 0, warned: new Set(), models: new Set(), open: new Set(), opened: null, ...lastShape() };
  // THE TABLE'S OWN SHAPE FROM THE LAST TIME IT LANDED: how many rows, and whether the idle commands drew their line
  // under it, so the first frame of the next visit is the height the table lands at and nothing under it moves
  function lastShape() {
    try {
      const v = JSON.parse(localStorage.getItem("splice-usage-results") || "null");
      if (v && v.rows > 0) return { rows: Math.min(Number(v.rows) || 6, 24), idle: Math.min(Number(v.idle ?? (v.quiet ? 4 : 0)) || 0, 24) };
    } catch { /* unreadable: the default shape */ }
    // a first visit has nothing remembered: a console with seven commands in use and a few idle ones under them
    return { rows: 7, idle: 4 };
  }
  function keepShape(rows, idle) {
    if (rows === st.rows && idle === st.idle) return;
    st.rows = rows; st.idle = idle;
    try { localStorage.setItem("splice-usage-results", JSON.stringify({ rows, idle })); } catch { /* a blocked store costs the ghost its shape */ }
  }
  const winNow = () => st.pick ?? (ui.win === "5h" ? "24h" : "7d");
  const SHOWN_MODELS = 3;
  // the table's column heads, known before any figure: the reading table carries them too, so it reads as itself
  const COLS = `<div class="pthead pcols" role="row"><span class="pf cmd">Command</span><span class="pf">Requests</span><span class="pf">Failed</span>`
    + `<span class="pf ure-time">Time, log scale</span><span class="pf num" title="Counts every request, failed ones included">Typical</span><span class="pf num" title="Counts every request, failed ones included">Slowest 5%</span></div>`;
  // the failure rate that reads as hurting (red) and as worth a look (Key Light); below, the bar stays neutral
  const BAD = 0.05, WATCH = 0.01;

  async function read() {
    const gen = ++st.gen, win = winNow();
    st.reading = true; st.readFor = win;
    // the compactions ledger beside the summary (TurnsMount /api/compact): its own counts by outcome, which perf rows lack
    const [r, c] = await Promise.all([API.get(`/api/perf/summary?window=${win}`), API.get("/api/compact")]);
    if (gen !== st.gen) return; // a newer window was asked for while this one was read
    st.reading = false;
    st.body = r.ok ? r.body : null;
    st.compact = c.ok ? c.body?.stats?.heads ?? null : null;
    st.error = r.ok ? null : WHY_NOT[r.status] ?? (r.status ? "splice did not send it" : "splice did not answer");
    if (!r.ok) console.warn(`perf summary: ${r.status}`);
    render();
  }

  // Requests in the table's window, every project, for one command and what else names the door
  const door = (key, extra = {}) => `requests.html?${new URLSearchParams({ win: winNow(), project: "", cmd: cmdOf(key), ...extra })}`;
  // a read that failed, in splice's words, as the console says a page that did not open (console.js)
  const WHY_NOT = { 500: "splice failed to send it", 502: "splice is not ready", 503: "splice is not ready", 504: "splice did not answer" };
  const pct = (x) => (x == null ? null : x > 0 && x < 0.005 ? "<1%" : `${Math.round(x * 100)}%`);
  // a failure rate as printed: one decimal under 10% (1.4%, 0.8%) so two rows never both read 1% with one tinted, whole
  // numbers from 10%. [shown] is the number the row prints, and the tint is read from it, never from the raw share.
  const shown = (x) => (x == null ? null : x >= 0.0995 ? Math.round(x * 100) : Math.round(x * 1000) / 10);
  const rate = (x) => { const s = shown(x); return s == null ? null : s === 0 ? (x > 0 ? "<0.1%" : "0%") : `${x >= 0.0995 ? s : s.toFixed(1)}%`; };
  const dash = DRAW.dash;
  const failedOf = (h) => Object.entries(h.outcomes || {}).reduce((a, [tag, k]) => a + (isFailed(tag) ? k : 0), 0);
  const shareOf = (h) => h.failure_share ?? (h.count > 0 ? failedOf(h) / h.count : 0);
  const level = (share) => { const s = shown(share) / 100; return s >= BAD ? "bad" : s >= WATCH ? "watch" : "ok"; };

  /** Failures by kind, the largest count first, in the words every page uses for an outcome. Tags that read as one word
   *  are one reason with their counts summed: error:rate-limited, error:rate-limit-relayed and failure:rate_limit_error
   *  all read "Rate limited" (kit.js OUTCOME_WORD), and a row listed it twice. The reason
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

  // The panel's shapes are the KIT's now (kit.js DRAW, console.css's drawings block): this file names the figures and
  // the words, and DRAW draws them. Each is drawn from the summary's own figures; an absent or zero figure draws nothing.
  const STEP = ["f0", "f1", "f2", "f3", "f4", "f5"];   // failure blocks by rank: the largest is the full limit red, the rest step down, then take patterns of it
  const GLYPH = DRAW.glyph;
  // The counts the stat strip carries, each shown only above zero, in the order the panel reads them.
  const FACTS = [
    { label: "Most at once", glyph: "peak", has: (h) => h.peak_inflight > 0, n: (h) => h.peak_inflight },
    { label: "Retries", glyph: "retry", has: (h) => h.retries > 0, n: (h) => h.retries },
    { label: "Cold cache", glyph: "cold", has: (h) => h.cache_cold > 0, n: (h) => h.cache_cold },
    { label: "Sign-ins renewed", glyph: "key", has: (h) => h.refreshes > 0, n: (h) => h.refreshes },
    // The spec's count (§3), shown as "Record gaps" by a recorded Deviation: requests during which splice dropped some of
    // the records it keeps on this disk. Telemetry reads as data leaving the machine, and these are local writes. The lost
    // writes are other records, never the row that counts them, so no Requests row carries a mark.
    { label: "Record gaps", glyph: "gap", has: (h) => h.telemetry_dropped > 0, n: (h) => h.telemetry_dropped },
  ];
  /** The command's whole population: the failure rate as printed, over one bar of every request in the window. The ok
   *  share and the other clean outcomes stay in the neutral part; each failure kind is a block of the limit red, and the
   *  failed total opens every failure on Requests. */
  const heroSection = (h, marks = "") => {
    const n = failedOf(h), share = shareOf(h), lv = level(share);
    const others = Object.entries(h.outcomes || {}).filter(([t, k]) => k > 0 && t !== "ok" && !isFailed(t)).sort((a, b) => b[1] - a[1]);
    const ok = Math.max(0, h.count - n - others.reduce((a, [, k]) => a + k, 0));
    const segs = [
      { n: ok, word: PLAIN.ok, cls: "ok", href: door(h.key, { tag: "ok" }) },
      ...others.map(([t, k]) => ({ n: k, word: OUTCOME_WORD[t] || PLAIN[t] || t.replace(/_/g, " "), cls: "nt", href: door(h.key, { tag: t }) })),
      ...kindsOf(h).map((k, i) => ({ n: k.n, word: k.word, cls: STEP[Math.min(i, STEP.length - 1)], href: door(h.key, { tag: k.tags.join(",") }) })),
    ];
    const hero = DRAW.hero(n > 0
      ? { figure: rate(share) ?? "0%", level: lv, count: n, word: "failed", of: `of ${n0(h.count)} requests`, href: door(h.key, { outcome: "fail" }) }
      : { figure: "0%", level: lv, of: `No failures of ${n0(h.count)} requests` });
    // Every request is one bar: the answered part carries its word where it fits, the failed requests are one block in the
    // limit red (the lead above says how many), the rest is quiet. Under it the failed requests are a whole of their own,
    // split by kind, so a kind that is a sliver of every request still reads as its share of the failures, with its word and
    // count inside where they fit and in the row under it where they do not. What neither answered nor failed is named there too.
    const kinds = kindsOf(h);
    if (!(n > 0) || !kinds.length || !(h.count > 0)) return DRAW.section("Outcomes", hero + DRAW.stack(segs, h.count), { cls: "pd-hero", aside: marks });
    const shareOfAll = (k) => (k / h.count) * 100, block = (cls, k, href, text) => `<${href ? "a" : "span"} class="pd-seg ${cls}"${href ? ` href="${href}"` : ""} style="width:max(${shareOfAll(k).toFixed(3)}%, .16rem)" title="${esc(text)}">${text && cls === "ok" && shareOfAll(k) >= 25 ? `<span>${esc(PLAIN.ok)} <b>${DRAW.n(k)}</b></span>` : ""}</${href ? "a" : "span"}>`;
    const whole = `<div class="pd-pop"><div class="pd-bar" role="img" aria-label="${esc(`${n0(ok)} answered, ${n0(n)} failed of ${n0(h.count)} requests`)}">`
      + block("ok", ok, door(h.key, { tag: "ok" }), `${PLAIN.ok} ${DRAW.n(ok)}`)
      + others.map(([t, k]) => block("nt", k, door(h.key, { tag: t }), `${OUTCOME_WORD[t] || PLAIN[t] || t.replace(/_/g, " ")} ${DRAW.n(k)}`)).join("")
      + block("f0", n, door(h.key, { outcome: "fail" }), `Failed ${DRAW.n(n)}`) + `</div></div>`;
    const quiet = others.map(([t, k]) => `<a class="pd-lb" href="${door(h.key, { tag: t })}">${esc(OUTCOME_WORD[t] || PLAIN[t] || t.replace(/_/g, " "))} <b>${DRAW.n(k)}</b></a>`);
    const lone = shareOfAll(ok) < 25 ? `<a class="pd-lb" href="${door(h.key, { tag: "ok" })}"><i class="pd-sw ok"></i>${esc(PLAIN.ok)} <b>${DRAW.n(ok)}</b></a>` : "";
    const neither = others.length ? `<div class="pd-lgrp"><span class="pd-neither"><i class="pd-sw nt"></i>Neither <b>${DRAW.n(others.reduce((a, [, k]) => a + k, 0))}</b>:</span> ${quiet.join(`<span class="pd-comma">, </span>`)}</div>` : "";
    const split = DRAW.stack(kinds.map((k, i) => ({ n: k.n, word: k.word, cls: STEP[Math.min(i, STEP.length - 1)], href: door(h.key, { tag: k.tags.join(",") }) })), kinds.reduce((a, k) => a + k.n, 0), { tail: lone + neither });
    return DRAW.section("Outcomes", hero + whole + split, { cls: "pd-hero", aside: marks });
  };

  /** The stat strip: the cache hit as a ring, then the counts that are above zero, each a small glyph, its number and its
   *  name on one baseline. The status labels are the ones splice wrote itself from the last tool call with no model
   *  (PerfKeys.ACTIVITY_QUERY, ActivityLabel.kt), so they cost nothing. */
  const statStrip = (h, facts) => {
    const r = h.cache_hit_ratio, q = h.kinds?.activity_query ?? 0;
    const ring = r > 0 ? DRAW.statTile(DRAW.ring(r, { size: "focal", label: `Cache hit ${pct(r)}` }), pct(r), "Cache hit", { focal: true }) : "";
    const tiles = [ring, ...facts.filter((c) => c.glyph && c.has(h)).map((c) => DRAW.statTile(GLYPH[c.glyph], n0(c.n(h)), c.label)),
      q > 0 ? DRAW.statTile(GLYPH.label, n0(q), "Status labels", { title: "Written by splice, no model, $0" }) : ""].filter(Boolean);
    return tiles.length ? DRAW.section("Cache and activity", DRAW.tiles(tiles)) : "";
  };

  /** The stages of a request, for the ones this command measured, on one log axis: the solid bar runs to the typical time,
   *  the light bar on to the slowest 5%, whose end is the tick. "Waited in line" is the time the gate held a request
   *  behind its command's full limit, before splice sent it (queue_wait_ms, from admit_held_ms): only held requests carry
   *  it, so the times are theirs and the line under the name says how many waited. Each stage is its own subset of
   *  requests, so the stages are never drawn as adding up to the total. */
  const stagesSection = (h, flag = null) => {
    const q = h.queue_wait_ms, rows = [
      q?.p50 != null ? ["Waited in line", q, `${n0(q.count)} of ${n0(h.count)} requests`] : null,
      h.time_before_first_byte_ms?.p50 != null ? ["Before first byte", h.time_before_first_byte_ms] : null,
      h.time_streaming_ms?.p50 != null ? ["Streaming", h.time_streaming_ms] : null,
    ].filter(Boolean);
    if (!rows.length) return "";
    // a slow flag compares the command's typical time before today with the one since it began: both are marks on this axis,
    // so the axis runs far enough to hold them
    const marks = flag?.kind === "slow" && flag.typ && flag.now ? [[flag.typ, "before today", false], [flag.now, "since then", true]] : [];
    const ax = DRAW.axis([...rows.map(([, s]) => s.p50), ...marks.map(([ms]) => ms)], [...rows.map(([, s]) => s.p95 ?? s.p50), ...marks.map(([ms]) => ms)]), longest = Math.max(...rows.map(([, s]) => s.p50));
    const body = rows.map(([label, s, sub]) => {
      const hi = s.p95 ?? s.p50;
      // the slowest cell's class was a bare "s", which console.css draws as a LIST CARD: it inherited a card's padding
      // and a .14 s transition, and "1 min 15 s" ran past the right end of its header. "hi5" is
      // the same cell with nothing else landing on it.
      return `<div class="pd-st pd-grid${s.p50 === longest ? " pd-focus" : ""}"><span class="pd-sl">${label}${sub ? `<small>${sub}</small>` : ""}</span>`
        + DRAW.range(ax, s.p50, hi)
        + `<span class="pd-n t">${secs(s.p50)}</span><span class="pd-n hi5">${s.p95 == null ? dash : secs(s.p95)}</span></div>`;
    }).join("");
    const mk = marks.map(([ms, word, now]) => `<i class="ure-mark${now ? " now" : ""}${ax.at(ms) > 60 ? " end" : ""}" style="left:${ax.at(ms).toFixed(2)}%"><b>${secs(ms)}<span> ${word}</span></b></i>`).join("");
    const axis = DRAW.axisRow(ax, "Time, log scale", "<span></span><span></span>")
      .replace(/<\/span><\/span><span><\/span><span><\/span><\/div>$/, `</span>${mk}</span><span></span><span></span></div>`);
    return DRAW.section("Stages", body + axis,
      { cls: "pd-stages", head: "pd-grid", aside: `${DRAW.rangeLegend}<span class="pd-n">Typical</span><span class="pd-n">Slowest 5%</span>` });
  };

  /** How the command's compactions ended over the last seven days, from the compactions ledger (CompactPayloads'
   *  by_outcome_7d): it keeps its own week whatever window the table reads, so the bar names its span. The count beside the
   *  heading is the table's window, named, and opens the compactions on Requests with its Compactions toggle on; the bar is its
   *  own seven days, named, with its own total, so the two never read as one figure. Only where there
   *  are any; the spec counts turn kind per command (v0.4.0 §3). */
  const turnsSection = (h) => {
    const n = h.kinds?.compaction ?? 0;
    const by = Object.entries(st.compact?.[h.key]?.by_outcome_7d ?? {}).filter(([, k]) => k > 0)
      .sort((a, b) => (b[0] === "model_text") - (a[0] === "model_text") || b[1] - a[1]);
    if (!(n > 0) && !by.length) return "";
    const count = n > 0 ? DRAW.countDoor(n, `sent in ${WINDOWS[winNow()]}`, door(h.key, { compact: "1" })) : "";
    const total = by.reduce((a, [, k]) => a + k, 0);
    let p = 0;
    const segs = by.map(([o, k]) => ({ n: k, word: ENDED[o] ?? o.replace(/_/g, " "), cls: o === "model_text" ? "ok" : STEP[Math.min(p++, STEP.length - 1)] }));
    return DRAW.section("Compactions", by.length ? DRAW.focal(n0(total), "ended, last 7 days") + DRAW.stack(segs, total, { small: true }) : "", { aside: count });
  };

  // a model id carrying its release date (claude-haiku-4-5-20251001) reads by its name, never the date
  // a request with no model ended before splice picked one, cut by a restart or refused first (no_request rows; live
  // Oct 13, claudex's 23 were all error:restarted), so its row says that and never reads as a model of that name
  const modelWord = (id) => (id === "?" ? "Ended before a model was picked" : modelName(S.labels, id.replace(/-\d{8}$/, "")) ?? id);
  const answered = (id, m) => id !== "?" && m.failure_share < 1 && m.total_ms?.p50 != null;
  const byCount = (h) => Object.entries(h.models || {}).sort((x, y) => y[1].count - x[1].count);
  /** Each model a command answered with, where it used more than one, as small cards on shared scales: the requests on the
   *  busiest model's count and the failure rate on 0 to 100%, as the table's own bars are, and the typical to slowest 5%
   *  on one log axis across the cards. A model that fails is the loud card, in the limit red. A time needs an answer: a
   *  request that named no model failed before one answered, and a model every request to failed never answered, so
   *  neither prints one. The three biggest by requests are shown and the rest sit behind "N more". A
   *  command with one model has no cards: its own figures already say it. */
  const modelsSection = (h) => {
    const ms = byCount(h);
    if (ms.length < 2) return "";
    // the one card that reads loud: the model failing most, when its rate is worth a look at all. It is always among the
    // cards shown, after the busiest, so the model that hurts is never behind "N more".
    const worst = ms.reduce((w, e) => (e[1].failure_share > (w?.[1].failure_share ?? 0) ? e : w), null);
    const loud = worst && level(worst[1].failure_share) !== "ok" ? worst[0] : null;
    // a command with four models shows four, never three and "1 more": a card standing alone under a row of three reads as lost
    const top = ms.slice(0, ms.length <= SHOWN_MODELS + 1 ? ms.length : SHOWN_MODELS);
    if (loud && !top.some(([id]) => id === loud)) top.push(worst);
    const open = st.models.has(h.key), shown = open ? ms : top, rest = ms.length - top.length;
    const most = Math.max(1, ms[0][1].count);
    const timed = shown.filter(([id, m]) => answered(id, m));
    const ax = timed.length ? DRAW.axis(timed.map(([, m]) => m.total_ms.p50), timed.map(([, m]) => m.total_ms.p95 ?? m.total_ms.p50)) : null;
    const cards = shown.map(([id, m]) => {
      const lv = level(m.failure_share), ok = answered(id, m);
      const reqW = (m.count / most) * 100, failW = m.failure_share > 0 ? Math.max(1.5, m.failure_share * 100) : 0;
      const t = ok ? m.total_ms : null, hi = t ? t.p95 ?? t.p50 : null;
      const time = t
        ? `${DRAW.range(ax, t.p50, hi)}<span class="pd-ends"><span><small>Typical</small><b>${secs(t.p50)}</b></span><span><small>Slowest 5%</small><b>${t.p95 == null ? dash : secs(t.p95)}</b></span></span>`
        : `<span class="pd-trk"></span><span class="pd-ends"><span><small>Typical</small><b>${dash}</b></span><span><small>Slowest 5%</small><b>${dash}</b></span></span>`;
      const body = `<div class="pd-pair">${DRAW.miniPair({ label: "Requests", figure: n0(m.count), w: reqW })}`
        + `${DRAW.miniPair({ label: "Failed", figure: rate(m.failure_share) ?? "0%", w: failW, cls: "fail", level: lv })}</div>`
        + `<div class="pd-time">${time}</div>`;
      return DRAW.multiCard({ name: modelWord(id), level: lv, loud: id === loud, body });
    }).join("");
    const more = rest > 0 ? DRAW.more(rest, { word: `more ${rest === 1 ? "model" : "models"}`, act: "perfmods", data: ` data-k="${esc(h.key)}"`, open, less: "Fewer models" }) : "";
    return DRAW.section("Models", `<div class="pd-cards" style="--k:${Math.min(shown.length, 4)}">${cards}</div>${more}`, { aside: DRAW.rangeLegend });
  };

  /** What the record behind a row could not cover, in the fewest words, as marks on the Outcomes line the counts belong
   *  to; the cause goes to the console. */
  function gaps(h) {
    const out = [];
    if (h.read_error || h.coverage_known === false || h.skipped_lines) {
      // what the summary says could not be read (PerfSummary: an unparseable perf row is skipped and left out of every count;
      // a generation that could not be read leaves the window's coverage unknown), in words, never a bare "Partly read"
      if (h.read_error || h.coverage_known === false) out.push("Part of the log unread, counts may be low");
      if (h.skipped_lines) out.push(`${n0(h.skipped_lines)} ${h.skipped_lines === 1 ? "record" : "records"} unread, not counted`);
      const said = `${winNow()} ${h.key}`;
      if (!st.warned.has(said)) st.warned.add(said) && console.warn(`perf ${h.key}: ${h.read_error ?? ""} coverage_known=${h.coverage_known} skipped_lines=${h.skipped_lines ?? 0}`);
    }
    if (h.clamped) out.push(`Last ${h.covers_ms >= 3600e3 ? `${Math.floor(h.covers_ms / 3600e3)} h` : `${Math.max(1, Math.floor(h.covers_ms / 60e3))} min`} only`);
    return out;
  }
  /** Slow or failing today, as the Commands cards said it; only with no pick, since the rows behind it follow the pick. */
  // a failing burst is named only when it falls inside the window the table reads, so the flag never names failures the table's own rate shows as none
  const SPAN = { "1h": 3600e3, "24h": 86400e3, "7d": 604800e3 };
  const flagFor = (h) => {
    if (ui.project != null || hasFilter()) return null;
    const f = flagOf(h.key);
    return f?.kind === "failing" && f.to < Date.now() - SPAN[winNow()] ? null : f;
  };

  function detail(h, facts) {
    const f = flagFor(h), marks = gaps(h).map((x) => `<span class="pgap">${DRAW.glyph.gap}${esc(x)}</span>`).join("");
    // the figures stand in two columns where the plate holds two of them (what failed and how the cache did; what was
    // compacted and how long the stages took), and the models run the whole width under both
    const left = heroSection(h, marks) + statStrip(h, facts), right = turnsSection(h) + stagesSection(h, f);
    const body = (f ? `<p class="pnote">${flagHtml(h.key, f)}</p>` : "")
      + (right ? `<div class="pd-cols"><div class="pd-col">${left}</div><div class="pd-col">${right}</div></div>` : left) + modelsSection(h);
    // a panel rises in once, on the draw that opened it, never on a redraw
    return DRAW.plate(body, `pdetail${st.opened === h.key ? " unfold" : ""}`, `perf-${h.key}`);
  }

  function item(h, ctx, rank) {
    const open = st.open.has(h.key), share = shareOf(h), n = failedOf(h), lv = level(share), f = flagFor(h);
    const shownRate = n > 0 ? rate(share) ?? "0%" : "0%";
    const reqW = ctx.max > 0 ? Math.max(2, (h.count / ctx.max) * 100) : 0;
    const failW = n > 0 ? Math.max(2, share * 100) : 0;
    const typ = h.total_ms?.p50, slow = h.total_ms?.p95;
    // THE COMMAND'S OWN NAME, the one the rest of the page uses (cmdOf): the table read "glml53" where the chart's
    // legend, the Breakdown and Tokens all read "claude-glml53", so one command read as two things on one page.
    const name = `<i class="dot-c" aria-hidden="true"></i><span class="nm">${esc(cmdOf(h.key))}</span>`;
    return `<div class="pitem${open ? " open" : ""}" role="rowgroup" style="--c:${colorOf(providerOfHead(h.key))}">`
      + `<div class="prow pcols" role="row" data-act="perfopen" data-k="${esc(h.key)}">`
      + `<span class="pf cmd" role="cell"><span class="upl-rank" aria-hidden="true">${rank}</span>${DRAW.disc({ open, controls: `perf-${esc(h.key)}`, inner: name, cls: "plbl" })}</span>`
      + DRAW.barCell({ w: reqW, text: n0(h.count), label: "Requests", cls: "pf req", role: "cell" })
      + DRAW.barCell({ w: failW, text: shownRate, label: "Failed", cls: "pf fail", level: lv, role: "cell" })
      + `<span class="pf ure-time" role="cell"><em>Time, log scale</em>${typ == null ? `<span class="pd-trk ure-none"></span>` : DRAW.range(ctx.ax, typ, slow ?? typ)}</span>`
      + `<span class="pf num tm${f?.kind === "slow" ? " slow" : ""}" role="cell"><em>Typical</em>${typ == null ? dash : secs(typ)}</span>`
      + `<span class="pf num tm" role="cell"><em>Slowest 5%</em>${slow == null ? dash : secs(slow)}</span>`
      + `</div>${open ? detail(h, ctx.facts) : ""}</div>`;
  }

  /** A command with no request in the window keeps its row, where its figures would be it says so once, and the row opens
   *  that command's requests of the last 7 days. */
  const idleRow = (h) => `<div class="pitem" role="rowgroup" style="--c:${colorOf(providerOfHead(h.key))}"><a class="prow pcols pidle" role="row" href="${door(h.key, { win: "7d" })}">`
    + `<span class="pf cmd" role="cell"><span class="upl-rank" aria-hidden="true"></span><span class="ure-gap" aria-hidden="true"></span><i class="dot-c" aria-hidden="true"></i><span class="nm">${esc(cmdOf(h.key))}</span></span>`
    + `<span class="pf ure-say" role="cell">No requests${OPEN}</span></a></div>`;
  function html() {
    // a window other than the one on screen reads again at once, and its rows are stripes until it lands, never the last
    // window's figures under the new one's switch
    const win = winNow();
    if (st.readFor !== win || (!st.body && !st.error && !st.reading)) { st.body = null; st.error = null; read(); }
    const sw = DRAW.switcher(Object.entries(WINDOWS), win, "perfwin", "Window");
    // the project and filters picked above do not narrow this table: a pill says it, and its title says why
    const scoped = ui.project != null || hasFilter() ? `<span class="hn" title="The project and filters picked above do not narrow this table">All requests</span>` : "";
    const header = `<div class="bandhead"><h2>Results by command</h2>${scoped}${sw}</div>`;
    if (st.error) {
      return `<section class="perf-heads">${header}<div class="errline" role="alert">${ICON.bang}<span><b>Results didn't load</b> <span class="why">${esc(st.error)}</span></span>`
        + `<button class="act small" type="button" data-act="perfretry">Try again</button></div>`
        + `<div class="ptable card ure-held" aria-hidden="true">${COLS}${Array.from({ length: 3 }, () => `<div class="prow pcols"><span class="pf cmd"><span class="vbar unread" style="max-width:9rem"></span></span><span class="pf"><span class="vbar unread"></span></span><span class="pf"><span class="vbar unread"></span></span><span class="pf"><span class="pd-trk ure-none"></span></span><span class="pf"><span class="vbar unread"></span></span><span class="pf"><span class="vbar unread"></span></span></div>`).join("")}</div></section>`;
    }
    // READING: the table's own rows in stripes, as many as it last held, so the column keeps its height and nothing
    // under it moves when the window's figures land (a generic four-bar card stood where a six-row table lands). The
    // rows also wait for the page's own read of the commands: before it, a command has neither its name nor its
    // provider's colour, and the table would draw the daemon's keys on grey dots, then rename them in place.
    if (!st.body || !S.stood) {
      const rows = Array.from({ length: Math.max(3, st.rows) }, (_, i) =>
        `<div class="prow pcols gprow" style="--i:${i}"><span class="pf cmd">${GHOST.bar(8)}</span>`
        + `<span class="pf">${GHOST.track()}</span><span class="pf">${GHOST.track()}</span><span class="pf">${GHOST.track()}</span><span class="pf num">${GHOST.bar(3)}</span><span class="pf num">${GHOST.bar(3.4)}</span></div>`).join("");
      const idleGhost = Array.from({ length: st.idle }, (_, i) => `<div class="prow pcols gprow pidle" style="--i:${st.rows + i}"><span class="pf cmd">${GHOST.bar(8)}</span><span class="pf ure-say">${GHOST.bar(5)}</span></div>`).join("");
      return `<section class="perf-heads" aria-busy="true">${header}<div class="figline sub" aria-hidden="true">${GHOST.fig(2.6)}${GHOST.bar(9)}</div><div class="ptable card ghosted">${COLS}${rows}${idleGhost}</div></section>`;
    }
    const heads = st.body.heads || [];
    // the one that is hurting reads first: the highest failure rate, then the busiest
    const used = heads.filter((h) => h.count > 0).sort((a, b) => shareOf(b) - shareOf(a) || b.count - a.count);
    const idle = heads.filter((h) => !(h.count > 0));
    if (used.length) keepShape(used.length, idle.length);
    const timed = used.filter((h) => h.total_ms?.p50 != null);
    const ctx = { max: Math.max(0, ...used.map((h) => h.count)), facts: FACTS.filter((c) => used.some(c.has)),
      ax: timed.length ? DRAW.axis(timed.map((h) => h.total_ms.p50), timed.map((h) => h.total_ms.p95 ?? h.total_ms.p50)) : null };
    // THE TABLE'S ANSWER: the share of every request in the window that failed, and a door to those failures
    const all = used.reduce((a, h) => a + h.count, 0), bad = used.reduce((a, h) => a + failedOf(h), 0);
    const lead = used.length
      ? `<div class="ure-lead">${DRAW.hero(bad > 0
        ? { figure: rate(bad / all) ?? "0%", level: level(bad / all), count: bad, word: "failed", of: `of ${n0(all)} requests`, href: `requests.html?${new URLSearchParams({ win, project: "", outcome: "fail" })}`, key: "perf-lead" }
        : { figure: "0%", level: "ok", of: `No failures in ${n0(all)} requests`, key: "perf-lead" })}</div>` : "";
    // Every command keeps its row: the ones that answered in worst-first order, numbered, then the ones with no request,
    // each saying so once where its figures would be. A window nothing answered in is those rows alone.
    const rowsHtml = used.map((h, i) => item(h, ctx, i + 1)).join("") + idle.map(idleRow).join("");
    const table = heads.length
      ? `<div class="ptable card" role="table">${COLS}${rowsHtml}</div>`
      : `<div class="card ptable pnone">${DRAW.nothing("No requests")}</div>`;
    return `<section class="perf-heads">${header}${lead}${table}</section>`;
  }

  document.addEventListener("click", (e) => {
    const toggle = (set, k) => { set.has(k) ? set.delete(k) : set.add(k); render(); };
    const mo = e.target.closest("[data-act=perfmods]");
    if (mo) { toggle(st.models, mo.dataset.k); return; }
    const row = e.target.closest("[data-act=perfopen]");
    if (row && !e.target.closest("a")) {
      const k = row.dataset.k;
      st.opened = st.open.has(k) ? null : k;
      toggle(st.open, k); st.opened = null; return;
    }
    if (e.target.closest("[data-act=perfretry]")) { st.error = null; st.body = null; st.reading = false; render(); return; }
    const el = e.target.closest("[data-act=perfwin]");
    if (!el || el.dataset.v === winNow()) return;
    st.pick = el.dataset.v; st.body = null; st.error = null; st.reading = false;
    render();
  });
  /** The table's answer as the header's door reads it, or null before it has been read: the failed share and its level, or that
   *  there were no requests at all, with the window it was read over (its key and its words). */
  function lead() {
    const win = winNow();
    if (!st.body || st.error || st.readFor !== win) return null;
    const over = { win, word: WINDOWS[win] };
    const used = (st.body.heads || []).filter((h) => h.count > 0);
    if (!used.length) return { none: true, ...over };
    const all = used.reduce((x, h) => x + h.count, 0), bad = used.reduce((x, h) => x + failedOf(h), 0);
    return { figure: rate(bad / all) ?? "0%", level: level(bad / all), ...over };
  }
  return { html, lead };
};
