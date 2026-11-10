// Shared performance view inside Usage and Requests. Every population, rate and percentile is server-owned.
"use strict";
const PERF_CONSOLE = (() => {
  const FILTERS = ["head", "model", "transport", "outcome", "session", "project", "account", "local", "compact", "streaming"];
  const SELECTORS = [...FILTERS, "silence_ms", "queued_ms", "resumed", "started_over", "unattributed", "test"];
  const WINDOWS = { "1m": 60000, "5m": 300000, "15m": 900000, "1h": 3600000, "5h": 18000000, "24h": 86400000, "7d": 604800000, "30d": 2592000000 };
  const PERIOD = { "1m": "minute", "5m": "5 minutes", "15m": "15 minutes", "1h": "hour", "5h": "5 hours", "24h": "24 hours", "7d": "7 days", "30d": "30 days" };
  const LABELS = { transport: "Transport", outcome: "Outcome", session: "Session", account: "Account", silence_ms: "Silence · ms", queued_ms: "Waited in line · ms",
    resumed: "Resumed", started_over: "Started over", unattributed: "Unattributed", test: "Test traffic", local: "Local steps", compact: "Compactions", streaming: "Streaming" };
  const ARTIFACT_REFUSALS = ["control_query_capacity", "control_query_deadline", "performance_io_failure", "invalid_profile_id", "artifact_reader_capacity", "profile_artifact_unavailable"];
  const perfTransport = API;
  // The four query projections also share one worker across panel generations and page mounts.
  let queryRunning = false, queryWaiting = null;
  function drainQuery() {
    if (queryRunning || !queryWaiting) return;
    const next = queryWaiting; queryWaiting = null;
    if (!next.current()) { next.resolve(null); return; }
    queryRunning = true;
    Promise.resolve().then(() => next.transport.perfRequest("perf:shared-query", "GET", next.path, undefined, next.current)).then(next.resolve, next.reject)
      .finally(() => { queryRunning = false; drainQuery(); });
  }
  function queryRead(path, current, transport = perfTransport) {
    return new Promise((resolve, reject) => {
      // Keep one active read and only the newest pending projection, including rapid refreshes.
      queryWaiting?.resolve(null);
      queryWaiting = { path, current, transport, resolve, reject };
      drainQuery();
    });
  }
  const safe = (v) => String(v ?? "").replace(/[&<>"']/g, (c) => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" })[c]);
  // A figure as the eye uses it (Nov 10): whole numbers from 100 up, three significant digits below, so a rate reads 385
  // and 0.00221, never 384.628; counts keep every digit. A share reads one decimal under 10% and whole from there.
  const num = (v) => typeof v === "number" && Number.isFinite(v) ? v.toLocaleString("en-US", Number.isInteger(v) || Math.abs(v) >= 100 ? { maximumFractionDigits: 0 } : { maximumSignificantDigits: 3 }) : "—";
  const percent = (v) => typeof v === "number" && Number.isFinite(v) ? v > 0 && v < .0005 ? "<0.1%" : `${(v * 100).toLocaleString("en-US", { maximumFractionDigits: v >= .0995 ? 0 : 1 })}%` : "—";
  // a per-second rate in the unit that gives it a readable figure: 0.00221 a second is 7.96 an hour
  const perTime = (v) => typeof v !== "number" || !Number.isFinite(v) ? "—" : v === 0 || v >= 1 ? `${num(v)} a second` : v * 60 >= 1 ? `${num(v * 60)} a minute` : `${num(v * 3600)} an hour`;
  const words = (v) => String(v ?? "Unknown").replace(/_/g, " ");
  const reasonText = (v) => v === "cursor_work_limit"
    ? "The request timeline reached its work limit. Narrow the time range or filters, then refresh."
    : words(v);
  const date = (v) => Number.isFinite(v) ? new Date(v).toLocaleString("en-US", { timeZone: "America/Chicago" }) : "Unknown time";
  // a moment as a person says it: the clock alone today, the weekday and date before that
  const when = (v, sayToday = false) => {
    if (!Number.isFinite(v)) return "Unknown time";
    const d = new Date(v), today = d.toLocaleDateString("en-US", { timeZone: "America/Chicago" }) === new Date().toLocaleDateString("en-US", { timeZone: "America/Chicago" });
    const at = d.toLocaleString("en-US", { timeZone: "America/Chicago", hour: "numeric", minute: "2-digit", ...(today ? {} : { weekday: "short", month: "short", day: "numeric" }) });
    return today && sayToday ? `today ${at}` : at;
  };
  // the browser's datetime-local value for an instant (its own zone, to the second), and back
  const localInput = (v) => Number.isFinite(v) ? new Date(v - new Date(v).getTimezoneOffset() * 60000).toISOString().slice(0, 19) : "";
  const entries = (v) => v && typeof v === "object" && !Array.isArray(v) ? Object.entries(v) : [];
  const scalar = (v) => typeof v === "number" ? num(v) : typeof v === "boolean" ? String(v) : v == null ? "—" : safe(words(v));
  const note = (s) => `<p class="pc-note">${safe(s)}</p>`;
  const table = (heads, rows, caption) => `<div class="pc-table-wrap" tabindex="0" role="region" aria-label="${safe(caption)}"><table class="pc-table"><caption>${safe(caption)}</caption><thead><tr>${heads.map((h) => `<th scope="col">${safe(h)}</th>`).join("")}</tr></thead><tbody>${rows.map((r) => `<tr>${r.map((v, i) => `<${i ? "td" : 'th scope="row"'}>${v}</${i ? "td" : "th"}>`).join("")}</tr>`).join("")}</tbody></table></div>`;
  const facts = (v) => `<dl class="pc-facts">${entries(v).filter(([, x]) => x == null || ["string", "number", "boolean"].includes(typeof x)).map(([k, x]) => `<div><dt>${safe(words(k))}</dt><dd>${scalar(x)}</dd></div>`).join("")}</dl>`;
  const section = (title, body) => `<section class="pc-section"><h3>${safe(title)}</h3>${body}</section>`;
  const detail = (title, body) => `<details class="pc-detail"><summary>${safe(title)}</summary>${body}</details>`;
  const known = (v) => typeof v === "number" && Number.isFinite(v);
  const duration = (v) => !known(v) ? "Not measured" : v < 1000 ? `${num(v)} ms` : v < 60000 ? `${num(v / 1000)} s` : v < 3600000 ? `${num(v / 60000)} min` : v < 86400000 ? `${num(v / 3600000)} h` : `${num(v / 86400000)} days`;
  const bytes = (v) => { if (!known(v)) return "Not measured"; const i = Math.min(4, Math.max(0, Math.floor(Math.log2(Math.max(1, v)) / 10))); return `${num(v / 1024 ** i)} ${["B", "KiB", "MiB", "GiB", "TiB"][i]}`; };
  const keyWords = (v) => words(String(v).replace(/([a-z])([A-Z])/g, "$1 $2").replace(/^runtime[_. ]/, "").replace(/\./g, " ")).replace(/\b(cpu|jvm|gc|rss|http|otlp|io)\b/gi, (x) => x.toUpperCase());
  const metricNames = { arrival_to_first_meaningful_content_ms: "First content", arrival_to_terminal_ms: "Whole request", queue_wait_ms: "Queue wait", client_flush_ms: "Client flush", first_content_ms: "First content", completion_ms: "Completion", progress_gap_ms: "Progress gap", queue_ms: "Queue wait" };
  const metricName = (k) => metricNames[k] || keyWords(k).replace(/ (ms|ns|bytes|ratio)$/, "");
  const reading = (k, v) => !known(v) ? "Not measured" : /bytes_per_second$/.test(k) ? `${bytes(v)} / s` : /bytes$/i.test(k) ? bytes(v) : /(_ns|Nanos)$/.test(k) ? duration(v / 1000000) : /(_ms|Millis|Ms)$/.test(k) ? duration(v) : /(_ratio|Ratio)$/.test(k) ? percent(v) : num(v);
  const badge = (label, kind = "neutral") => `<span class="pc-state pc-state-${kind}"><i aria-hidden="true"></i>${safe(label)}</span>`;
  const empty = (title, hint = "") => `<div class="pc-empty">${badge(title, "unknown")}${hint ? note(hint) : ""}</div>`;
  const track = (v, max, label) => `<span class="pc-track" role="img" aria-label="${safe(label)}"><i style="width:${known(v) && max > 0 ? Math.max(0, Math.min(100, v / max * 100)) : 0}%"></i>${v === 0 ? '<b class="pc-zero"></b>' : ""}</span>`;
  function bars(items, caption, unit = "", labelHtml = safe) {
    const values = items.map(([, v]) => known(v) ? v : 0), max = Math.max(0, ...values), min = Math.min(0, ...values), span = max - min;
    const drawn = (label, v) => min >= 0 ? track(v, max, `${label}: ${num(v)}${unit}; scale 0 to ${num(max)}${unit}`)
      : `<span class="pc-track" role="img" aria-label="${safe(`${label}: ${num(v)}${unit}; scale ${num(min)} to ${num(max)}${unit}`)}"><i style="position:absolute;left:${(Math.min(v, 0) - min) / span * 100}%;width:${Math.abs(v) / span * 100}%"></i><b class="pc-zero" style="left:${-min / span * 100}%"></b></span>`;
    return `<div class="pc-bars"><p class="pc-axis">${safe(caption)} <span>${num(min)} → ${num(max)}${safe(unit)}</span></p>${items.map(([label, v]) => `<div class="pc-bar-row"><span>${labelHtml(label)}</span>${known(v) ? drawn(label, v) : badge("Not measured", "unknown")}<strong>${known(v) ? `${num(v)}${safe(unit)}` : ""}</strong></div>`).join("")}</div>`;
  }
  function ranges(items) {
    const max = Math.max(1, ...items.map(([, h]) => h?.p99 ?? h?.max ?? h?.p95 ?? 0));
    const position = (v) => known(v) ? Math.max(0, Math.min(100, Math.log1p(Math.max(0, v)) / Math.log1p(max) * 100)) : 0;
    return `<div class="pc-ranges"><p class="pc-axis">Typical ▰ · p95 │ · p99 ● <span>Log scale · 0 → ${safe(duration(max))}</span></p>${items.map(([label, h]) => `<div class="pc-range-row"><span>${safe(label)}</span>${known(h?.p50) ? `<span class="pc-range" role="img" aria-label="${safe(label)}: typical ${safe(duration(h.p50))}, p95 ${safe(duration(h.p95))}, p99 ${safe(duration(h.p99))}; logarithmic scale"><b style="width:${position(h.p50)}%"></b>${known(h.p95) ? `<i style="left:${position(h.p95)}%"></i>` : ""}${known(h.p99) ? `<em style="left:${position(h.p99)}%"></em>` : ""}</span>` : badge("Not measured", "unknown")}<strong>${safe(duration(h?.p50))}</strong><small>p95 ${safe(duration(h?.p95))}</small></div>`).join("")}</div>`;
  }
  const issues = (v) => [...(v?.coverage?.issues || []), ...(v?.issues || [])].map((x) => typeof x === "string" ? x : x.code).filter(Boolean);
  const status = (r) => !r ? "Loading" : !r.ok ? r.status === 0 ? "Offline" : r.status === 404 ? "Unsupported" : "Error" : (words(r.body?.status || "ok").replace(/^./, (c) => c.toUpperCase()));
  const explain = (r) => r?.body?.error?.reason || r?.body?.error?.code || r?.body?.reason || issues(r?.body).join(" · ");
  function params(query) {
    const p = new URLSearchParams({ contract: "1", from: String(query.from_ms), until: String(query.until_ms), source: query.source || "auto" });
    for (const k of SELECTORS) if (query.filters?.[k] != null && query.filters[k] !== "") p.set(k, String(query.filters[k]));
    if (query.objective_id) p.set("objective", query.objective_id);
    return p;
  }
  function address(query, extra = {}, hash = "") {
    const p = params(query); p.set("perf", "1"); p.set("to", String(query.until_ms));
    for (const [k, v] of Object.entries(extra)) if (v != null) p.set(k, String(v));
    return `requests.html?${p}${hash ? `#${encodeURIComponent(hash)}` : ""}`;
  }
  // Keep the storage key so existing snapshots remain inspectable; exported documents are version 2.
  const STORE = "splice-performance-baselines-v1", LIMIT = 8, MAX_BYTES = 750000;
  const WORKLOAD_FIELDS = ["input_tokens", "output_tokens", "cached_tokens", "cache_write_tokens", "cache_write_1h_tokens", "absorbed_rounds", "cut_rounds", "no_request"];
  const WORKLOAD_SCOPE = ["head", "model", "transport", "work", "local", "compact", "streaming", "outcome"];
  const SEMANTICS = ["population", "filter_attribution", "input_tokens", "output_tokens", "cache_write_1h_tokens", "rounds", "matching_basis", "distribution_id", "scope", "presence", "assurance"];
  // Explicit projection: snapshots never contain rows, traces, prompts, headers, or arbitrary imported objects.
  function snapshot(summary, name = "Baseline") {
    if (summary?.contract_version !== 1 || !summary.query || !Array.isArray(summary.identity_cohorts)) throw new Error("Not a version 1 performance summary");
    let projectionTruncated = false;
    const text = (v) => { if (typeof v !== "string") return null; if (v.length > 256) projectionTruncated = true; return v.slice(0, 256); };
    const number = (v) => typeof v === "number" && Number.isFinite(v) ? v : null;
    const list = (v, limit = 64) => { if (!Array.isArray(v)) return []; if (v.length > limit) projectionTruncated = true; return v.slice(0, limit); };
    const strings = (v, limit = 64) => list(v, limit).map(text).filter((x) => x !== null);
    const keep = (obj, keys) => Object.fromEntries(keys.filter((k) => obj && Object.hasOwn(obj, k)).map((k) => [k, typeof obj[k] === "boolean" ? obj[k] : typeof obj[k] === "number" ? number(obj[k]) : text(obj[k])]));
    const metrics = (obj, keys) => Object.fromEntries(entries(obj).slice(0, 160).filter(([k]) => /^[a-z][a-z0-9_.]{0,95}$/.test(k)).map(([k, v]) => [k, { ...keep(v, [...keys, "filter_attribution"]), coverage: coverage(v.coverage), ...(Array.isArray(v.buckets) ? { buckets: v.buckets.slice(0, 128).map(number), bounds_ms: (v.bounds_ms || []).slice(0, 128).map(number) } : {}) }]));
    const q = summary.query;
    if (!Number.isFinite(q.from_ms) || !Number.isFinite(q.until_ms) || q.until_ms <= q.from_ms) throw new Error("Invalid baseline interval");
    const query = (v) => ({ ...keep(v, ["from_ms", "until_ms", "source", "objective_id", "objective_revision"]), filters: keep(v?.filters, SELECTORS) });
    const coverage = (v) => ({ ...keep(v, ["from_ms", "until_ms", "observed_ms", "complete", "as_of_ms", "coverage_basis", "event_time_final"]), issues: list(v?.issues).map((x) => keep(x, ["code", "source", "count"])), sinks: list(v?.sinks).map((x) => keep(x, ["sink", "observed", "enqueued", "written", "failed", "dropped", "pending", "oldestPendingMs", "lastSuccessEpochMs"])) });
    const evidence = summary.comparison, workload = evidence?.workload, provenance = evidence?.provenance;
    const comparisonEvidence = evidence ? {
      ...keep(evidence, ["status", "readiness_scope", "projection_truncated"]), reasons: strings(evidence.reasons),
      workload: { ...keep(workload, ["status", "population_complete", "observed_arrivals", "measured_requests", "matching_requests", "matching_basis", "distribution_id", "as_of_ms", "coverage_basis", "event_time_final"]), reasons: strings(workload?.reasons), truncated: workload?.truncated === true || (workload?.partitions?.length || 0) > 256,
        partitions: list(workload?.partitions, 256).map((p) => ({ scope: keep(p?.scope, WORKLOAD_SCOPE), ...keep(p, [...WORKLOAD_FIELDS, "count"]) })) },
      measurement_semantics: keep(evidence.measurement_semantics, SEMANTICS),
      provenance: { ...keep(provenance, ["source", "objective_id", "objective_revision", "configuration_status", "configuration_scope", "as_of_ms", "settled_until_ms"]), builds: strings(provenance?.builds, 128), versions: strings(provenance?.versions, 128), schema_versions: list(provenance?.schema_versions, 128).map(number), query: query(provenance?.query) },
      environment: keep(evidence.environment, ["provider_topology", "remote_environment", "causal_equivalence"]),
    } : null;
    const clean = {
      schema_version: 1, contract_version: 1, status: text(summary.status),
      query: query(q),
      as_of_ms: number(summary.as_of_ms), settled_until_ms: number(summary.settled_until_ms), requested_duration_ms: number(summary.requested_duration_ms), observed_duration_ms: number(summary.observed_duration_ms),
      identity_cohorts: summary.identity_cohorts.slice(0, 128).map((x) => keep(x, ["boot_id", "build", "version", "schema_version", "boot_config_revision", "effective_config_revision", "configuration_status"])),
      configuration: { ...keep(summary.configuration, ["status", "comparison_compatible", "comparison_scope", "requires_matching_provider_topology"]), excluded_dimensions: strings(summary.configuration?.excluded_dimensions), truncated: summary.configuration?.truncated === true || (summary.configuration?.intervals?.length || 0) > 512, intervals: list(summary.configuration?.intervals, 512).map((x) => keep(x, ["head", "boot_id", "from_ms", "until_ms", "revision"])) },
      comparison: comparisonEvidence,
      coverage: coverage(summary.coverage), population_coverage: Object.fromEntries(entries(summary.population_coverage).slice(0, 12).map(([k, v]) => [k.slice(0, 100), coverage(v)])),
      counts: keep(summary.counts, ["record_unit", "known_arrivals", "known_completions", "known_successful_completions", "population_complete", "arrivals", "completions", "attribution", "filter_attribution"]),
      rates: keep(summary.rates, ["arrivals_per_s", "completions_per_s", "successful_completions_per_s", "attempts_per_s", "input_tokens_per_s", "output_tokens_per_s", "cached_tokens_per_s", "upstream_bytes_per_s", "downstream_bytes_per_s", "upstream_events_per_s", "downstream_events_per_s", "denominator", "filter_attribution", "interpretation"]),
      goodput: keep(summary.goodput, ["status", "objective_id", "objective_revision", "attribution", "eligible", "known_evaluable", "observed_arrivals", "good", "bad", "pending", "unknown", "per_s", "known_good_per_s", "known_good_rate_is_lower_bound", "good_fraction", "population_complete"]),
      histograms: metrics(summary.histograms, ["count", "sum", "min", "max", "p50", "p95", "p99", "approximation"]),
      counters: metrics(summary.counters, ["count", "sum", "per_s"]), gauges: metrics(summary.gauges, ["last", "max", "time_weighted_mean", "covered_ms", "sample_count", "scope_count"]),
      missing_fields: (summary.missing_fields || []).slice(0, 200).map(text), resolution_ms: number(summary.resolution_ms), truncated: summary.truncated === true,
    };
    if (comparisonEvidence && projectionTruncated) comparisonEvidence.projection_truncated = true;
    return { format: "splice-performance-baseline", version: 2, name: String(name).slice(0, 80), saved_at_ms: Date.now(), summary: clean };
  }
  function importBaseline(b) {
    if (b?.format !== "splice-performance-baseline" || ![1, 2].includes(b.version)) throw new Error("Unsupported baseline format");
    const clean = snapshot(b.version === 1 ? { ...b.summary, comparison: null } : b.summary, b.name);
    clean.saved_at_ms = Number.isFinite(b.saved_at_ms) ? b.saved_at_ms : clean.saved_at_ms;
    return clean;
  }
  function loadBaselines() {
    try { const raw = localStorage.getItem(STORE) || "[]"; return raw.length <= MAX_BYTES ? JSON.parse(raw).slice(0, LIMIT).map(importBaseline) : []; } catch { return []; }
  }
  function comparison(a, b) {
    const unknown = [], different = [], changed = [], x = a.comparison, y = b.comparison;
    const known = (v) => v != null && !["", "unknown", "mixed", "?"].includes(v);
    const one = (items) => { const values = [...new Set(items || [])]; return values.length === 1 && known(values[0]) ? values[0] : null; };
    const compare = (name, left, right, variable = false) => {
      if (!known(left) || !known(right)) unknown.push(name);
      else if (left !== right) (variable ? changed : different).push(name);
    };
    compare("build", one(x?.provenance?.builds), one(y?.provenance?.builds), true);
    compare("measurement schema", one(x?.provenance?.schema_versions), one(y?.provenance?.schema_versions));
    const revision = (s) => s.configuration?.comparison_compatible === true && !s.configuration.truncated && s.configuration.comparison_scope === "effective_knob_policy" ? one(s.configuration.intervals?.map((i) => i.revision)) : null;
    compare("effective settings", revision(a), revision(b), true);
    compare("source", x?.provenance?.source, y?.provenance?.source);
    if (JSON.stringify(entries(a.query.filters).sort()) !== JSON.stringify(entries(b.query.filters).sort())) different.push("selected cohort");
    if (a.query.objective_id !== b.query.objective_id || a.query.objective_revision !== b.query.objective_revision) different.push("objective definition (goodput)");
    if ((a.query.objective_id && !known(a.query.objective_revision)) || (b.query.objective_id && !known(b.query.objective_revision))) unknown.push("objective revision");
    const partitionsKnown = (w) => Array.isArray(w?.partitions) && w.partitions.length > 0 && w.partitions.length <= 256 && !w.truncated && w.partitions.every((p) =>
      ["head", "model", "transport", "work", "outcome"].every((k) => typeof p.scope?.[k] === "string" && known(p.scope[k]))
      && ["local", "compact", "streaming"].every((k) => typeof p.scope?.[k] === "boolean")
      && WORKLOAD_FIELDS.every((k) => Number.isSafeInteger(p[k]) && p[k] >= 0) && Number.isSafeInteger(p.count) && p.count > 0);
    const workloadKnown = (c) => c?.status === "ready" && !c.projection_truncated && !c.reasons?.length && c.readiness_scope === "measured_workload_and_effective_knob_policy_only" && c.provenance?.configuration_scope === "effective_knob_policy"
      && c.workload?.status === "complete" && c.workload.population_complete === true && c.workload.matching_basis === "exact_joint_frequency_v1"
      && /^[a-f0-9]{64}$/.test(c.workload.distribution_id || "") && partitionsKnown(c.workload);
    if (!partitionsKnown(x?.workload) || !partitionsKnown(y?.workload)) unknown.push("input size", "output size", "cache partition", "resolved workload and outcome");
    if (!workloadKnown(x) || !workloadKnown(y)) unknown.push("complete measured workload");
    else compare("joint workload distribution", x.workload.distribution_id, y.workload.distribution_id);
    if (x?.projection_truncated || y?.projection_truncated) unknown.push("complete saved evidence");
    if (SEMANTICS.some((k) => !known(x?.measurement_semantics?.[k]) || !known(y?.measurement_semantics?.[k]))) unknown.push("measurement meanings");
    else if (SEMANTICS.some((k) => x.measurement_semantics[k] !== y.measurement_semantics[k])) different.push("measurement meanings");
    return { status: different.length ? "Incompatible" : unknown.length ? "Insufficient evidence" : "Measured workload matches", different, unknown, changed,
      reasons: [...new Set([...(x?.reasons || []), ...(y?.reasons || [])])].map(words) };
  }
  function create(scope, options) {
    const { document, API, setTimeout, clearTimeout, setInterval } = scope;
    const panelRead = (key, method, path, current, body, transport = API, opts = {}) =>
      transport.perfRequest(`perf:${options.id}:${key}`, method.toUpperCase(), path, body, current, { ...opts, replace: key !== "action" });
    const st = { key: null, query: null, replies: {}, tab: "overview", busy: false, rowsBusy: false, abort: null, timer: null, generation: 0, message: "", baselines: loadBaselines(), selected: 0, draft: null, draftRevision: 0, builders: new Map(), profiles: null, settings: null, openRequest: null, cursor: null, advanced: false, commands: new Set(), queryDraft: null,
      // the last hour against the last 7 days, read from the daemon's fold, and whether the second step is open: a page
      // opened from a performance link (contract=1, perf=1) names a cohort and lands with it open
      pulse: { now: null, usual: null, at: 0, reading: false, failed: false }, pulseTimer: null,
      deep: ["contract", "perf"].some((k) => new URLSearchParams(scope.location?.search ?? globalThis.location?.search ?? "").get(k) === "1") };
    const clearRows = () => { delete st.replies.rows; st.rowsQuery = null; };
    const clearMeasurements = () => { st.replies = Object.fromEntries(["schema", "caps", "preferences"].filter((k) => st.replies[k]).map((k) => [k, st.replies[k]])); st.rowsQuery = null; };
    const current = () => options.query();
    const accountChoices = () => [...new Set(String((st.query || queryNow()).filters?.account || "").split("\u001f").filter(Boolean))];
    const host = () => document.querySelector(`[data-performance="${options.id}"]`);
    const attached = () => host()?.isConnected;
    const actionPending = () => st.actionBusy || st.standingBusy || st.profileBusy;
    function rememberBuilders(h) {
      for (const form of h?.querySelectorAll(".pc-builder") || []) st.builders.set(form.dataset.pcForm, {
        open: form.closest("details").open,
        fields: [...form.elements].filter((el) => el.name).map((el) => ({ name: el.name, value: el.value, checked: el.checked })),
      });
    }
    function repaint() {
      const h = host();
      if (h) {
        const focus = document.activeElement, focusedForm = h.contains(focus) ? focus?.closest?.(".pc-builder") : null;
        rememberBuilders(h);
        h.innerHTML = inner();
        for (const form of h.querySelectorAll(".pc-builder")) {
          if (focusedForm?.dataset.pcForm === form.dataset.pcForm && focus.name) {
            const el = form.elements.namedItem(focus.name); el?.focus({ preventScroll: true });
            if (focus.selectionStart != null) el?.setSelectionRange(focus.selectionStart, focus.selectionEnd);
          }
        }
      }
      options.changed?.();
    }
    function resetBuilders() {
      st.builders.clear();
      for (const form of host()?.querySelectorAll(".pc-builder") || []) form.remove();
    }
    const builderValue = (kind, name, fallback) => st.builders.get(kind)?.fields.find((field) => field.name === name)?.value ?? fallback;
    const builderField = (kind, name, label, value = "", type = "text", attrs = "") => field(name, label, builderValue(kind, name, value), type, attrs);
    const builderSelect = (kind, name, label, choices, value) => select(name, label, choices, builderValue(kind, name, value));
    function queryNow() {
      const input = current(), filters = { ...input.filters };
      const query = { from_ms: input.from_ms, until_ms: input.until_ms, source: input.source || "auto", filters, objective_id: input.objective_id || null };
      if (!Number.isFinite(query.until_ms)) query.until_ms = Date.now();
      if (!Number.isFinite(query.from_ms)) query.from_ms = query.until_ms - (WINDOWS[input.window] || WINDOWS["24h"]);
      return query;
    }
    // The fingerprint excludes a moving "now"; one interval is frozen for all views in a read cycle.
    const fingerprint = () => JSON.stringify(current());
    async function read(force = false, refreshRows = false) {
      const key = fingerprint();
      if (st.busy && key === st.key && !force) return;
      if (st.rowsBusy && key === st.key && !force) { st.timer = setTimeout(() => read(), 15000); return; }
      st.abort?.abort(); clearTimeout(st.timer);
      const abort = st.abort = new AbortController(), api = API.scoped(abort.signal), gen = ++st.generation;
      const active = () => gen === st.generation && !abort.signal.aborted && host()?.isConnected;
      API.cancelPerf(`perf:${options.id}:`);
      API.cancelPerf("perf:shared-query");
      const changed = key !== st.key; st.key = key; st.query = queryNow(); st.busy = true; st.rowsBusy = false;
      if (!st.standingStarted) { st.standingStarted = true; standing(); }
      if (changed) { clearMeasurements(); st.cursor = null; st.openRequest = null; st.queryDraft = null; }
      else if (refreshRows) clearRows();
      if (accountChoices().length > 1) {
        clearMeasurements(); st.busy = false; st.advanced = true;
        st.replies.summary = { ok: true, status: 200, body: { contract_version: 1, status: "unsupported", query: st.query, error: { reason: "cohort_unavailable_choose_one_recorded_account" } } };
        repaint(); return;
      }
      repaint();
      const p = params(st.query).toString();
      const projections = { summary: `/api/perf/summary?${p}`, series: `/api/perf/series?${p}`, diagnose: `/api/perf/diagnose?${p}` };
      // A row cursor owns a fixed interval until the user refreshes or changes the cohort.
      if (st.tab === "timeline" && !st.replies.rows) {
        st.rowsQuery = st.query;
        projections.rows = `/api/perf/turns?${p}&page_size=100`;
      }
      const paths = { runtime: "/api/perf/runtime", alerts: `/api/perf/alerts?from=${st.query.from_ms}&until=${st.query.until_ms}`, alertStatus: "/api/perf/alerts/status", preferences: "/api/alerts" };
      const capacityRefusals = [];
      const queried = (async () => {
        for (const [key, path] of Object.entries(projections)) {
          if (!active()) return;
          const r = await queryRead(path, active);
          if (active()) {
            st.replies[key] = r;
            capacityRefusals.push({ key, path, projection: true, answer: r });
          }
        }
      })();
      await Promise.all([queried, ...Object.entries(paths).map(async ([key, path]) => {
        const r = await (key === "preferences" ? api.get(path) : panelRead(key, "get", path, active, undefined, api));
        if (active()) {
          st.replies[key] = r;
          if (key !== "preferences") capacityRefusals.push({ key, path, projection: false, answer: r });
        }
      })]);
      if (!active()) return;
      // Only the shared helper's exact capacity-refusal predicate retries, after this whole batch settles.
      const retried = await api.retryCapacity(capacityRefusals, active, (refused, retryApi, canStart) => refused.projection
        ? queryRead(refused.path, () => active() && canStart(), retryApi)
        : panelRead(refused.key, "get", refused.path, () => active() && canStart(), undefined, retryApi));
      for (const { item, answer } of retried) {
        if (active()) st.replies[item.key] = answer;
      }
      if (!active()) return;
      st.busy = false;
      if (st.replies.summary?.body?.query) {
        st.query = st.replies.summary.body.query;
        options.resolved?.(st.query);
      }
      repaint();
      if (st.profiles?.status === "running") refreshProfile();
      notifyAlerts();
      st.timer = setTimeout(() => { if (!st.deep) return; if (!document.hidden && !host()?.contains(document.activeElement)) read(); else st.timer = setTimeout(() => read(), 15000); }, 15000);
    }
    function standing() {
      if (st.standingBusy) return st.standingPromise;
      st.standingBusy = true; repaint();
      st.standingPromise = (async () => {
        const paths = { schema: "/api/perf/schema", caps: "/api/perf/profiles/capabilities", profiles: "/api/perf/profiles", settings: "/api/perf/objectives" };
        let [schema, caps, profiles, settings, preferences] = await Promise.all([
          ...Object.entries(paths).map(([key, path]) => panelRead(key, "get", path, attached)), API.get("/api/alerts"),
        ]);
        const replies = [schema, caps, profiles, settings];
        const retried = await API.retryCapacity(Object.entries(paths).map(([key, path], index) => ({ key, path, answer: replies[index] })), attached,
          (item, retryApi, canStart) => panelRead(item.key, "get", item.path, () => attached() && canStart(), undefined, retryApi));
        for (const { item, answer } of retried) {
          if (item.key === "schema") schema = answer;
          if (item.key === "caps") caps = answer;
          if (item.key === "profiles") profiles = answer;
          if (item.key === "settings") settings = answer;
        }
        if (!attached() || !schema || !caps || !profiles || !settings) return;
        Object.assign(st.replies, { schema, caps, preferences }); st.profilesReply = profiles; st.profiles = profiles.ok ? profiles.body : null; st.settings = settings.ok ? settings.body : null; st.settingsReply = settings;
      })().finally(() => { st.standingBusy = false; repaint(); });
      return st.standingPromise;
    }
    // WHAT A PERSON OPENING PERFORMANCE WANTS FIRST (Nov 10): is anything slow or failing right now, which command, and
    // against what. The daemon's fold (GET /api/perf/summary?window=1h|7d, the one Results by command reads) carries every
    // command's complete figures at both, so the panel leads with the last hour against the last 7 days, read every minute
    // while the panel is up; the exact cohort reads stay behind the second step.
    async function readPulse(force = false) {
      clearTimeout(st.pulseTimer);
      if (st.pulse.reading || (!force && Date.now() - st.pulse.at < 55000)) return;
      st.pulse.reading = true;
      const [hour, week] = await Promise.all([API.get("/api/perf/summary?window=1h"), API.get("/api/perf/summary?window=7d")]);
      st.pulse = { now: hour?.ok ? hour.body : null, usual: week?.ok ? week.body : null, at: Date.now(), reading: false, failed: !hour?.ok || !week?.ok };
      if (!attached()) return;
      paintPulse();
      st.pulseTimer = setTimeout(() => { if (attached()) readPulse(); }, 60000);
    }
    // the minute's new figures redraw the pulse alone: a whole repaint would put back a cohort form being edited below
    function paintPulse() {
      const h = host(), el = h?.querySelector(".pc-pulse");
      if (!el) { repaint(); return; }
      el.outerHTML = pulse();
      const refresh = h.querySelector('[data-pc="refresh"]'); if (refresh) refresh.disabled = st.busy || st.pulse.reading;
    }
    const FEW = 5;
    // a command's last hour against its last 7 days: failing more, slower, as usual, or too few requests to say
    function verdict(h, u) {
      if (!(h.count >= FEW)) return { kind: "unknown", word: "Too few to compare" };
      const f = h.failure_share, uf = u?.failure_share, t = h.total_ms || {}, ut = u?.total_ms || {};
      if (known(f) && f >= .02 && Math.round(f * h.count) >= 3 && (!known(uf) || f >= 2 * uf)) return { kind: "bad", word: "Failing more", why: "fail" };
      if (known(t.p50) && ut.p50 > 0 && t.p50 >= 1.5 * ut.p50) return { kind: "watch", word: "Slower", why: "p50" };
      if (known(t.p95) && ut.p95 > 0 && t.p95 >= 2 * ut.p95) return { kind: "watch", word: "Slowest 5% slower", why: "p95" };
      return { kind: "neutral", word: "As usual" };
    }
    const RANK = { bad: 0, watch: 1, neutral: 2, unknown: 3 };
    function pulse() {
      const p = st.pulse, scoped = Object.keys(current().filters || {}).some((k) => k !== "local");
      const wrap = (body, busy = false) => `<section class="pc-pulse" aria-label="The last hour against the last 7 days" aria-busy="${busy}">${body}</section>`;
      if (!p.at) return wrap(`<p class="pc-pulse-says">Reading the last hour…</p>`, true);
      if (p.failed) return wrap(`<p class="pc-pulse-says">splice did not send the last hour's figures. Refresh to ask again.</p>`);
      const heads = (b) => new Map((b?.heads || []).map((h) => [h.key, h]));
      const hour = heads(p.now), week = heads(p.usual);
      const rows = [...hour.values()].filter((h) => h.count > 0).map((h) => ({ h, u: week.get(h.key), v: verdict(h, week.get(h.key)) }))
        .sort((a, b) => RANK[a.v.kind] - RANK[b.v.kind] || b.h.count - a.h.count);
      const quiet = [...week.values()].filter((u) => u.count > 0 && !(hour.get(u.key)?.count > 0)).map((u) => commandName(u.key));
      const total = rows.reduce((a, r) => a + r.h.count, 0), flagged = rows.filter((r) => r.v.kind === "bad" || r.v.kind === "watch");
      const plural = (n, one, many = `${one}s`) => `${num(n)} ${n === 1 ? one : many}`;
      let says;
      if (flagged.length) {
        const { h, u, v } = flagged[0], name = safe(commandName(h.key));
        says = v.why === "fail" ? `<strong>${name} is failing more than usual.</strong> ${percent(h.failure_share)} of its ${plural(h.count, "request")} in the last hour failed, against ${percent(u?.failure_share)} over the last 7 days.`
          : v.why === "p50" ? `<strong>${name} is slower than usual.</strong> Its typical request took ${duration(h.total_ms.p50)} in the last hour, against ${duration(u.total_ms.p50)} over the last 7 days.`
          : `<strong>${name}'s slowest requests are slower than usual.</strong> Its slowest 5% took ${duration(h.total_ms.p95)} or more in the last hour, against ${duration(u.total_ms.p95)} over the last 7 days.`;
        if (flagged.length > 1) says += ` ${flagged.length - 1} more ${flagged.length === 2 ? "command needs" : "commands need"} a look below.`;
      } else if (rows.length) says = `<strong>Nothing is slower or failing more than usual.</strong> ${plural(total, "request")} in the last hour across ${plural(rows.length, "command")}, each against its own last 7 days.`;
      else says = `<strong>No requests in the last hour.</strong>${quiet.length ? ` ${plural(quiet.length, "command")} answered in the last 7 days.` : ""}`;
      const hourAgo = p.at - WINDOWS["1h"];
      const pair = (now, usual) => `<b>${now}</b><small>usual ${usual}</small>`;
      const body = rows.map(({ h, u, v }) => `<tr><th scope="row"><a href="${safe(address({ from_ms: hourAgo, until_ms: p.at, source: "auto", filters: { head: h.key } }))}">${safe(commandName(h.key))}</a></th>`
        + `<td data-l="Requests"><b>${num(h.count)}</b><small>${num(u?.count)} in 7 days</small></td>`
        + `<td data-l="Failed" class="${v.why === "fail" ? "pc-hot" : ""}">${pair(percent(h.failure_share), percent(u?.failure_share))}</td>`
        + `<td data-l="Typical time" class="${v.why === "p50" ? "pc-hot" : ""}">${pair(duration(h.total_ms?.p50), duration(u?.total_ms?.p50))}</td>`
        + `<td data-l="Slowest 5%" class="${v.why === "p95" ? "pc-hot" : ""}">${pair(duration(h.total_ms?.p95), duration(u?.total_ms?.p95))}</td>`
        + `<td>${badge(v.word, v.kind)}</td></tr>`).join("");
      const table = rows.length ? `<div class="pc-table-wrap pc-pulse-wrap" tabindex="0" role="region" aria-label="Each command's last hour against its last 7 days"><table class="pc-pulse-table">`
        + `<caption>Last hour, to ${safe(when(p.at))} · each figure over its own last 7 days</caption>`
        + `<thead><tr><th scope="col">Command</th><th scope="col">Requests</th><th scope="col">Failed</th><th scope="col">Typical time</th><th scope="col">Slowest 5%</th><th scope="col"><span class="sr">Compared</span></th></tr></thead><tbody>${body}</tbody></table></div>` : "";
      const tail = [quiet.length ? `Quiet in the last hour: ${quiet.join(", ")}.` : "", scoped ? "This compares every request; the page's project and filters narrow the measurements below." : ""].filter(Boolean);
      return wrap(`<p class="pc-pulse-says">${says}</p>${table}${tail.map((x) => note(x)).join("")}`);
    }
    const field = (k, label, value = "", type = "text", extra = "") => `<label>${safe(label)}<input class="field" name="${safe(k)}" type="${type}" value="${safe(value)}" ${extra}></label>`;
    const select = (k, label, values, picked) => `<label>${safe(label)}<select name="${safe(k)}" class="field">${values.map(([v, t]) => `<option value="${safe(v)}"${String(picked ?? "") === v ? " selected" : ""}>${safe(t)}</option>`).join("")}</select></label>`;
    const metricCoverage = (m) => `${m?.coverage?.complete === true ? "Complete" : m?.coverage ? "Partial" : "Unknown"} · ${num(m?.coverage?.observed_ms)} ms · ${safe(words(m?.filter_attribution))}`;
    function controls() {
      const q = st.query || queryNow(), input = current(), f = q.filters, accounts = accountChoices();
      // Labelled in words (Nov 10): a period reads "Last 7 days", an exact one is picked on a date and clock, and an empty
      // filter says what it lets through. The exact instants ride in hidden fields, so a submit that leaves the clock
      // untouched keeps the interval to the millisecond a link named.
      const any = (what) => `placeholder="${safe(what)}"`;
      // a value being edited survives a redraw: a read landing while the period is picked must not put the old one back
      const draft = (k, v) => st.queryDraft && k in st.queryDraft ? st.queryDraft[k] : v;
      const F = (k, label, v, ...rest) => field(k, label, draft(k, v), ...rest), S = (k, label, values, picked) => select(k, label, values, draft(k, picked));
      return `<form class="pc-query" data-pc-form="query"><div class="pc-query-main">`
        + S("window", "Period", [["", "Exact dates"], ...Object.keys(WINDOWS).map((x) => [x, `Last ${PERIOD[x]}`])], input.window || "")
        + F("head", "Command", f.head, "text", any("Every command")) + F("model", "Model", f.model, "text", any("Every model")) + F("project", "Project", f.project, "text", any("Every project"))
        + S("objective", "Objective", [["", "No objective"], ...(st.settings?.objectives || []).map((x) => [x.id, x.id])], q.objective_id)
        + `<button class="act small" type="submit">Measure</button><button class="act quiet small" type="button" data-pc="filters" aria-expanded="${st.advanced}">All filters</button></div>`
        + `<div class="pc-query-extra"${st.advanced ? "" : " hidden"}>${F("fromAt", "From", localInput(q.from_ms), "datetime-local", 'step="1"')}${F("untilAt", "Until (not included)", localInput(q.until_ms), "datetime-local", 'step="1"')}`
        + `<input type="hidden" name="from" value="${safe(q.from_ms)}"><input type="hidden" name="until" value="${safe(q.until_ms)}">`
        + ["transport", "outcome", "session", "account", "silence_ms", "queued_ms", "resumed", "started_over", "unattributed", "test"].map((k) => k === "account" && accounts.length > 1 ? S(k, "Recorded account", [["", "Choose one ID"], ...accounts.map((v) => [v, v])], "") : F(k, LABELS[k] || words(k), f[k], "text", any("Any"))).join("")
        + ["local", "compact", "streaming"].map((k) => S(k, LABELS[k], [["", "Any"], ["1", "Yes"], ["0", "No"]], f[k])).join("")
        + S("source", "Source", [["auto", "Automatic"], ["live", "Live"], ["history", "History"], ["evidence", "Recorded evidence"], ["legacy", "Legacy rows"]], q.source) + `</div></form>`
        + (accounts.length > 1 ? note("Cohort unavailable: this account uses multiple recorded IDs. Choose one ID to narrow Usage and Performance together. Usage continues to show the combined account until you choose.") : "");
    }
    function completedWindow() {
      const q = st.query, horizon = st.replies.summary?.body?.settled_until_ms, duration = q?.until_ms - q?.from_ms;
      return Number.isSafeInteger(horizon) && horizon >= duration && Number.isSafeInteger(duration) && duration > 0
        ? { ...q, filters: { ...q.filters }, window: "", from_ms: horizon - duration, until_ms: horizon } : null;
    }
    function coverage() {
      const query = st.query || queryNow();
      const r = st.replies.summary, s = r?.body, stale = s?.as_of_ms != null && query.until_ms - s.as_of_ms > 60000;
      const completed = completedWindow(), accountGap = issues(s).includes("account_alias_mapping_unavailable");
      return `<div class="pc-coverage" role="status">${badge(`${status(r)}${stale ? " · Stale" : ""}${st.busy ? " · Refreshing" : ""}`, !r?.ok || stale || issues(s).length ? "unknown" : "neutral")}<span>${safe(when(query.from_ms, true).replace(/^today/, "Today"))} to ${Math.abs(Date.now() - query.until_ms) < 120000 ? "now" : safe(when(query.until_ms, true))} CT</span>${s?.truncated ? badge("Truncated", "unknown") : ""}<button class="act quiet small" data-pc="completed-window"${!completed || st.busy ? " disabled" : ""}>Use completed window</button></div>`
        + (accountGap ? badge("Account names unavailable", "unknown") : "")
        + detail("Coverage and recording boundary", note(`Recording certified for ${duration(s?.observed_duration_ms)} of the ${duration(s?.requested_duration_ms)} picked.`) + (completed ? note(`From ${date(completed.from_ms)} to ${date(completed.until_ms)} · same ${duration(completed.until_ms - completed.from_ms)} duration. This selects the interval ending at the certified recording boundary. Late records can revise it; coverage still applies. Your selected interval stays unchanged until you choose it.`) : note("No certified recording boundary available for this duration."))
          + (explain(r) ? note(words(explain(r))) : "")
          + (accountGap ? note("Account names are temporarily unavailable. Exact recorded account IDs still work. Name matching uses the last Accounts read for up to 60 seconds; opening Accounts refreshes it. This view does not refresh accounts automatically.") : ""))
        + (s?.missing_fields?.length ? `<details class="pc-detail"><summary>${s.missing_fields.length} unavailable measurements</summary>${note(s.missing_fields.map(words).join(" · "))}</details>` : "");
    }
    function overview() {
      const s = st.replies.summary?.body;
      if (!s?.rates) return note(`${status(st.replies.summary)} · Measurements have not been returned.`) + commandResults();
      const rates = s.rates;
      // each rate in the unit that gives it a readable figure (perTime): 0.00221 completions a second is 7.96 an hour
      // These count lifecycle records, which can cover only part of the traffic; the pulse above counts every request, so
      // a partial population says so beside the rates rather than reading as a contradiction of it.
      const hero = [["Arrivals recorded", "arrivals_per_s"], ["Completions recorded", "completions_per_s"], ["Successful completions", "successful_completions_per_s"], ["Attempts recorded", "attempts_per_s"]];
      const partialPopulation = s.counts?.population_complete === false
        ? note("These rates count the requests whose lifecycle splice recorded, part of the traffic in this period (Partial population below). The last hour above counts every request.") : "";
      const peak = Math.max(0, ...hero.map(([, k]) => rates[k] || 0));
      const headline = `<div class="pc-meters">${hero.map(([label, k]) => `<div><span>${label}</span>${known(rates[k]) ? `<strong>${perTime(rates[k]).replace(/ (a|an) (second|minute|hour)$/, " <small>$1 $2</small>")}</strong>${track(rates[k], peak, `${label}: ${perTime(rates[k])}; shared scale 0 to ${perTime(peak)}`)}` : empty("Not measured")}</div>`).join("")}</div>`;
      const traffic = bars([["Input", rates.input_tokens_per_s], ["Output", rates.output_tokens_per_s], ["Cached", rates.cached_tokens_per_s]], "Tokens a second")
        + bars([["Upstream", rates.upstream_bytes_per_s], ["Downstream", rates.downstream_bytes_per_s]], "Bytes a second")
        + detail("Event throughput", bars([["Upstream", rates.upstream_events_per_s], ["Downstream", rates.downstream_events_per_s]], "Events a second"));
      const g = s.goodput || {};
      const goodput = g.status === "unconfigured" ? empty("Objective not selected", "Select an objective above to measure goodput.") : badge(words(g.status), g.bad > 0 ? "bad" : "neutral") + bars([["Good", g.good], ["Bad", g.bad], ["Pending", g.pending], ["Unknown", g.unknown]], "Objective results")
        + detail("Goodput definitions and coverage", facts({ objective: g.objective_id, revision: g.objective_revision, known_evaluable: g.known_evaluable, observed_arrivals: g.observed_arrivals, whole_window_eligible: g.eligible, whole_window_good_per_s: g.per_s, good_fraction: g.good_fraction, known_good_per_s: g.known_good_per_s, known_good_rate_is_lower_bound: g.known_good_rate_is_lower_bound }) + note(words(g.attribution)));
      const counts = s.counts || {};
      const populations = bars([["Known arrivals", counts.known_arrivals], ["Known completions", counts.known_completions], ["Known successful", counts.known_successful_completions]], "Recorded lifecycle counts")
        + badge(counts.population_complete ? "Complete population" : "Partial population", counts.population_complete ? "neutral" : "unknown")
        + detail("Population definitions", facts(counts) + note("Arrival and completion windows are separate. Model-at-arrival may be unknown; returned attribution governs each rate."));
      return partialPopulation + headline + detail("Rate definitions", note(`Rates use requested wall duration. ${words(rates.interpretation)}. Attribution: ${words(rates.filter_attribution)}.`))
        + `<div class="pc-grid pc-overview-grid">${section("Throughput", traffic)}${section("Request populations", populations)}${section("Objective goodput", goodput)}</div>`
        + commandResults()
        + `<details class="pc-detail"><summary>Population coverage and outcomes</summary>${facts(s.outcomes)}${entries(s.population_coverage).map(([k, v]) => section(words(k), facts(v))).join("")}</details>`;
    }
    const commandName = (head) => options.commandName?.(head) || head;
    function commandResults() {
      const reply = st.replies.summary, s = reply?.body;
      const columns = ["Command", "Requests", "Failed", "Typical", "Slowest 5%", "Coverage"];
      const heading = `<h3>Results by command</h3>${note("Selected cohort and interval · open a command for outcomes, cache, load, stages and models.")}`;
      const labels = `<div class="pc-command-grid pc-command-labels" aria-hidden="true">${columns.map((x) => `<span>${x}</span>`).join("")}</div>`;
      if (!reply || st.busy || options.ready?.() === false) {
        const ghost = '<i class="ghost" aria-hidden="true"></i>';
        return `<section class="pc-results" aria-label="Results by command">${heading}<div class="card pc-commands ghosted" aria-busy="true">${labels}${Array.from({ length: 6 }, () => `<div class="pc-command-grid">${columns.map(() => `<span>${ghost}</span>`).join("")}</div>`).join("")}</div></section>`;
      }
      const heads = entries(s?.legacy).sort((a, b) => (b[1].failure_share ?? -1) - (a[1].failure_share ?? -1) || (b[1].count ?? -1) - (a[1].count ?? -1));
      const renderHead = ([head, h]) => {
        const covered = h.count === 0 ? "No recorded rows" : h.metrics_complete === false ? "Partial metrics" : h.read_status === "readable" ? "Recorded rows" : "Unread / unknown";
        const time = (v) => known(v) ? duration(v) : "—";
        const cells = [commandName(head), h.count === 0 ? "No recorded rows" : num(h.count), percent(h.failure_share), time(h.total_ms?.p50), time(h.total_ms?.p95), covered];
        return `<details class="pc-command" data-pc-command="${safe(head)}"${st.commands.has(head) ? " open" : ""}><summary class="pc-command-grid">${cells.map((value, i) => `<span><small>${columns[i]}</small>${safe(value)}</span>`).join("")}</summary><div class="pc-command-detail">${commandDetail(head, h)}</div></details>`;
      };
      const active = heads.filter(([, h]) => h.count !== 0);
      const inactive = heads.filter(([, h]) => h.count === 0);
      // a busy command's long period stops at the query's processing limit and keeps its count alone; say where its times are
      const partial = active.filter(([, h]) => h.metrics_complete === false).map(([head]) => commandName(head));
      const limit = partial.length ? note(`${partial.join(", ")} reached this period's processing limit, so only ${partial.length === 1 ? "its count is" : "their counts are"} shown. A shorter period or a narrower filter measures ${partial.length === 1 ? "its" : "their"} times; the last hour above reads every figure.`) : "";
      const rows = active.map(renderHead).join("") + (inactive.length
        ? `<details class="pc-inactive-commands"${st.inactiveOpen ? " open" : ""}><summary>${num(inactive.length)} commands with no recorded rows</summary><div>${inactive.map(renderHead).join("")}</div></details>`
        : "");
      return `<section class="pc-results" aria-label="Results by command">${heading}<div class="card pc-commands">${labels}${rows || note(`${status(reply)} · No command results returned.`)}</div>${limit}</section>`;
    }
    function commandDetail(head, h) {
      const link = (label, extra = {}) => `<a href="${safe(address(st.query, { head, ...extra }))}">${safe(label)}</a>`;
      const failure = h.failure_share === 0 ? "No failures" : typeof h.failure_share === "number" ? `${percent(h.failure_share)} failed` : "Failure rate unavailable";
      const flag = h.metrics_complete !== false ? options.sampledFlag?.(head, st.query) : null;
      const flagFits = flag && Number.isFinite(flag.from) && Number.isFinite(flag.to) && flag.from >= st.query.from_ms && flag.to < st.query.until_ms;
      const sampled = flagFits && (flag.kind === "slow" || h.failure_share > 0)
        ? `<p class="pc-note"><span class="flag ${flag.kind === "failing" ? "failing" : "slow"}">${link(`${flag.kind === "failing" ? "Failing" : "Slow"} in sampled records · ${date(flag.from)} to ${date(flag.to)}`, flag.kind === "failing" ? { outcome: "failed" } : {})}</span></p>` : "";
      const outcomes = bars(entries(h.outcomes), "Recorded outcomes", "", (outcome) => link(words(outcome), { outcome }));
      const stageItems = [["Waited in line", h.queue_wait_ms], ["Before first byte", h.time_before_first_byte_ms], ["Streaming", h.time_streaming_ms], ["Whole request", h.total_ms]];
      const time = (v) => known(v) ? duration(v) : "—";
      const stages = ranges(stageItems) + detail("Stage sample counts", table(["Stage", "Samples", "Typical", "Slowest 5%", "Longest"], stageItems.map(([label, v]) => [label, num(v?.count), time(v?.p50), time(v?.p95), time(v?.max)]), `${commandName(head)} stages`));
      const models = table(["Model", "Requests", "Failed", "Typical", "Slowest 5%"], entries(h.models).map(([model, v]) => [model === "?" ? "Ended before a model was picked" : link(model, { model }), num(v.count), percent(v.failure_share), time(v.total_ms?.p50), time(v.total_ms?.p95)]), `${commandName(head)} models`);
      return `<div class="pc-actions"><strong class="pc-command-failure">${safe(failure)}</strong>${link("Open requests")}${h.failure_share > 0 ? link("Open failures", { outcome: "failed" }) : ""}${link("Open compactions", { compact: "1" })}</div>`
        + sampled + detail("Recorded row coverage", note(`Recorded request rows: ${num(h.count)} · local steps: ${num(h.local_steps)}. ${h.metrics_complete === false ? "Metric details are unavailable because this query reached its processing limit. Narrow the time range or filters to inspect metrics; counts remain available." : "Metrics describe the returned recorded-row population; coverage gaps can leave records uncounted."}`))
        + `<div class="pc-grid">${section("Outcomes", outcomes)}${section("Cache and load", facts({ cache_hit_ratio: percent(h.cache_hit_ratio), cold_after_account_switch: h.cache_cold, retries: h.retries, refreshes: h.refreshes, peak_inflight: h.peak_inflight }))}${section("Stages", stages)}</div>`
        + detail("Stage and load meanings", note("Stages describe their own measured samples and can overlap; they do not add up to the whole request. Peak inflight is a reported sample, not a process total.")) + models;
    }
    function lead() {
      if (st.busy || st.key !== fingerprint() || !st.replies.summary?.ok || options.ready?.() === false) return null;
      const heads = entries(st.replies.summary.body?.legacy), known = heads.filter(([, h]) => h.count > 0 && h.metrics_complete !== false && Number.isFinite(h.failure_share)).sort((a, b) => b[1].failure_share - a[1].failure_share);
      const head = known[0], picked = current().window, window = { "1m": "1 minute", "5m": "5 minutes", "15m": "15 minutes", "1h": "1 hour", "5h": "5 hours", "24h": "24 hours", "7d": "7 days", "30d": "30 days" }[picked] || "selected interval";
      return { head: head?.[0], name: head ? commandName(head[0]) : null, figure: head ? percent(head[1].failure_share) : null,
        none: heads.length > 0 && heads.every(([, h]) => h.count === 0), window, scoped: Object.keys(st.query?.filters || {}).some((k) => k !== "local") };
    }
    function distribution() {
      const s = st.replies.summary?.body;
      const histograms = entries(s?.histograms).sort((a, b) => (b[1].p95 || 0) - (a[1].p95 || 0));
      return section("Measured latency", histograms.length ? ranges(histograms.map(([k, h]) => [metricName(k), h])) : empty("No measured durations", "This cohort has no histogram samples."))
        + note("Durations overlap. Percentiles are bucket bounds; they are approximate.")
        + detail("Measurement definitions", note("Independent measured durations; stages, attempts and tools can overlap and do not add up to request elapsed time. Percentiles are returned bucket bounds, not exact samples."))
        + `<div class="pc-distributions">${histograms.map(([key, h]) => detail(`${metricName(key)} · ${num(h.count)} samples`, badge(h.coverage?.complete ? "Complete" : "Partial", h.coverage?.complete ? "neutral" : "unknown")
          + note(metricCoverage(h)) + note(words(h.approximation)) + note(st.replies.schema?.body?.metrics?.[key]?.population || "Population not described")
          + bars((h.buckets || []).map((n, i) => [i < (h.bounds_ms || []).length ? `≤ ${duration(h.bounds_ms[i])}` : "Overflow", n]), "Bucket counts")
          + table(["p50 · ms", "p95 · ms", "p99 · ms"], [[num(h.p50), num(h.p95), num(h.p99)]], "Returned percentile bounds"))).join("")}</div>`;
    }
    function history() {
      const series = st.replies.series?.body?.series || [], gauges = st.replies.summary?.body?.gauges || {};
      const row = ([k, g]) => [safe(words(k)), num(g.last), num(g.max), num(g.time_weighted_mean), num(g.sample_count), num(g.scope_count), num(g.covered_ms), metricCoverage(g)];
      const tableSummary = table(["Gauge", "Latest scope", "Max scope", "Weighted mean", "Samples", "Scopes", "Covered scope-ms", "Metric coverage / attribution"], entries(gauges).map(row), "Observed resource gauges");
      const points = series.flatMap((p) => entries(p.metrics).filter(([, m]) => Object.hasOwn(m, "last")).map(([k, m]) => ({ p, k, m })));
      const chosen = st.historyMetric || points[0]?.k;
      const filtered = points.filter((x) => x.k === chosen).slice(-120);
      const values = filtered.map((x) => x.m.last).filter((v) => typeof v === "number"), max = Math.max(1, ...values);
      const graph = values.length ? `<div class="pc-axis">${safe(metricName(chosen))}<span>0 → ${num(max)} · per observed scope</span></div><div class="pc-history" role="img" aria-label="${safe(words(chosen))}: latest reading per returned interval and scope. Exact readings below.">${filtered.map(({ p, m }) => `<span title="${safe(date(p.from_ms))} · ${safe(p.scope?.head)} · ${num(m.last)}" style="--height:${known(m.last) && m.last > 0 ? m.last / max * 100 : 0}%"><i></i>${m.last === 0 ? '<b class="pc-zero"></b>' : !known(m.last) ? '<b class="pc-unknown-point">?</b>' : ""}</span>`).join("")}</div><p class="pc-axis"><span>${safe(date(filtered[0].p.from_ms))} CT</span><span>${safe(date(filtered.at(-1).p.until_ms))} CT</span></p>${values.every((v) => v === 0) ? badge("All recorded samples are zero") : ""}` : empty("No sampled history", `${status(st.replies.series)} · This cohort has no returned gauge samples.`);
      return select("historyMetric", "History measurement", [...new Set(points.map((x) => x.k))].map((k) => [k, metricName(k)]), chosen) + graph
        + note("Each point is one scope. Missing scopes remain unknown.")
        + detail("Exact history readings", table(["From", "Until", "Head / model / transport", "Latest", "Max", "Weighted mean", "Samples"], filtered.map(({ p, m }) => [safe(date(p.from_ms)), safe(date(p.until_ms)), safe([p.scope?.head, p.scope?.model, p.scope?.transport].map((x) => x ?? "unknown").join(" / ")), num(m.last), num(m.max), num(m.time_weighted_mean), num(m.sample_count)]), "History · last 120 returned scope intervals"))
        + detail("Gauge summary and meanings", note("Latest is one observed scope, never a process total. Max is the largest scope sample; mean is weighted over observed scope time. Covered milliseconds are summed over scopes; missing scopes are unknown.") + tableSummary)
        + detail("Event counters", table(["Counter", "Measurements", "Sum of deltas", "Per second", "Metric coverage / attribution"], entries(st.replies.summary?.body?.counters).map(([k, c]) => [safe(metricName(k)), num(c.count), num(c.sum), num(c.per_s), metricCoverage(c)]), "Counters · requested-duration rates"));
    }
    function runtime() {
      const r = st.replies.runtime, v = r?.body;
      const groups = new Map(), group = (k) => /cpu|process.*time/i.test(k) ? "CPU" : /heap|memory|rss|buffer|gc/i.test(k) ? "Memory and GC" : /file|disk|persist/i.test(k) ? "Files and persistence" : /export|otlp|scrape/i.test(k) ? "Export" : /http|socket|connection/i.test(k) ? "Connections" : /thread|scheduler/i.test(k) ? "Threads" : /native|tool|mcp/i.test(k) ? "Tools" : "Instrumentation";
      for (const [key, value] of entries(v?.measurements)) { const name = group(key); if (!groups.has(name)) groups.set(name, []); groups.get(name).push([key, value]); }
      const readings = (items) => `<dl class="pc-readings">${items.map(([k, x]) => `<div><dt>${safe(metricName(k))}</dt><dd>${safe(reading(k, x))}</dd>${/_ratio$/.test(k) && known(x) ? track(x, 1, `${metricName(k)}: ${percent(x)}`) : ""}</div>`).join("")}</dl>`;
      const cards = [...groups].map(([name, items]) => section(name, readings(items.slice(0, 5)) + (items.length > 5 ? detail(`${items.length - 5} more readings`, readings(items.slice(5))) : ""))).join("");
      const sinks = st.replies.summary?.body?.coverage?.sinks || [];
      return `<div class="pc-actions">${badge(status(r), r?.ok ? "neutral" : "unknown")}<span>Sampled ${safe(date(v?.as_of_ms))} CT · ${safe(duration(v?.age_ms))} old</span></div>`
        + (cards ? `<div class="pc-runtime-grid" style="--pc-columns:${Math.min(4, groups.size)}">${cards}</div>` : empty("No runtime sample"))
        + detail("Process identity and unavailable counters", facts(v?.identity) + table(["Capability", "Availability", "Reason"], (v?.capabilities || []).map((x) => [safe(metricName(x.id)), badge(x.supported === true ? "Available" : x.supported === false ? "Unsupported" : "Unknown", x.supported ? "neutral" : "unknown"), safe(words(x.reason))]), "Runtime capabilities") + note(issues(v).map(words).join(" · ")))
        + section("Recording and export health", sinks.length ? `<div class="pc-runtime-grid" style="--pc-columns:${Math.min(4, sinks.length)}">${sinks.map((x) => section(keyWords(x.sink), badge(x.failed || x.dropped ? "Recording loss" : x.pending ? "Writes pending" : "No pending writes", x.failed || x.dropped ? "bad" : x.pending ? "unknown" : "neutral")
          + bars([["Written", x.written], ["Pending", x.pending], ["Failed", x.failed], ["Dropped", x.dropped]], "Sink records")
          + detail("Sink counters", facts(x)))).join("")}</div>` : empty("Sink status unavailable"))
        + note("Current process sample. Use History for the selected interval.");
    }
    function timeline() {
      const d = st.replies.diagnose?.body, requests = d?.timeline?.requests || [];
      const selected = requests.find((x) => x.request_id === st.openRequest) || requests[0];
      const events = selected?.events || [], arrival = events.find((e) => e.kind === "REQUEST_ARRIVED"), origin = arrival?.monotonic_ms ?? events[0]?.monotonic_ms;
      const measurement = (e) => entries(e.measurements).map(([k, v]) => `${words(k)}: ${num(v)}`).join(" · ");
      const rows = events.map((e) => [safe(words(e.kind)), num(Number.isFinite(e.monotonic_ms) && Number.isFinite(origin) ? e.monotonic_ms - origin : null), safe(e.attempt_id ?? "—"), safe(e.tool_id ?? "—"), detail("Measurements", safe(measurement(e))), turnLink(e, "Open turn")]);
      const evidence = table(["Measured condition", "Mean · ms", "Interpretation", "Next measurement"], (d?.evidence || []).map((e) => [safe(words(e.metric)), num(e.mean_ms), safe(`${words(e.classification)} · ${words(e.inference)}`), safe(words(e.next_falsifying_measurement))]), "Diagnosis evidence");
      const span = events.length ? events.at(-1).monotonic_ms - origin : 0;
      const waterfall = events.length ? `<div class="pc-waterfall" role="img" aria-label="Recorded progress on the request clock; exact values in event details">${events.filter((e) => /CONTENT|CLIENT|ATTEMPT|TOOL|REQUEST/.test(e.kind)).map((e) => `<div><span>${safe(words(e.kind).toLowerCase())}</span><i><b style="left:${span > 0 ? Math.max(0, Math.min(100, (e.monotonic_ms - origin) / span * 100)) : 0}%"></b></i><small>${safe(duration(e.monotonic_ms - origin))}</small></div>`).join("")}</div>` : "";
      return section("Measured conditions", (d?.evidence || []).length ? bars((d.evidence || []).map((e) => [metricName(e.metric), e.mean_ms]), "Mean measured duration", " ms") : empty("No diagnosis measurements"))
        + detail("Interpretation and next measurements", evidence + note("Content and successful flush checkpoints below are separate from transport bytes. Attempt and tool IDs have independent clocks; their durations overlap and are not additive."))
        + select("openRequest", "Representative request", requests.map((r, i) => [r.request_id, `Request ${i + 1} · ${commandName(r.head) || "Unknown command"} · ${(r.events || []).length} recorded events`]), selected?.request_id)
        + (selected ? `<div class="pc-actions">${turnLink(selected, "Open recorded turn")}</div>` + detail("Request identifiers", facts({ command: selected.head, turn: selected.turn_id, trace: selected.trace_id, session: selected.session_id })) : "")
        + (d?.timeline?.truncated ? note("Representative timeline is truncated; it is not the complete population.") : "")
        + note(arrival ? "Elapsed time is measured from the recorded request arrival." : "Arrival is unavailable. Elapsed time starts at the first retained event.")
        + waterfall + (events.length ? detail("Event details and correlation IDs", table(["Event", "Elapsed · ms", "Attempt", "Tool", "Measurements", "Trace reference"], rows, "Request, attempt and tool timeline")) : empty("No request timeline evidence", status(st.replies.diagnose)))
        + detail("Resource evidence", facts(d?.resources) + ((d?.resources?.conditions || []).length ? table(["Measured resource", "Value", "Reported capacity", "Fraction", "Interpretation"], d.resources.conditions.map((x) => [safe(metricName(x.metric)), num(x.value), num(x.capacity), num(x.fraction), safe(words(x.inference))]), "Resource occupancy · sample time only") : empty("Resource occupancy not measured"))) + requestRows();
    }
    function turnLink(record, label, query = st.query) {
      return record?.head && record?.turn_id ? `<a href="${safe(address(query, { head: record.head }, `${record.head}:${record.turn_id}`))}">${safe(label)}</a>` : "Turn link unavailable";
    }
    function requestRows() {
      const r = st.replies.rows, v = r?.body;
      return ((v?.rows || []).length ? table(["Request", "Time", "Command", "Model", "Outcome"], v.rows.map((x, i) => [turnLink(x, `Open request ${i + 1}`, st.rowsQuery), safe(date(x.ts)), safe(commandName(x.head)), safe(x.model ?? "Unknown"), badge(words(x.outcome), x.outcome === "failed" ? "bad" : "neutral")]), "Recorded request rows · returned source order") : empty(r?.ok ? "No recorded rows returned" : st.busy ? "Loading recorded rows…" : "Request rows unavailable", status(r)))
        + note(`${status(r)} · ${known(v?.total_matching) ? num(v.total_matching) : "Unknown count of"} matching recorded rows`)
        + (explain(r) ? note(reasonText(explain(r))) : "")
        + (explain(r) === "cursor_work_limit" ? detail("Technical reason", `<code>${safe(explain(r))}</code>`) : "")
        + (st.rowsQuery ? note(`Row snapshot: ${date(st.rowsQuery.from_ms)} – ${date(st.rowsQuery.until_ms)}. Refresh to capture newer rows.`) : "")
        + (v?.next_cursor ? `<button class="act small" data-pc="next"${st.busy || st.rowsBusy ? " disabled" : ""}>Next records</button>` : "");
    }
    function baselines() {
      const selected = st.baselines[st.selected], summary = st.replies.summary?.body;
      const c = selected && summary?.identity_cohorts ? comparison(selected.summary, summary) : null;
      const pairs = selected && summary ? [
        ...["arrivals_per_s", "completions_per_s", "attempts_per_s", "input_tokens_per_s", "output_tokens_per_s", "cached_tokens_per_s"].map((k) => [keyWords(k), selected.summary.rates[k], summary.rates?.[k], " / s"]),
        ...["arrival_to_first_meaningful_content_ms", "arrival_to_terminal_ms", "queue_wait_ms"].map((k) => [`${metricName(k)} · p95`, selected.summary.histograms[k]?.p95, summary.histograms?.[k]?.p95, " ms"]),
      ] : [];
      const drawn = `<div class="pc-comparisons">${pairs.map(([label, left, right, unit]) => section(label, bars([["Saved", left], ["Current", right]], "Observed values", unit))).join("")}</div>`;
      const values = selected && summary ? table(["Recorded value", "Saved", "Current"], [
        ...["arrivals_per_s", "completions_per_s", "attempts_per_s", "input_tokens_per_s", "output_tokens_per_s", "cached_tokens_per_s"].map((k) => [safe(words(k)), num(selected.summary.rates[k]), num(summary.rates?.[k])]),
        ...["arrival_to_first_meaningful_content_ms", "arrival_to_terminal_ms", "queue_wait_ms"].map((k) => [`${safe(words(k))} · p95`, num(selected.summary.histograms[k]?.p95), num(summary.histograms?.[k]?.p95)]),
        ["Known good / s", num(selected.summary.goodput.known_good_per_s), num(summary.goodput?.known_good_per_s)],
        ["Observed / requested · ms", `${num(selected.summary.observed_duration_ms)} / ${num(selected.summary.requested_duration_ms)}`, `${num(summary.observed_duration_ms)} / ${num(summary.requested_duration_ms)}`],
      ], "Saved and current observations · no causal performance claim") : "";
      const evidence = (s) => {
        const c = s?.comparison, w = c?.workload, p = c?.provenance;
        return facts({ workload: w?.status || "Unavailable", measured_requests: w?.measured_requests, matching_requests: w?.matching_requests, observed_arrivals: w?.observed_arrivals,
          measured_as_of: date(w?.as_of_ms ?? p?.as_of_ms), coverage_basis: w?.coverage_basis, event_time_final: w?.event_time_final, build: p?.builds?.join(", "), schema: p?.schema_versions?.join(", "),
          source: p?.source, settings: s?.configuration?.intervals?.map((i) => i.revision).filter((v, i, a) => a.indexOf(v) === i).join(", "), settings_scope: p?.configuration_scope,
          objective: p?.objective_id, objective_revision: p?.objective_revision, matching_basis: w?.matching_basis, distribution: w?.distribution_id });
      };
      const partitions = (s) => table(["Command / model / transport / work / outcome", "Local / compact / streaming", "Input", "Output", "Cache read", "Cache write", "1h write", "Absorbed / cut / no request", "Requests"], (s?.comparison?.workload?.partitions || []).map((p) => [
        safe(["head", "model", "transport", "work", "outcome"].map((k) => p.scope?.[k] ?? "unknown").join(" / ")),
        safe(["local", "compact", "streaming"].map((k) => p.scope?.[k] ?? "unknown").join(" / ")), ...["input_tokens", "output_tokens", "cached_tokens", "cache_write_tokens", "cache_write_1h_tokens"].map((k) => num(p[k])),
        ["absorbed_rounds", "cut_rounds", "no_request"].map((k) => num(p[k])).join(" / "), num(p.count),
      ]), "Measured workload groups · exact counts");
      return `<div class="pc-actions">${field("baselineName", "Baseline name", "Baseline")}
        <button class="act small" data-pc="save-baseline"${!summary?.rates || st.busy ? " disabled" : ""}>Save locally</button><label class="act quiet small">Import JSON<input type="file" accept="application/json,.json" data-pc-import hidden></label></div>`
        + detail("What a snapshot contains", note(`Up to ${LIMIT} snapshots in this browser. Saved measurements include the query, workload groups, build, settings, objective and coverage. Imported files are local records, not independently verified measurements.`))
        + select("baseline", "Saved baseline", st.baselines.map((b, i) => [String(i), `${b.name} · ${date(b.saved_at_ms)}`]), String(st.selected))
        + (selected ? `<div class="pc-actions"><button class="act quiet small" data-pc="export-baseline">Export JSON</button><button class="act quiet small" data-pc="apply-baseline">Apply saved cohort</button><button class="act quiet small" data-pc="delete-baseline">Delete</button></div>`
          + badge(c?.status || "Waiting for current summary", c?.status === "Measured workload matches" ? "neutral" : "unknown")
          + note("Observed differences only. Matching workload does not establish cause.") + drawn
          + detail("Comparison conditions and exact values", note(c ? `Different: ${c.different.join(", ") || "none known"}. Unknown or mixed: ${c.unknown.join(", ") || "none in the measured comparison"}. Changed variables: ${c.changed.join(", ") || "none known"}.` : "Read the current cohort first.")
            + note("Matching compares measured request groups and their proportions, including actual outcomes and token/cache counts. It covers effective settings only. Provider topology and remote conditions are unverified; a match does not establish cause or rank providers.")
            + (c?.reasons.length ? note(`Evidence gaps: ${c.reasons.join(" · ")}`) : "") + values)
          + detail("Saved and current measurement evidence", `<div class="pc-comparisons">${section("Saved evidence", evidence(selected.summary))}${section("Current evidence", evidence(summary))}</div>`)
          + `<details class="pc-detail"><summary>Measured workload groups</summary>${section("Saved", partitions(selected.summary))}${section("Current", partitions(summary))}</details>`
          + `<details class="pc-detail"><summary>Saved cohort and configuration evidence</summary>${facts(selected.summary.query.filters)}${selected.summary.identity_cohorts.map(facts).join("")}${facts(selected.summary.configuration)}${selected.summary.configuration.intervals.map(facts).join("")}${note(`Settings exclude: ${(selected.summary.configuration.excluded_dimensions || []).map(words).join(", ") || "not recorded"}.`)}</details>` : note("No saved baselines."));
    }
    function profiles() {
      const caps = st.replies.caps?.body?.capabilities || [], p = st.profiles;
      const name = (id) => keyWords(id).replace(/^jfr /i, "JVM · ").replace(/^native /i, "Native · ");
      return `<form data-pc-form="profile" class="pc-actions">${select("profile", "Profiler and mode", caps.map((c) => [c.id, `${name(c.id)}${c.supported ? "" : " · unavailable"}`]), st.profileMode || "jfr.cpu")}${field("duration", "Duration · seconds", 60, "number", 'min="1" max="300" required')}<button class="act small" type="submit"${actionPending() ? " disabled" : ""}>Start capture</button></form>`
        + badge(`Capabilities: ${status(st.replies.caps)}`, st.replies.caps?.ok ? "neutral" : "unknown")
        + note("Explicit capture · current process · one at a time · 1–300 seconds")
        + `<div class="pc-capabilities">${caps.map((c) => `<div><strong>${safe(name(c.id))}</strong>${badge(c.supported ? "Available" : "Unavailable", c.supported ? "neutral" : "unknown")}${!c.supported ? detail("Reason", note(words(c.reason))) : ""}</div>`).join("")}</div>`
        + detail("Capture bounds and capabilities", note("Capture starts only when you press Start capture. It records the current process, independently of request filters. Bounds: 1–300 seconds; one capture per process. No native profiler is downloaded."))
        + (p ? `<div class="pc-profile-result">${badge(words(p.status), p.status === "failed" ? "bad" : p.status === "running" ? "unknown" : "neutral")}<strong>${safe(bytes(p.size_bytes))}</strong><span>${safe(date(p.from_ms))} → ${safe(date(p.until_ms))} CT</span></div>`
          + detail("Capture details", facts({ profile_id: p.profile_id, artifact_available: p.artifact_available }) + note([...(p.issues || []), ...(p.limitations || [])].map((x) => words(typeof x === "string" ? x : x.code)).join(" · ")))
          + `<div class="pc-actions"><button class="act quiet small" data-pc="profile-refresh"${actionPending() ? " disabled" : ""}>Refresh status</button>${p.status === "running" ? `<button class="act small" data-pc="profile-stop"${actionPending() ? " disabled" : ""}>Stop capture</button>` : ""}${p.artifact_available && p.profile_id ? `<button class="act small" data-pc="profile-download"${actionPending() ? " disabled" : ""}>Download artifact</button>` : ""}</div>` : note(`${status(st.profilesReply)} · Profile status has not been returned.`));
    }
    function objectives() {
      const settings = st.settings;
      if (!settings) return note(`${status(st.settingsReply)} · Objective settings unavailable.`);
      const builders = `<div class="pc-grid"><details class="pc-detail"${st.builders.get("add-objective")?.open ? " open" : ""}><summary>Add objective</summary><form data-pc-form="add-objective" class="pc-builder">${builderField("add-objective", "id", "Objective ID", "", "text", "required maxlength=128")}${builderField("add-objective", "cohort", "Cohort · JSON", "{}")}${["first_content_ms", "completion_ms", "queue_ms", "progress_gap_ms"].map((k) => builderField("add-objective", k, words(k), "", "number", 'min="0"')).join("")}<button class="act small" type="submit">Add to draft</button></form></details>`
        + `<details class="pc-detail"${st.builders.get("add-rule")?.open ? " open" : ""}><summary>Add alert rule</summary><form data-pc-form="add-rule" class="pc-builder">${builderField("add-rule", "id", "Rule ID", "", "text", "required maxlength=128")}${builderSelect("add-rule", "objective_id", "Objective", [["", "None"], ...(settings.objectives || []).map((o) => [o.id, o.id])], "")}${builderField("add-rule", "cohort", "Cohort · JSON", "{}")}${builderField("add-rule", "metric", "Metric selector", "", "text", 'required placeholder="histograms.queue_wait_ms.p95"')}${builderSelect("add-rule", "comparison", "Trigger when", [["above", "Above threshold"], ["below", "Below threshold"]], "above")}${builderField("add-rule", "threshold", "Threshold", "", "number", 'required step="any"')}${builderField("add-rule", "window_ms", "Window · ms", 300000, "number", 'required min="1"')}${builderField("add-rule", "min_count", "Minimum samples", 1, "number", 'required min="1"')}${builderField("add-rule", "min_coverage", "Minimum coverage · 0–1", 1, "number", 'required min="0" max="1" step="0.01"')}${builderField("add-rule", "hold_ms", "Trigger hold · ms", 0, "number", 'required min="0"')}${builderField("add-rule", "cooldown_ms", "Delivery cooldown · ms", 60000, "number", 'required min="0"')}${builderField("add-rule", "recovery_threshold", "Recovery threshold", "", "number", 'required step="any"')}${builderField("add-rule", "recovery_hold_ms", "Recovery hold · ms", 0, "number", 'required min="0"')}<label class="pc-checkbox"><input type="checkbox" name="delivery_enabled"${st.builders.get("add-rule")?.fields.find((field) => field.name === "delivery_enabled")?.checked ? " checked" : ""}>Allow configured delivery for this rule</label><button class="act small" type="submit">Add to draft</button></form></details></div>`;
      const draft = st.draft ?? JSON.stringify({ schema_version: 1, objectives: settings.objectives || [], rules: settings.rules || [] }, null, 2);
      const rows = Math.max(8, draft.split("\n").length + 1);
      return `<div class="pc-comparisons" style="--pc-columns:${Math.max(1, Math.min(3, settings.objectives?.length || 0))}">${(settings.objectives || []).map((o) => section(o.id, bars(["first_content_ms", "completion_ms", "queue_ms", "progress_gap_ms"].filter((k) => known(o[k])).map((k) => [metricName(k), o[k]]), "Configured upper bounds", " ms") + detail("Cohort and revision", facts(o.cohort) + facts({ revision: o.revision })))).join("") || empty("No configured objectives", "Add an objective to measure request goodput.")}</div>`
        + `<div class="pc-capabilities">${(settings.rules || []).map((rule) => `<div><strong>${safe(rule.id)}</strong>${badge(rule.delivery_enabled ? "Delivery enabled" : "Local only")}<span>${safe(metricName(rule.metric))} · ${safe(words(rule.comparison))} ${num(rule.threshold)}</span></div>`).join("")}</div>`
        + builders + `<details class="pc-detail" data-pc-editor${st.editorOpen || st.draft != null ? " open" : ""}><summary>Edit settings JSON</summary><form data-pc-form="objectives"><label class="pc-json-label">Objective and alert settings · JSON<textarea class="field pc-json" name="settings" spellcheck="false" rows="${rows}">${safe(draft)}</textarea></label>`
        + detail("Rule fields and validation", note('Rules use metric, comparison "above" or "below", threshold, window_ms, min_count ≥ 1, min_coverage 0–1, hold_ms, cooldown_ms, recovery {threshold, hold_ms} and optional delivery_enabled. Recovery must be on the healthy side. Server validates the entire document before publishing; revisions are canonical.'))
        + `<div class="pc-actions"><button type="submit" class="act small"${actionPending() ? " disabled" : ""}>Save objectives and rules</button><button type="button" class="act quiet small" data-pc="objectives-reload"${actionPending() ? " disabled" : ""}>Discard edits and reload</button></div></form></details>`
        + detail("Settings revision", facts({ revision: settings.revision }) + note("No rules or delivery are enabled by default."));
    }
    function alerts() {
      const now = st.replies.alertStatus?.body, history = st.replies.alerts?.body;
      const state = (e) => badge(`${words(e.state)}${e.active ? " · active" : ""}${e.retired ? " · retired" : ""}`, e.state === "firing" ? "bad" : /pending|insufficient/.test(e.state) ? "unknown" : "neutral");
      const measurement = (e) => {
        const metric = e.metric || st.settings?.rules?.find((r) => r.id === e.rule_id)?.metric;
        const unit = /_ms(?:\.|$)/.test(metric) ? " ms" : /_ns(?:\.|$)/.test(metric) ? " ns" : /_bytes(?:\.|$)/.test(metric) ? " B" : /_per_s(?:\.|$)/.test(metric) ? " / s" : /_ratio(?:\.|$)/.test(metric) ? " ratio" : "";
        return bars([["Observed", e.observed], ["Threshold", e.threshold]], metric ? keyWords(metric) : "Rule measurement · unit unavailable", unit);
      };
      const lifecycle = (e) => detail("Lifecycle and coverage", facts({ pending_since: date(e.pending_since_ms), firing_since: date(e.firing_since_ms), recovery_since: date(e.recovery_since_ms), resolved_at: date(e.resolved_at_ms), continuity: e.continuity, objective_revision: e.objective_revision, rule_revision: e.rule_revision, settings_revision: e.settings_revision }) + facts(e.coverage) + facts(e.cohort));
      const events = [...(now?.events || [])].sort((a, b) => Number(b.active) - Number(a.active));
      return `<div class="pc-actions">${badge(`Current: ${status(st.replies.alertStatus)}`)}${badge(`History: ${status(st.replies.alerts)}`)}${now?.restart_uncertain ? badge("Restart uncertainty", "unknown") : ""}</div>`
        + (events.length ? `<div class="pc-runtime-grid" style="--pc-columns:${Math.min(4, events.length)}">${events.map((e) => section(e.rule_id, state(e) + measurement(e) + note(words(e.coverage?.reason || e.reason || (e.coverage?.complete ? "Complete coverage" : "Partial coverage"))) + `<a href="${safe(alertAddress(e))}">Inspect interval</a>` + lifecycle(e))).join("")}</div>` : empty(st.replies.alertStatus?.ok ? "No current alert states" : "Current alerts unavailable"))
        + section("Alert transitions", (history?.events || []).length ? `<ol class="pc-alert-rail">${history.events.map((e) => `<li>${state(e)}<strong>${safe(e.rule_id)}</strong><span>${known(e.window?.until_ms) ? `Window ended ${safe(date(e.window.until_ms))} CT` : "Window time unavailable"}</span><a href="${safe(alertAddress(e))}">Inspect interval</a>${lifecycle(e)}</li>`).join("")}</ol>` : empty(st.replies.alerts?.ok ? "No transitions in this interval" : "Alert history unavailable", status(st.replies.alerts)))
        + note("Insufficient data never resolves an active alert.")
        + detail("Delivery and history health", facts({ history_status: history?.status, expired_events: history?.expired_events, delivery_rejected: now?.delivery_rejected, delivery_failures: now?.delivery_failures, enqueue_failures: now?.enqueue_failures }) + note("Desktop notices require the rule’s delivery opt-in, the existing desktop preference, and browser permission. This page does not request permission."));
    }
    function alertAddress(e) {
      const p = e.diagnostic_links?.find((x) => x.endpoint === "/api/perf/diagnose")?.parameters || {};
      const filters = { ...e.cohort };
      for (const k of SELECTORS) if (p[k] != null) filters[k] = p[k];
      return address({ ...st.query, from_ms: Number(p.from ?? e.window?.from_ms ?? st.query.from_ms), until_ms: Number(p.until ?? e.window?.until_ms ?? st.query.until_ms), filters, objective_id: p.objective || null });
    }
    function notifyAlerts() {
      const preferences = st.replies.preferences?.body;
      if (preferences?.desktop !== true || typeof Notification === "undefined" || Notification.permission !== "granted") return;
      let seen;
      try { seen = new Set(JSON.parse(localStorage.getItem("splice-performance-notified-v1") || "[]")); } catch { return; }
      for (const e of st.replies.alertStatus?.body?.events || []) {
        if (e.state !== "firing" || e.delivery_enabled !== true || !e.dedup_key || seen.has(e.dedup_key)) continue;
        seen.add(e.dedup_key);
        try { localStorage.setItem("splice-performance-notified-v1", JSON.stringify([...seen].slice(-256))); } catch { continue; }
        try { new Notification("Splice performance alert", { body: `${e.rule_id}: ${words(e.metric)} ${num(e.observed)}`, tag: e.dedup_key }); }
        catch { st.message = "Desktop notification could not be shown"; }
      }
    }
    const tabs = { overview: ["Overview", overview], distribution: ["Latency", distribution], history: ["History", history], runtime: ["Runtime", runtime], timeline: ["Requests & traces", timeline], baselines: ["Baselines", baselines], profiles: ["Profiles", profiles], objectives: ["Objectives", objectives], alerts: ["Alerts", alerts] };
    function inner() {
      // The first step answers "is anything slow or failing right now" (pulse); the second, one click away, is the whole
      // measurement console PR #482 built: an exact period and cohort, its coverage, and its nine views.
      const deep = `<details class="pc-deep" data-pc-deep${st.deep ? " open" : ""}><summary><span class="pc-deep-title">Measure any period</span>`
        + `<span class="pc-deep-says">Pick exact dates, a command, model or project, then read rates, latency, history, runtime, request traces, baselines, profiles, objectives and alerts</span></summary>`
        + (st.deep ? `<div class="pc-deep-body">${controls()}${coverage()}<nav class="pc-tabs" aria-label="Performance views">${entries(tabs).map(([k, [title]]) => `<button type="button" data-pc-tab="${k}" aria-current="${st.tab === k ? "page" : "false"}">${title}</button>`).join("")}</nav>`
          + (st.message ? `<p class="pc-message" role="status">${safe(st.message)}</p>` : "")
          + `<div class="pc-content" aria-busy="${st.busy}">${tabs[st.tab][1]()}</div></div>` : "")
        + `</details>`;
      return `<div class="bandhead pc-heading"><h2>Performance</h2>${accountChoices().length > 1 ? "" : `<a href="${safe(address(st.query || queryNow()))}">Open requests ↗</a>`}<button class="act quiet small" data-pc="refresh"${st.busy || st.pulse.reading ? " disabled" : ""}>Refresh</button></div>`
        + pulse() + deep;
    }
    function html() {
      rememberBuilders(host());
      // the cohort is read only while the second step is open; the pulse reads whenever the panel is drawn
      if (st.deep && fingerprint() !== st.key) { clearMeasurements(); st.query = queryNow(); setTimeout(() => read(), 0); }
      if (!st.pulse.reading && Date.now() - st.pulse.at >= 55000) setTimeout(() => readPulse(), 0);
      return `<section class="perf-console" data-performance="${options.id}">${inner()}</section>`;
    }
    function saveStore(next) {
      const held = next.slice(0, LIMIT), body = JSON.stringify(held);
      if (body.length > MAX_BYTES) throw new Error("Baseline store is full; delete a snapshot first");
      localStorage.setItem(STORE, body);
      st.baselines = held;
    }
    function download(blob, name) {
      const url = URL.createObjectURL(blob), a = document.createElement("a"); a.href = url; a.download = name; a.click(); setTimeout(() => URL.revokeObjectURL(url), 1000);
    }
    function refreshProfile() {
      if (st.profileBusy) return st.profilePromise;
      if (st.actionBusy || st.standingBusy) return;
      st.profileBusy = true; repaint();
      const p = st.profiles?.profile_id;
      const path = `/api/perf/profiles${p ? `/${encodeURIComponent(p)}` : ""}`;
      st.profilePromise = (async () => {
        let r = await panelRead("profile-status", "get", path, attached);
        const retried = await API.retryCapacity([{ path, answer: r }], attached,
          (item, retryApi, canStart) => panelRead("profile-status", "get", item.path, () => attached() && canStart(), undefined, retryApi));
        if (retried.length) r = retried[0].answer;
        if (r && attached()) { if (r.ok) st.profiles = r.body; else st.message = `Profile status: ${status(r)} · ${words(explain(r))}`; }
      })()
        .finally(() => { st.profileBusy = false; repaint(); });
      return st.profilePromise;
    }
    document.addEventListener("submit", async (e) => {
      const form = e.target.closest("[data-pc-form]"); if (!form || !host()?.contains(form)) return;
      e.preventDefault(); const values = new FormData(form); let actionStarted = false;
      try {
        if (["add-objective", "add-rule"].includes(form.dataset.pcForm)) {
          const draft = st.draft ? JSON.parse(st.draft) : { schema_version: 1, objectives: [...st.settings.objectives], rules: [...st.settings.rules] };
          const cohort = JSON.parse(String(values.get("cohort")));
          if (!cohort || Array.isArray(cohort) || typeof cohort !== "object") throw new Error("Cohort must be a JSON object");
          const item = { id: String(values.get("id")).trim(), cohort };
          const rule = form.dataset.pcForm === "add-rule", list = rule ? draft.rules : draft.objectives;
          if (list.some((x) => x.id === item.id)) throw new Error("That ID already exists; edit its draft entry");
          if (!rule) {
            for (const k of ["first_content_ms", "completion_ms", "queue_ms", "progress_gap_ms"]) if (values.get(k) !== "") item[k] = Number(values.get(k));
          } else {
            Object.assign(item, { metric: String(values.get("metric")), comparison: values.get("comparison"), delivery_enabled: values.get("delivery_enabled") === "on", recovery: { threshold: Number(values.get("recovery_threshold")), hold_ms: Number(values.get("recovery_hold_ms")) } });
            for (const k of ["threshold", "window_ms", "min_count", "min_coverage", "hold_ms", "cooldown_ms"]) item[k] = Number(values.get(k));
            if (values.get("objective_id")) item.objective_id = values.get("objective_id");
          }
          list.push(item); st.draft = JSON.stringify(draft, null, 2); st.draftRevision++; st.message = "Added to draft. Save objectives and rules to publish.";
          st.builders.delete(form.dataset.pcForm); form.remove(); repaint(); return;
        }
        if (form.dataset.pcForm === "query") {
          const input = { window: values.get("window"), filters: {}, source: values.get("source"), objective_id: values.get("objective") || null };
          if (accountChoices().length > 1 && !accountChoices().includes(values.get("account"))) throw new Error("Choose one recorded account ID before applying this cohort");
          if (accountChoices().length > 1 && values.get("head") !== st.query.filters.head) throw new Error("Keep the selected command when choosing one of its account IDs");
          for (const k of SELECTORS) if (values.get(k)) input.filters[k] = String(values.get(k)).trim();
          if (input.filters.project === "-") input.filters.project = "none";
          if (input.filters.outcome === "fail") input.filters.outcome = "failed";
          if (input.filters.outcome === "all") delete input.filters.outcome;
          // the clock a person set, or the exact instant behind it when the clock still reads what it was given
          const exact = (at, raw) => { const v = String(values.get(at) || ""), held = Number(values.get(raw)); return v && v !== localInput(held) ? new Date(v).getTime() : held; };
          if (!input.window) { input.from_ms = exact("fromAt", "from"); input.until_ms = exact("untilAt", "until"); if (!(input.until_ms > input.from_ms)) throw new Error("Enter a valid exact interval"); }
          st.message = ""; st.queryDraft = null; options.apply(input); st.key = null; read(true); return;
        }
        if (actionPending()) { st.message = "A performance action is pending. Wait for it to finish."; return; }
        st.actionBusy = actionStarted = true; st.message = "Performance action pending…"; repaint();
        if (form.dataset.pcForm === "profile") {
          const [profiler, mode] = String(values.get("profile")).split("."), durationSeconds = Number(values.get("duration"));
          if (!Number.isInteger(durationSeconds) || durationSeconds < 1 || durationSeconds > 300) throw new Error("Duration must be 1–300 seconds");
          const capability = (st.replies.caps?.body?.capabilities || []).find((x) => x.id === `${profiler}.${mode}`);
          if (!capability?.supported && capability?.reason !== "mode_requires_explicit_start_probe") throw new Error(words(capability?.reason || "Unsupported profile"));
          const r = await panelRead("action", "post", "/api/perf/profiles", () => true, { profiler, mode, durationSeconds });
          st.profiles = r.body; st.message = `Capture: ${status(r)}${explain(r) ? ` · ${words(explain(r))}` : ""}`;
        }
        if (form.dataset.pcForm === "objectives") {
          st.draft = String(values.get("settings")); if (st.draft.length > 65536) throw new Error("Settings document is too large");
          const draftRevision = st.draftRevision;
          const body = JSON.parse(st.draft); if (body.schema_version !== 1 || !Array.isArray(body.objectives) || !Array.isArray(body.rules)) throw new Error("Version 1 settings require objectives and rules arrays");
          const r = await panelRead("action", "put", "/api/perf/objectives", () => true, { schema_version: 1, objectives: body.objectives, rules: body.rules });
          if (!r.ok || r.body?.status !== "ok") throw new Error(explain(r) || "Settings were not saved");
          await standing();
          if (st.draftRevision === draftRevision) { st.draft = null; st.editorOpen = false; }
          st.message = `Settings saved${st.draftRevision !== draftRevision ? ". Newer draft edits are unsaved." : "."}`; read(true);
        }
      } catch (error) { st.message = error.message || "Action failed"; }
      finally { if (actionStarted) st.actionBusy = false; repaint(); }
    });
    // what a person types or picks in the cohort form is held until it is measured or the cohort changes under it
    const keepDraft = (e) => { const form = e.target.closest?.("[data-pc-form=\"query\"]"); if (form && host()?.contains(form) && e.target.name && e.target.type !== "hidden") st.queryDraft = { ...st.queryDraft, [e.target.name]: e.target.value }; };
    document.addEventListener("input", keepDraft);
    document.addEventListener("change", keepDraft);
    document.addEventListener("input", (e) => { if (host()?.contains(e.target) && e.target.name === "settings") { st.draft = e.target.value; st.draftRevision++; e.target.rows = Math.max(8, st.draft.split("\n").length + 1); } });
    document.addEventListener("toggle", (e) => {
      const el = e.target;
      if (!host()?.contains(el)) return;
      if (el.matches?.("[data-pc-editor]")) { st.editorOpen = el.open; return; }
      // the second step: opening it draws and reads the cohort; a details drawn already open fires its own toggle, which
      // finds the state already matching and does nothing
      if (el.matches?.("[data-pc-deep]")) {
        if (st.deep === el.open) return;
        st.deep = el.open;
        if (st.deep && fingerprint() !== st.key) { clearMeasurements(); st.query = queryNow(); read(); } else repaint();
        return;
      }
      if (el.matches?.(".pc-inactive-commands")) { st.inactiveOpen = el.open; return; }
      if (!el.matches?.("[data-pc-command]")) return;
      if (el.open) st.commands.add(el.dataset.pcCommand); else st.commands.delete(el.dataset.pcCommand);
    }, true);
    document.addEventListener("change", async (e) => {
      if (!host()?.contains(e.target)) return;
      const el = e.target;
      // exact dates live with the other filters: picking them opens that row where the From and Until clocks stand
      if (el.name === "window" && !el.value && !st.advanced) {
        st.advanced = true; host().querySelector(".pc-query-extra").hidden = false;
        host().querySelector('[data-pc="filters"]')?.setAttribute("aria-expanded", "true");
      }
      if (el.name === "historyMetric") { st.historyMetric = el.value; repaint(); }
      if (el.name === "openRequest") { st.openRequest = el.value; repaint(); }
      if (el.name === "baseline") { st.selected = Number(el.value); repaint(); }
      if (el.matches("[data-pc-import]")) {
        try {
          const file = el.files?.[0]; if (!file || file.size > MAX_BYTES) throw new Error("Import must be a baseline JSON file under 750 KB");
          const clean = importBaseline(JSON.parse(await file.text()));
          saveStore([clean, ...st.baselines]); st.selected = 0; st.message = "Baseline imported locally";
        } catch (error) { st.message = error.message; } repaint();
      }
    });
    document.addEventListener("click", async (e) => {
      if (!host()?.contains(e.target)) return;
      const tab = e.target.closest("[data-pc-tab]"); if (tab) { st.tab = tab.dataset.pcTab; if (st.tab === "timeline" && !st.replies.rows) read(true); else repaint(); return; }
      const button = e.target.closest("[data-pc]"); if (!button) return;
      const action = button.dataset.pc; let actionStarted = false;
      try {
        if (action === "refresh") { readPulse(true); if (st.deep) read(true, true); return; }
        if (action === "completed-window") { const q = completedWindow(); if (!q || st.busy) return; options.apply(q); st.message = "Selected the completed window. Coverage is reported below."; st.key = null; read(true); return; }
        if (action === "filters") {
          // Disclosure must preserve the query being edited, not redraw it from the last applied cohort.
          st.advanced = !st.advanced;
          host().querySelector(".pc-query-extra").hidden = !st.advanced;
          button.setAttribute("aria-expanded", String(st.advanced));
          return;
        }
        if (action === "save-baseline") { saveStore([snapshot(st.replies.summary.body, host().querySelector('[name="baselineName"]').value), ...st.baselines]); st.selected = 0; st.message = "Baseline saved in this browser"; }
        if (action === "delete-baseline") { saveStore(st.baselines.filter((_, i) => i !== st.selected)); st.selected = 0; }
        if (action === "export-baseline") download(new Blob([JSON.stringify(st.baselines[st.selected], null, 2)], { type: "application/json" }), "splice-performance-baseline.json");
        if (action === "apply-baseline") {
          const q = st.baselines[st.selected].summary.query, window = current().window || "";
          options.apply({ filters: q.filters, source: q.source, objective_id: q.objective_id, window, ...(window ? {} : { from_ms: st.query.from_ms, until_ms: st.query.until_ms }) });
          st.key = null; read(true);
        }
        if (["objectives-reload", "profile-refresh", "profile-stop", "profile-download"].includes(action)) {
          if (actionPending()) { st.message = "A performance action is pending. Wait for it to finish."; repaint(); return; }
          if (action === "profile-refresh") { await refreshProfile(); return; }
          st.actionBusy = actionStarted = true; st.message = "Performance action pending…"; repaint();
          if (action === "objectives-reload") {
            const draftRevision = st.draftRevision; st.draft = null; resetBuilders(); await standing();
            st.message = st.settingsReply?.ok ? "Objective settings reloaded" : `Objective settings: ${status(st.settingsReply)} · ${words(explain(st.settingsReply))}`;
            if (st.draftRevision !== draftRevision) st.message += ". Newer draft edits are unsaved.";
          }
          if (action === "profile-stop") { const r = await panelRead("action", "post", `/api/perf/profiles/${encodeURIComponent(st.profiles.profile_id)}/stop`, () => true, {}); st.profiles = r.body; st.message = `Capture: ${status(r)}${explain(r) ? ` · ${words(explain(r))}` : ""}`; }
          if (action === "profile-download") {
            const id = st.profiles.profile_id, path = `/api/perf/profiles/${encodeURIComponent(id)}/artifact`, options = { refusalReasons: ARTIFACT_REFUSALS };
            let r = await panelRead("action", "blob", path, attached, options);
            const retried = await API.retryCapacity([{ path, answer: r }], attached,
              (item, retryApi, canStart) => panelRead("action", "blob", item.path, () => attached() && canStart(), options, retryApi));
            if (retried.length) r = retried[0].answer;
            if (!r.ok) throw new Error(`Artifact: ${status(r)}${explain(r) ? ` · ${words(explain(r))}` : ""}`);
            download(r.body, `splice-profile-${id}.jfr`); st.message = "Profile artifact downloaded";
          }
        }
        if (action === "next") {
          if (st.busy || st.rowsBusy || !st.replies.rows?.body?.next_cursor) return;
          const gen = st.generation, abort = st.abort, p = params(st.rowsQuery);
          const active = () => gen === st.generation && !abort.signal.aborted && host()?.isConnected;
          p.set("cursor", st.replies.rows.body.next_cursor); p.set("page_size", "100");
          st.rowsBusy = true; repaint();
          try {
            const path = `/api/perf/turns?${p}`;
            let r = await queryRead(path, active);
            const retried = await API.retryCapacity([{ path, answer: r }], active,
              (item, retryApi, canStart) => queryRead(item.path, () => active() && canStart(), retryApi), abort.signal);
            if (retried.length) r = retried[0].answer;
            if (active()) st.replies.rows = r;
          }
          finally { if (active()) st.rowsBusy = false; }
        }
      } catch (error) { st.message = error.message || "Action failed"; }
      finally { if (actionStarted) st.actionBusy = false; }
      repaint();
    });
    return { html, read, query: () => st.query, lead, showResults: () => { st.tab = "overview"; repaint(); } };
  }
  function fromAddress(fallback = {}) {
    const p = new URLSearchParams(location.search), filters = { ...fallback.filters };
    for (const k of SELECTORS) if (p.has(k)) { if (p.get(k)) filters[k] = p.get(k); else delete filters[k]; }
    if (filters.project === "-") filters.project = "none";
    if (filters.outcome === "fail") filters.outcome = "failed";
    if (filters.outcome === "all") delete filters.outcome;
    if (p.get("contract") !== "1" && filters.account?.includes("|")) {
      const [head, ...labels] = filters.account.split("|"); filters.head = head; filters.account = labels.join("|");
    }
    return { ...fallback, filters, window: p.has("from") ? "" : p.get("win") || fallback.window, from_ms: p.has("from") ? Number(p.get("from")) : fallback.from_ms, until_ms: p.has("until") || p.has("to") ? Number(p.get("until") || p.get("to")) : fallback.until_ms, source: p.get("source") || fallback.source || "auto", objective_id: p.get("objective") || fallback.objective_id };
  }
  return { create, params, address, fromAddress, snapshot, comparison };
})();
// THE MEASUREMENTS, ONE STEP AWAY. Usage leads with Results by command and Requests with its requests; Performance
// stands under one heading line that names what it holds, folded until it is opened, and reads nothing while folded.
// Opened, it answers first whether anything is slower or failing more than usual (the last hour against the last 7
// days); the whole measurement console (its cohort, coverage and nine views) is its second step. A page opened from a
// performance link opens both.
const PERF_FOLD = {
  html(open, body) {
    const head = `<button class="pc-fold-head" type="button" data-act="perffold" aria-expanded="${open}">`
      + `<h2>Performance</h2><span class="pc-fold-says">Is anything slower or failing more than usual, and measurements for any period</span>`
      + `<span class="pc-fold-go">${open ? "Hide" : "Show"}${DRAW.mpChev(open)}</span></button>`;
    return `<section class="pc-fold${open ? " open" : ""}">${head}${open ? body() : ""}</section>`;
  },
};
const createPerfHeads = (scope, helpers) => {
  let override = null, addressActive = true, lastNative = "";
  const canonicalAccount = (query) => {
    const { head, account } = query.filters;
    if (!head || !account) return query;
    const labels = account.split("\u001f"), rows = helpers.S.accounts.filter((a) => a.ref && (a.heads || []).includes(head));
    const found = rows.filter((a) => {
      const place = a.login_place?.id;
      const aliases = [a.ref, a.account_labels?.[head], a.selector_key, a.label, a.edit_target?.id, place, place ? `native:${place}` : null, a.single_login === true ? "primary" : null].filter(Boolean);
      return labels.every((v) => aliases.includes(v));
    });
    return found.length === 1 ? { ...query, filters: { ...query.filters, account: found[0].ref } } : query;
  };
  const base = () => {
    const { ui } = helpers, f = ui.f || {}, filters = { local: "0" };
    if (ui.project) filters.project = ui.project === NO_PROJECT_KEY ? "none" : ui.project;
    if (f.model) filters.model = f.model;
    if (f.agent) filters.session = f.agent;
    if (f.account) { const [head, ...parts] = f.account.split("|"); filters.head = head; filters.account = parts.join("|"); }
    const native = { window: ui.win, filters }, key = JSON.stringify(native);
    if (lastNative && lastNative !== key) addressActive = false;
    lastNative = key;
    return canonicalAccount(addressActive ? PERF_CONSOLE.fromAddress(native) : native);
  };
  let lastBase = "";
  const sampledFlag = (head, query) => {
    const native = base().filters, picked = query.filters || {};
    if (Object.keys(native).some((k) => k !== "local")) return null;
    if (Object.keys(picked).some((k) => k !== "head" && picked[k] !== native[k]) || Object.keys(native).some((k) => native[k] !== picked[k])) return null;
    const flag = helpers.flagOf(head);
    // The legacy page's flag is explicitly sampled; a missing end or unknown custom cohort cannot certify this interval.
    return flag && Number.isFinite(flag.to) ? flag : null;
  };
  return PERF_CONSOLE.create(scope, { id: "usage", query: () => { const b = base(), key = JSON.stringify(b); if (lastBase && lastBase !== key) override = null; lastBase = key; return override || b; },
    apply: (q) => {
      const before = base(), labels = (before.filters.account || "").split("\u001f");
      override = q;
      if (labels.length > 1 && q.filters.head === before.filters.head && labels.includes(q.filters.account)) {
        lastBase = ""; lastNative = ""; addressActive = false;
        helpers.selectAccount(q.filters.head, q.filters.account);
      }
    }, commandName: helpers.cmdOf, ready: () => helpers.S.stood, changed: helpers.changed, sampledFlag });
};
