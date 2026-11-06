// Shared performance view inside Usage and Requests. Every population, rate and percentile is server-owned.
"use strict";
const PERF_CONSOLE = (() => {
  const FILTERS = ["head", "model", "transport", "outcome", "session", "project", "account", "local", "compact", "streaming"];
  const SELECTORS = [...FILTERS, "silence_ms", "queued_ms", "resumed", "started_over", "unattributed", "test"];
  const WINDOWS = { "1m": 60000, "5m": 300000, "15m": 900000, "1h": 3600000, "5h": 18000000, "24h": 86400000, "7d": 604800000, "30d": 2592000000 };
  const safe = (v) => String(v ?? "").replace(/[&<>"']/g, (c) => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" })[c]);
  const num = (v) => typeof v === "number" && Number.isFinite(v) ? v.toLocaleString("en-US", { maximumFractionDigits: 3 }) : "—";
  const words = (v) => String(v ?? "Unknown").replace(/_/g, " ");
  const date = (v) => Number.isFinite(v) ? new Date(v).toLocaleString() : "Unknown time";
  const entries = (v) => v && typeof v === "object" && !Array.isArray(v) ? Object.entries(v) : [];
  const scalar = (v) => typeof v === "number" ? num(v) : typeof v === "boolean" ? String(v) : v == null ? "—" : safe(words(v));
  const note = (s) => `<p class="pc-note">${safe(s)}</p>`;
  const table = (heads, rows, caption) => `<div class="pc-table-wrap" tabindex="0" role="region" aria-label="${safe(caption)}"><table class="pc-table"><caption>${safe(caption)}</caption><thead><tr>${heads.map((h) => `<th scope="col">${safe(h)}</th>`).join("")}</tr></thead><tbody>${rows.map((r) => `<tr>${r.map((v, i) => `<${i ? "td" : 'th scope="row"'}>${v}</${i ? "td" : "th"}>`).join("")}</tr>`).join("")}</tbody></table></div>`;
  const facts = (v) => `<dl class="pc-facts">${entries(v).filter(([, x]) => x == null || ["string", "number", "boolean"].includes(typeof x)).map(([k, x]) => `<div><dt>${safe(words(k))}</dt><dd>${scalar(x)}</dd></div>`).join("")}</dl>`;
  const section = (title, body) => `<section class="pc-section"><h3>${safe(title)}</h3>${body}</section>`;
  const issues = (v) => [...(v?.coverage?.issues || []), ...(v?.issues || [])].map((x) => typeof x === "string" ? x : x.code).filter(Boolean);
  const status = (r) => !r ? "Loading" : !r.ok ? r.status === 0 ? "Offline" : r.status === 404 ? "Unsupported" : "Error" : (words(r.body?.status || "ok").replace(/^./, (c) => c.toUpperCase()));
  const explain = (r) => r?.body?.error?.reason || r?.body?.error?.code || issues(r?.body).join(" · ");
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
    const st = { key: null, query: null, replies: {}, tab: "overview", busy: false, abort: null, timer: null, generation: 0, message: "", baselines: loadBaselines(), selected: 0, draft: null, profiles: null, settings: null, openRequest: null, cursor: null, advanced: false };
    const clearMeasurements = () => { st.replies = Object.fromEntries(["schema", "caps", "preferences"].filter((k) => st.replies[k]).map((k) => [k, st.replies[k]])); };
    const current = () => options.query();
    const host = () => document.querySelector(`[data-performance="${options.id}"]`);
    const repaint = () => { const h = host(); if (h) h.innerHTML = inner(); };
    function queryNow() {
      const input = current(), filters = { ...input.filters };
      const query = { from_ms: input.from_ms, until_ms: input.until_ms, source: input.source || "auto", filters, objective_id: input.objective_id || null };
      if (!Number.isFinite(query.until_ms)) query.until_ms = Date.now();
      if (!Number.isFinite(query.from_ms)) query.from_ms = query.until_ms - (WINDOWS[input.window] || WINDOWS["24h"]);
      return query;
    }
    // The fingerprint excludes a moving "now"; one interval is frozen for all views in a read cycle.
    const fingerprint = () => JSON.stringify(current());
    async function read(force = false) {
      const key = fingerprint();
      if (st.busy && key === st.key && !force) return;
      st.abort?.abort(); clearTimeout(st.timer);
      const abort = st.abort = new AbortController(), api = API.scoped(abort.signal), gen = ++st.generation;
      const changed = key !== st.key; st.key = key; st.query = queryNow(); st.busy = true;
      if (changed) { clearMeasurements(); st.cursor = null; st.openRequest = null; }
      repaint();
      const p = params(st.query).toString();
      const paths = { summary: `/api/perf/summary?${p}`, series: `/api/perf/series?${p}`, diagnose: `/api/perf/diagnose?${p}`, rows: `/api/perf/turns?${p}&page_size=100`, runtime: "/api/perf/runtime", alerts: `/api/perf/alerts?from=${st.query.from_ms}&until=${st.query.until_ms}`, alertStatus: "/api/perf/alerts/status", preferences: "/api/alerts" };
      await Promise.all(Object.entries(paths).map(async ([key, path]) => { const r = await api.get(path); if (gen === st.generation && !abort.signal.aborted) st.replies[key] = r; }));
      if (gen !== st.generation || abort.signal.aborted) return;
      st.busy = false;
      if (st.replies.summary?.body?.query) {
        st.query = st.replies.summary.body.query;
        options.resolved?.(st.query);
      }
      repaint();
      if (st.profiles?.status === "running") refreshProfile();
      notifyAlerts();
      st.timer = setTimeout(() => { if (!document.hidden && !host()?.contains(document.activeElement)) read(); else st.timer = setTimeout(() => read(), 15000); }, 15000);
    }
    async function standing() {
      const [schema, caps, profiles, settings, preferences] = await Promise.all(["/api/perf/schema", "/api/perf/profiles/capabilities", "/api/perf/profiles", "/api/perf/objectives", "/api/alerts"].map((p) => API.get(p)));
      Object.assign(st.replies, { schema, caps, preferences }); st.profilesReply = profiles; st.profiles = profiles.ok ? profiles.body : null; st.settings = settings.ok ? settings.body : null; st.settingsReply = settings; repaint();
    }
    const field = (k, label, value = "", type = "text", extra = "") => `<label>${safe(label)}<input class="field" name="${safe(k)}" type="${type}" value="${safe(value)}" ${extra}></label>`;
    const select = (k, label, values, picked) => `<label>${safe(label)}<select name="${safe(k)}" class="field">${values.map(([v, t]) => `<option value="${safe(v)}"${String(picked ?? "") === v ? " selected" : ""}>${safe(t)}</option>`).join("")}</select></label>`;
    const metricCoverage = (m) => `${m?.coverage?.complete === true ? "Complete" : m?.coverage ? "Partial" : "Unknown"} · ${num(m?.coverage?.observed_ms)} ms · ${safe(words(m?.filter_attribution))}`;
    function controls() {
      const q = st.query || queryNow(), input = current(), f = q.filters;
      return `<form class="pc-query" data-pc-form="query"><div class="pc-query-main">`
        + select("window", "Window", [["", "Exact interval"], ...Object.keys(WINDOWS).map((x) => [x, x])], input.window || "")
        + field("head", "Command", f.head) + field("model", "Model", f.model) + field("project", "Project", f.project)
        + select("objective", "Objective", [["", "Unconfigured"], ...(st.settings?.objectives || []).map((x) => [x.id, x.id])], q.objective_id)
        + `<button class="act small" type="submit">Apply cohort</button><button class="act quiet small" type="button" data-pc="filters" aria-expanded="${st.advanced}">All filters</button></div>`
        + `<div class="pc-query-extra"${st.advanced ? "" : " hidden"}>${field("from", "From · epoch ms", q.from_ms, "number")}${field("until", "Until · exclusive epoch ms", q.until_ms, "number")}`
        + ["transport", "outcome", "session", "account", "silence_ms", "queued_ms", "resumed", "started_over", "unattributed", "test"].map((k) => field(k, words(k), f[k])).join("")
        + ["local", "compact", "streaming"].map((k) => select(k, words(k), [["", "Any"], ["1", "Yes"], ["0", "No"]], f[k])).join("")
        + select("source", "Source", [["auto", "Automatic"], ["live", "Live"], ["history", "History"], ["evidence", "Recorded evidence"], ["legacy", "Legacy rows"]], q.source) + `</div></form>`;
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
      return `<div class="pc-coverage" role="status"><strong>${safe(status(r))}${stale ? " · Stale" : ""}${st.busy ? " · Refreshing" : ""}</strong><span>${safe(date(query.from_ms))} → ${safe(date(query.until_ms))}</span><span>Observed ${num(s?.observed_duration_ms)} / requested ${num(s?.requested_duration_ms)} ms</span>${s?.truncated ? "<b>Truncated</b>" : ""}</div>`
        + `<div class="pc-actions"><button class="act quiet small" data-pc="completed-window"${!completed || st.busy ? " disabled" : ""}>Use completed window</button><span class="pc-note">${completed ? `From ${safe(date(completed.from_ms))} to ${safe(date(completed.until_ms))} · same ${num(completed.until_ms - completed.from_ms)} ms duration` : "No certified recording boundary available for this duration."}</span></div>`
        + (completed ? note("This selects the interval ending at the certified recording boundary. Late records can revise it; coverage still applies. Your selected interval stays unchanged until you choose it.") : "")
        + (explain(r) ? note(words(explain(r))) : "")
        + (accountGap ? note("Account names are temporarily unavailable. Exact recorded account IDs still work. Name matching uses the last Accounts read for up to 60 seconds; opening Accounts refreshes it. This view does not refresh accounts automatically.") : "")
        + (s?.missing_fields?.length ? `<details class="pc-detail"><summary>${s.missing_fields.length} unavailable measurements</summary>${note(s.missing_fields.map(words).join(" · "))}</details>` : "");
    }
    function overview() {
      const s = st.replies.summary?.body;
      if (!s?.rates) return note(`${status(st.replies.summary)} · Measurements have not been returned.`);
      const rates = s.rates;
      const hero = [["Arrivals / s", "arrivals_per_s"], ["Completions / s", "completions_per_s"], ["Successful / s", "successful_completions_per_s"], ["Attempts / s", "attempts_per_s"]];
      const headline = `<div class="pc-meters">${hero.map(([label, k]) => `<div><span>${label}</span><strong>${num(rates[k])}</strong></div>`).join("")}</div>`;
      const traffic = table(["Traffic", "Per second"], [["Input tokens", "input_tokens"], ["Output tokens", "output_tokens"], ["Cached tokens", "cached_tokens"], ["Upstream bytes", "upstream_bytes"], ["Downstream bytes", "downstream_bytes"], ["Upstream events", "upstream_events"], ["Downstream events", "downstream_events"]].map(([label, key]) => [label, num(rates[`${key}_per_s`])]), "Token, byte and event throughput");
      const g = s.goodput || {};
      const goodput = g.status === "unconfigured" ? note("Select a configured objective to measure goodput.") : facts({ objective: g.objective_id, revision: g.objective_revision, status: g.status, good: g.good, bad: g.bad, known_evaluable: g.known_evaluable, observed_arrivals: g.observed_arrivals, pending: g.pending, unknown: g.unknown, whole_window_eligible: g.eligible, whole_window_good_per_s: g.per_s, good_fraction: g.good_fraction, known_good_per_s: g.known_good_per_s, known_good_rate_is_lower_bound: g.known_good_rate_is_lower_bound }) + note(words(g.attribution));
      const heads = entries(s.legacy).map(([head, h]) => [`<a href="${safe(address(st.query, { head }))}">${safe(head)}</a>`, num(h.count), num(h.local_steps), num(h.total_ms?.p50), num(h.total_ms?.p95), safe(h.metrics_complete === false ? "Partial" : h.read_status || "Unknown")]);
      return headline + note(`Rates use requested wall duration. ${words(rates.interpretation)}. Attribution: ${words(rates.filter_attribution)}.`)
        + `<div class="pc-grid">${section("Throughput", traffic)}${section("Objective goodput", goodput)}${section("Request populations", facts(s.counts) + note("Arrival and completion windows are separate. Model-at-arrival may be unknown; returned attribution governs each rate."))}</div>`
        + table(["Command", "Requests", "Local steps", "p50 · ms", "p95 · ms", "Coverage"], heads, "Results by command · selected cohort")
        + `<details class="pc-detail"><summary>Population coverage and outcomes</summary>${facts(s.outcomes)}${entries(s.population_coverage).map(([k, v]) => section(words(k), facts(v))).join("")}</details>`;
    }
    function distribution() {
      const s = st.replies.summary?.body;
      const rows = entries(s?.histograms).map(([key, h]) => [safe(words(key)), num(h.count), num(h.p50), num(h.p95), num(h.p99), metricCoverage(h), `<details><summary>Distribution</summary>${note(words(h.approximation))}${note(st.replies.schema?.body?.metrics?.[key]?.population || "Population not described")}${table(["Upper bound · ms", "Count"], (h.buckets || []).map((n, i) => [i < (h.bounds_ms || []).length ? num(h.bounds_ms[i]) : "Overflow", num(n)]), `${words(key)} bucket counts`)}</details>`]);
      return note("Independent measured durations; stages, attempts and tools can overlap and do not add up to request elapsed time. Percentiles are returned bucket bounds, not exact samples.")
        + table(["Measurement", "Samples", "p50 · ms", "p95 · ms", "p99 · ms", "Metric coverage / attribution", "Buckets"], rows, "Latency distributions")
        + (!rows.length ? note("No histogram measurements for this cohort.") : "");
    }
    function history() {
      const series = st.replies.series?.body?.series || [], gauges = st.replies.summary?.body?.gauges || {};
      const row = ([k, g]) => [safe(words(k)), num(g.last), num(g.max), num(g.time_weighted_mean), num(g.sample_count), num(g.scope_count), num(g.covered_ms), metricCoverage(g)];
      const tableSummary = table(["Gauge", "Latest scope", "Max scope", "Weighted mean", "Samples", "Scopes", "Covered scope-ms", "Metric coverage / attribution"], entries(gauges).map(row), "Observed resource gauges");
      const points = series.flatMap((p) => entries(p.metrics).filter(([, m]) => Object.hasOwn(m, "last")).map(([k, m]) => ({ p, k, m })));
      const chosen = st.historyMetric || points[0]?.k;
      const filtered = points.filter((x) => x.k === chosen).slice(-120);
      const values = filtered.map((x) => x.m.last).filter((v) => typeof v === "number"), max = Math.max(1, ...values);
      const graph = filtered.length ? `<div class="pc-history" role="img" aria-label="${safe(words(chosen))}: latest reading per returned interval and scope. Table follows.">${filtered.map(({ p, m }) => `<span title="${safe(date(p.from_ms))} · ${safe(p.scope?.head)} · ${num(m.last)}" style="--height:${typeof m.last === "number" && m.last > 0 ? Math.max(1, m.last / max * 100) : 0}%"><i></i></span>`).join("")}</div>` : note(`${status(st.replies.series)} · No sampled history for this cohort.`);
      return note("Latest is one observed scope, never a process total. Max is the largest scope sample; mean is weighted over observed scope time. Covered milliseconds are summed over scopes; missing scopes are unknown.")
        + tableSummary + select("historyMetric", "History measurement", [...new Set(points.map((x) => x.k))].map((k) => [k, words(k)]), chosen) + graph
        + table(["From", "Until", "Head / model / transport", "Latest", "Max", "Weighted mean", "Samples"], filtered.map(({ p, m }) => [safe(date(p.from_ms)), safe(date(p.until_ms)), safe([p.scope?.head, p.scope?.model, p.scope?.transport].map((x) => x ?? "unknown").join(" / ")), num(m.last), num(m.max), num(m.time_weighted_mean), num(m.sample_count)]), "History · last 120 returned scope intervals")
        + section("Event counters", table(["Counter", "Measurements", "Sum of deltas", "Per second", "Metric coverage / attribution"], entries(st.replies.summary?.body?.counters).map(([k, c]) => [safe(words(k)), num(c.count), num(c.sum), num(c.per_s), metricCoverage(c)]), "Counters · requested-duration rates"));
    }
    function runtime() {
      const r = st.replies.runtime, v = r?.body;
      return note(`Current process sample · ${status(r)} · ${date(v?.as_of_ms)} · age ${num(v?.age_ms)} ms. Historical resource measurements are in History for the selected interval.`)
        + facts(v?.identity) + table(["Runtime measurement", "Value"], entries(v?.measurements).map(([k, x]) => [safe(words(k)), scalar(x)]), "CPU, GC, memory, files and exporter health")
        + table(["Capability", "Supported", "Reason"], (v?.capabilities || []).map((x) => [safe(x.id), scalar(x.supported), safe(words(x.reason))]), "Runtime capabilities") + note(issues(v).map(words).join(" · "))
        + table(["Sink", "Observed", "Enqueued", "Written", "Failed", "Dropped", "Pending", "Oldest pending · ms", "Last success"], (st.replies.summary?.body?.coverage?.sinks || []).map((x) => [safe(x.sink), num(x.observed), num(x.enqueued), num(x.written), num(x.failed), num(x.dropped), num(x.pending), num(x.oldestPendingMs), safe(date(x.lastSuccessEpochMs))]), "Recording and export health · returned source coverage");
    }
    function timeline() {
      const d = st.replies.diagnose?.body, requests = d?.timeline?.requests || [];
      const selected = requests.find((x) => x.request_id === st.openRequest) || requests[0];
      const events = selected?.events || [], arrival = events.find((e) => e.kind === "REQUEST_ARRIVED"), origin = arrival?.monotonic_ms ?? events[0]?.monotonic_ms;
      const measurement = (e) => entries(e.measurements).map(([k, v]) => `${words(k)}: ${num(v)}`).join(" · ");
      const rows = events.map((e) => [safe(words(e.kind)), num(Number.isFinite(e.monotonic_ms) && Number.isFinite(origin) ? e.monotonic_ms - origin : null), safe(e.attempt_id ?? "—"), safe(e.tool_id ?? "—"), safe(measurement(e)), turnLink(e, e.trace_id || e.turn_id || "Turn unavailable")]);
      const evidence = table(["Measured condition", "Mean · ms", "Interpretation", "Next measurement"], (d?.evidence || []).map((e) => [safe(words(e.metric)), num(e.mean_ms), safe(`${words(e.classification)} · ${words(e.inference)}`), safe(words(e.next_falsifying_measurement))]), "Diagnosis evidence");
      const span = events.length ? events.at(-1).monotonic_ms - origin : 0;
      const waterfall = span > 0 ? `<div class="pc-waterfall" role="img" aria-label="Recorded progress on the request clock; exact values in the table below">${events.filter((e) => /CONTENT|CLIENT|ATTEMPT|TOOL|REQUEST/.test(e.kind)).map((e) => `<div><span>${safe(words(e.kind))}${e.attempt_id ? ` · ${safe(e.attempt_id)}` : ""}${e.tool_id ? ` · ${safe(e.tool_id)}` : ""}</span><i><b style="left:${Math.max(0, Math.min(100, (e.monotonic_ms - origin) / span * 100))}%"></b></i><small>${num(e.monotonic_ms - origin)} ms</small></div>`).join("")}</div>` : "";
      return evidence + note("Content and successful flush checkpoints below are separate from transport bytes. Attempt and tool IDs have independent clocks; their durations overlap and are not additive.")
        + select("openRequest", "Representative request", requests.map((r) => [r.request_id, r.request_id]), selected?.request_id)
        + (selected ? `<div class="pc-actions">${turnLink(selected, "Open recorded turn")}</div>` + facts({ command: selected.head, turn: selected.turn_id, trace: selected.trace_id, session: selected.session_id }) : "")
        + (d?.timeline?.truncated ? note("Representative timeline is truncated; it is not the complete population.") : "")
        + note(arrival ? "Elapsed time is measured from the recorded request arrival." : "Arrival is unavailable. Elapsed time starts at the first retained event.")
        + waterfall + table(["Event", "Elapsed · ms", "Attempt", "Tool", "Measurements", "Trace reference"], rows, "Request, attempt and tool timeline")
        + (!events.length ? note(`${status(st.replies.diagnose)} · No request timeline evidence.`) : "")
        + section("Resource evidence", facts(d?.resources) + table(["Measured resource", "Value", "Reported capacity", "Fraction", "Interpretation"], (d?.resources?.conditions || []).map((x) => [safe(words(x.metric)), num(x.value), num(x.capacity), num(x.fraction), safe(words(x.inference))]), "Resource occupancy · sample time only")) + requestRows();
    }
    function turnLink(record, label) {
      return record?.head && record?.turn_id ? `<a href="${safe(address(st.query, { head: record.head }, `${record.head}:${record.turn_id}`))}">${safe(label)}</a>` : safe(record?.trace_id || record?.turn_id || "Turn link unavailable");
    }
    function requestRows() {
      const r = st.replies.rows, v = r?.body;
      return table(["Request", "Time", "Command", "Model", "Outcome"], (v?.rows || []).map((x) => [turnLink(x, x.turn_id), safe(date(x.ts)), safe(x.head), safe(x.model ?? "Unknown"), safe(words(x.outcome))]), "Recorded request rows · returned source order")
        + note(`${status(r)} · ${num(v?.total_matching)} matching records · ${v?.record_unit || "record"}`)
        + (v?.next_cursor ? `<button class="act small" data-pc="next">Next records</button>` : "");
    }
    function baselines() {
      const selected = st.baselines[st.selected], summary = st.replies.summary?.body;
      const c = selected && summary?.identity_cohorts ? comparison(selected.summary, summary) : null;
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
        + note(`Up to ${LIMIT} snapshots in this browser. Saved measurements include the query, workload groups, build, settings, objective and coverage. Imported files are local records, not independently verified measurements.`)
        + select("baseline", "Saved baseline", st.baselines.map((b, i) => [String(i), `${b.name} · ${date(b.saved_at_ms)}`]), String(st.selected))
        + (selected ? `<div class="pc-actions"><button class="act quiet small" data-pc="export-baseline">Export JSON</button><button class="act quiet small" data-pc="apply-baseline">Apply saved cohort</button><button class="act quiet small" data-pc="delete-baseline">Delete</button></div>`
          + facts({ saved_at: date(selected.saved_at_ms), measured_at: date(selected.summary.as_of_ms), source: selected.summary.query.source, from: date(selected.summary.query.from_ms), until: date(selected.summary.query.until_ms), objective_revision: selected.summary.goodput.objective_revision })
          + section(c?.status || "Waiting for current summary", note(c ? `Different: ${c.different.join(", ") || "none known"}. Unknown or mixed: ${c.unknown.join(", ") || "none in the measured comparison"}. Changed variables: ${c.changed.join(", ") || "none known"}.` : "Read the current cohort first.")
            + note("Matching compares measured request groups and their proportions, including actual outcomes and token/cache counts. It covers effective settings only. Provider topology and remote conditions are unverified; a match does not establish cause or rank providers.")
            + (c?.reasons.length ? note(`Evidence gaps: ${c.reasons.join(" · ")}`) : "") + values)
          + `<div class="pc-grid">${section("Saved evidence", evidence(selected.summary))}${section("Current evidence", evidence(summary))}</div>`
          + `<details class="pc-detail"><summary>Measured workload groups</summary>${section("Saved", partitions(selected.summary))}${section("Current", partitions(summary))}</details>`
          + `<details class="pc-detail"><summary>Saved cohort and configuration evidence</summary>${facts(selected.summary.query.filters)}${selected.summary.identity_cohorts.map(facts).join("")}${facts(selected.summary.configuration)}${selected.summary.configuration.intervals.map(facts).join("")}${note(`Settings exclude: ${(selected.summary.configuration.excluded_dimensions || []).map(words).join(", ") || "not recorded"}.`)}</details>` : note("No saved baselines."));
    }
    function profiles() {
      const caps = st.replies.caps?.body?.capabilities || [], p = st.profiles;
      return note("Capture starts only when you press Start capture. It records the current process, independently of request filters. Bounds: 1–300 seconds; one capture per process. No native profiler is downloaded.")
        + `<form data-pc-form="profile" class="pc-actions">${select("profile", "Profiler and mode", caps.map((c) => [c.id, `${c.id}${c.supported ? "" : ` · ${words(c.reason)}`}`]), st.profileMode || "jfr.cpu")}${field("duration", "Duration · seconds", 60, "number", 'min="1" max="300" required')}<button class="act small" type="submit"${st.actionBusy ? " disabled" : ""}>Start capture</button></form>`
        + note(`Capabilities: ${status(st.replies.caps)}`) + table(["Capability", "Supported", "Reason"], caps.map((c) => [safe(c.id), scalar(c.supported), safe(words(c.reason))]), "Profile availability")
        + (p ? facts({ status: p.status, profile_id: p.profile_id, from: date(p.from_ms), until: date(p.until_ms), size_bytes: p.size_bytes, artifact_available: p.artifact_available }) + note([...(p.issues || []), ...(p.limitations || [])].map((x) => words(typeof x === "string" ? x : x.code)).join(" · "))
          + `<div class="pc-actions"><button class="act quiet small" data-pc="profile-refresh">Refresh status</button>${p.status === "running" ? '<button class="act small" data-pc="profile-stop">Stop capture</button>' : ""}${p.artifact_available && p.profile_id ? '<button class="act small" data-pc="profile-download">Download artifact</button>' : ""}</div>` : note(`${status(st.profilesReply)} · Profile status has not been returned.`));
    }
    function objectives() {
      const settings = st.settings;
      if (!settings) return note(`${status(st.settingsReply)} · Objective settings unavailable.`);
      const builders = `<div class="pc-grid"><details class="pc-detail"><summary>Add objective</summary><form data-pc-form="add-objective" class="pc-builder">${field("id", "Objective ID", "", "text", "required maxlength=128")}${field("cohort", "Cohort · JSON", "{}")}${["first_content_ms", "completion_ms", "queue_ms", "progress_gap_ms"].map((k) => field(k, words(k), "", "number", 'min="0"')).join("")}<button class="act small" type="submit">Add to draft</button></form></details>`
        + `<details class="pc-detail"><summary>Add alert rule</summary><form data-pc-form="add-rule" class="pc-builder">${field("id", "Rule ID", "", "text", "required maxlength=128")}${select("objective_id", "Objective", [["", "None"], ...(settings.objectives || []).map((o) => [o.id, o.id])], "")}${field("cohort", "Cohort · JSON", "{}")}${field("metric", "Metric selector", "", "text", 'required placeholder="histograms.queue_wait_ms.p95"')}${select("comparison", "Trigger when", [["above", "Above threshold"], ["below", "Below threshold"]], "above")}${field("threshold", "Threshold", "", "number", 'required step="any"')}${field("window_ms", "Window · ms", 300000, "number", 'required min="1"')}${field("min_count", "Minimum samples", 1, "number", 'required min="1"')}${field("min_coverage", "Minimum coverage · 0–1", 1, "number", 'required min="0" max="1" step="0.01"')}${field("hold_ms", "Trigger hold · ms", 0, "number", 'required min="0"')}${field("cooldown_ms", "Delivery cooldown · ms", 60000, "number", 'required min="0"')}${field("recovery_threshold", "Recovery threshold", "", "number", 'required step="any"')}${field("recovery_hold_ms", "Recovery hold · ms", 0, "number", 'required min="0"')}<label class="pc-checkbox"><input type="checkbox" name="delivery_enabled">Allow configured delivery for this rule</label><button class="act small" type="submit">Add to draft</button></form></details></div>`;
      return note(`Canonical revision ${settings.revision || "unknown"}. No rules or delivery are enabled by default.`) + builders
        + table(["Objective", "Revision", "Cohort", "First content · ms", "Completion · ms", "Queue · ms", "Progress gap · ms"], (settings.objectives || []).map((o) => [safe(o.id), safe(o.revision), safe(JSON.stringify(o.cohort)), num(o.first_content_ms), num(o.completion_ms), num(o.queue_ms), num(o.progress_gap_ms)]), "Configured objectives")
        + `<form data-pc-form="objectives"><label class="pc-json-label">Objective and alert settings · JSON<textarea class="field pc-json" name="settings" spellcheck="false" rows="15">${safe(st.draft ?? JSON.stringify({ schema_version: 1, objectives: settings.objectives || [], rules: settings.rules || [] }, null, 2))}</textarea></label>`
        + note('Rules use metric, comparison "above" or "below", threshold, window_ms, min_count ≥ 1, min_coverage 0–1, hold_ms, cooldown_ms, recovery {threshold, hold_ms} and optional delivery_enabled. Recovery must be on the healthy side. Server validates the entire document before publishing; revisions are canonical.')
        + `<div class="pc-actions"><button type="submit" class="act small"${st.actionBusy ? " disabled" : ""}>Save objectives and rules</button><button type="button" class="act quiet small" data-pc="objectives-reload">Discard edits and reload</button></div></form>`;
    }
    function alerts() {
      const now = st.replies.alertStatus?.body, history = st.replies.alerts?.body;
      const rows = (events) => (events || []).map((e) => [safe(e.rule_id), safe(`${words(e.state)}${e.active ? " · active" : ""}${e.retired ? " · retired" : ""}`), scalar(e.observed), scalar(e.threshold), num(e.sample_count), safe(words(e.coverage?.reason || e.reason || (e.coverage?.complete ? "Complete" : "Partial / unknown"))), safe(words(e.delivery_status)), `<a href="${safe(alertAddress(e))}">Inspect interval</a><details><summary>Lifecycle and coverage</summary>${facts({ pending_since: date(e.pending_since_ms), firing_since: date(e.firing_since_ms), recovery_since: date(e.recovery_since_ms), resolved_at: date(e.resolved_at_ms), continuity: e.continuity, objective_revision: e.objective_revision, rule_revision: e.rule_revision, settings_revision: e.settings_revision })}${facts(e.coverage)}${facts(e.cohort)}</details>`]);
      return note(`Current states: ${status(st.replies.alertStatus)} · History: ${status(st.replies.alerts)} · ${now?.restart_uncertain ? "Restart uncertainty · " : ""}${words(now?.reason || "")} · insufficient data never resolves an active alert.`)
        + table(["Rule", "State", "Observed", "Threshold", "Samples", "Coverage", "Delivery", "Evidence"], rows(now?.events), "Current alert states")
        + table(["Rule", "State", "Observed", "Threshold", "Samples", "Coverage", "Delivery", "Evidence"], rows(history?.events), "Alert transitions in selected interval")
        + facts({ history_status: history?.status, expired_events: history?.expired_events, delivery_rejected: now?.delivery_rejected, delivery_failures: now?.delivery_failures, enqueue_failures: now?.enqueue_failures })
        + note("Desktop notices require the rule’s delivery opt-in, the existing desktop preference, and browser permission. This page does not request permission.");
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
      return `<div class="bandhead pc-heading"><h2>Performance</h2><a href="${safe(address(st.query || queryNow()))}">Open requests ↗</a><button class="act quiet small" data-pc="refresh"${st.busy ? " disabled" : ""}>Refresh</button></div>`
        + controls() + coverage() + `<nav class="pc-tabs" aria-label="Performance views">${entries(tabs).map(([k, [title]]) => `<button type="button" data-pc-tab="${k}" aria-current="${st.tab === k ? "page" : "false"}">${title}</button>`).join("")}</nav>`
        + (st.message ? `<p class="pc-message" role="status">${safe(st.message)}</p>` : "")
        + `<div class="pc-content" aria-busy="${st.busy}">${tabs[st.tab][1]()}</div>`;
    }
    function html() {
      if (fingerprint() !== st.key) { clearMeasurements(); st.query = queryNow(); setTimeout(() => read(), 0); }
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
    async function refreshProfile() {
      const p = st.profiles?.profile_id, r = await API.get(`/api/perf/profiles${p ? `/${encodeURIComponent(p)}` : ""}`);
      if (r.ok) st.profiles = r.body; else st.message = `Profile status: ${status(r)}`; repaint();
    }
    document.addEventListener("submit", async (e) => {
      const form = e.target.closest("[data-pc-form]"); if (!form || !host()?.contains(form)) return;
      e.preventDefault(); const values = new FormData(form);
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
          list.push(item); st.draft = JSON.stringify(draft, null, 2); st.message = "Added to draft. Save objectives and rules to publish."; repaint(); return;
        }
        if (form.dataset.pcForm === "query") {
          const input = { window: values.get("window"), filters: {}, source: values.get("source"), objective_id: values.get("objective") || null };
          for (const k of SELECTORS) if (values.get(k)) input.filters[k] = String(values.get(k)).trim();
          if (!input.window) { input.from_ms = Number(values.get("from")); input.until_ms = Number(values.get("until")); if (!(input.until_ms > input.from_ms)) throw new Error("Enter a valid exact interval"); }
          options.apply(input); st.key = null; read(true); return;
        }
        if (st.actionBusy) return; st.actionBusy = true;
        if (form.dataset.pcForm === "profile") {
          const [profiler, mode] = String(values.get("profile")).split("."), durationSeconds = Number(values.get("duration"));
          if (!Number.isInteger(durationSeconds) || durationSeconds < 1 || durationSeconds > 300) throw new Error("Duration must be 1–300 seconds");
          const capability = (st.replies.caps?.body?.capabilities || []).find((x) => x.id === `${profiler}.${mode}`);
          if (!capability?.supported && capability?.reason !== "mode_requires_explicit_start_probe") throw new Error(words(capability?.reason || "Unsupported profile"));
          const r = await API.post("/api/perf/profiles", { profiler, mode, durationSeconds });
          st.profiles = r.body; st.message = `Capture: ${status(r)}`;
        }
        if (form.dataset.pcForm === "objectives") {
          st.draft = String(values.get("settings")); if (st.draft.length > 65536) throw new Error("Settings document is too large");
          const body = JSON.parse(st.draft); if (body.schema_version !== 1 || !Array.isArray(body.objectives) || !Array.isArray(body.rules)) throw new Error("Version 1 settings require objectives and rules arrays");
          const r = await API.put("/api/perf/objectives", { schema_version: 1, objectives: body.objectives, rules: body.rules });
          if (!r.ok || r.body?.status !== "ok") throw new Error(explain(r) || "Settings were not saved");
          st.draft = null; await standing(); st.message = `Saved revision ${r.body.revision}`; read(true);
        }
      } catch (error) { st.message = error.message || "Action failed"; }
      finally { st.actionBusy = false; repaint(); }
    });
    document.addEventListener("input", (e) => { if (host()?.contains(e.target) && e.target.name === "settings") st.draft = e.target.value; });
    document.addEventListener("change", async (e) => {
      if (!host()?.contains(e.target)) return;
      const el = e.target;
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
      const tab = e.target.closest("[data-pc-tab]"); if (tab) { st.tab = tab.dataset.pcTab; repaint(); return; }
      const button = e.target.closest("[data-pc]"); if (!button) return;
      const action = button.dataset.pc;
      try {
        if (action === "refresh") { read(true); return; }
        if (action === "completed-window") { const q = completedWindow(); if (!q || st.busy) return; options.apply(q); st.message = "Selected the completed window. Coverage is reported below."; st.key = null; read(true); return; }
        if (action === "filters") st.advanced = !st.advanced;
        if (action === "save-baseline") { saveStore([snapshot(st.replies.summary.body, host().querySelector('[name="baselineName"]').value), ...st.baselines]); st.selected = 0; st.message = "Baseline saved in this browser"; }
        if (action === "delete-baseline") { saveStore(st.baselines.filter((_, i) => i !== st.selected)); st.selected = 0; }
        if (action === "export-baseline") download(new Blob([JSON.stringify(st.baselines[st.selected], null, 2)], { type: "application/json" }), "splice-performance-baseline.json");
        if (action === "apply-baseline") {
          const q = st.baselines[st.selected].summary.query, window = current().window || "";
          options.apply({ filters: q.filters, source: q.source, objective_id: q.objective_id, window, ...(window ? {} : { from_ms: st.query.from_ms, until_ms: st.query.until_ms }) });
          st.key = null; read(true);
        }
        if (action === "objectives-reload") { st.draft = null; await standing(); }
        if (action === "profile-refresh") await refreshProfile();
        if (action === "profile-stop") { const r = await API.post(`/api/perf/profiles/${encodeURIComponent(st.profiles.profile_id)}/stop`, {}); st.profiles = r.body; st.message = `Capture: ${status(r)}`; }
        if (action === "profile-download") { const r = await API.blob(`/api/perf/profiles/${encodeURIComponent(st.profiles.profile_id)}/artifact`); if (!r.ok) throw new Error(`Artifact: ${status(r)}`); download(r.body, `splice-profile-${st.profiles.profile_id}.jfr`); }
        if (action === "next") { const gen = st.generation, p = params(st.query); p.set("cursor", st.replies.rows.body.next_cursor); p.set("page_size", "100"); const r = await API.scoped(st.abort.signal).get(`/api/perf/turns?${p}`); if (gen === st.generation) st.replies.rows = r; }
      } catch (error) { st.message = error.message || "Action failed"; }
      repaint();
    });
    standing();
    return { html, read, query: () => st.query, lead: () => null };
  }
  function fromAddress(fallback = {}) {
    const p = new URLSearchParams(location.search), filters = { ...fallback.filters };
    for (const k of SELECTORS) if (p.has(k)) { if (p.get(k)) filters[k] = p.get(k); else delete filters[k]; }
    return { ...fallback, filters, window: p.has("from") ? "" : p.get("win") || fallback.window, from_ms: p.has("from") ? Number(p.get("from")) : fallback.from_ms, until_ms: p.has("until") || p.has("to") ? Number(p.get("until") || p.get("to")) : fallback.until_ms, source: p.get("source") || fallback.source || "auto", objective_id: p.get("objective") || fallback.objective_id };
  }
  return { create, params, address, fromAddress, snapshot, comparison };
})();
const createPerfHeads = (scope, helpers) => {
  let override = null, addressActive = true, lastNative = "";
  const base = () => {
    const { ui } = helpers, f = ui.f || {}, filters = { local: "0" };
    if (ui.project) filters.project = ui.project === NO_PROJECT_KEY ? "none" : ui.project;
    if (f.model) filters.model = f.model;
    if (f.agent) filters.session = f.agent;
    if (f.account) { const [head, ...parts] = f.account.split("|"); filters.head = head; filters.account = parts.join("|"); }
    const native = { window: ui.win, filters }, key = JSON.stringify(native);
    if (lastNative && lastNative !== key) addressActive = false;
    lastNative = key;
    return addressActive ? PERF_CONSOLE.fromAddress(native) : native;
  };
  let lastBase = "";
  return PERF_CONSOLE.create(scope, { id: "usage", query: () => { const b = base(), key = JSON.stringify(b); if (lastBase && lastBase !== key) override = null; lastBase = key; return override || b; }, apply: (q) => { override = q; } });
};
