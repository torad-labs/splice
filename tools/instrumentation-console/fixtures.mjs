// Content-free contract fixtures for the isolated browser. Never reads the operator daemon or profile.
export const now = 1793910000000;
export const histogram = { count: 125, sum: 150000, min: 32, max: 8000, p50: 1000, p95: 5000, p99: 10000, bounds_ms: [100, 1000, 5000, 10000], buckets: [10, 80, 28, 7, 0], approximation: 'upper_inclusive_bucket_bound; overflow_quantile_unknown' };
export const gauge = { last: 4, max: 8, time_weighted_mean: 3.2, sample_count: 80, scope_count: 2, covered_ms: 450000, covered_ms_semantics: 'sum_over_observed_scopes', aggregation: { last: 'latest_single_scope_reading_not_process_sum', max: 'maximum_observed_scope_sample', time_weighted_mean: 'covered_time_weighted_mean_over_observed_scopes' } };
export const settings = { schema_version: 1, revision: 'canonical-fixture-r1', objectives: [{ id: 'interactive', revision: 'objective-fixture-r1', cohort: {}, first_content_ms: 2000, completion_ms: 60000, queue_ms: 5000, progress_gap_ms: 10000 }], rules: [] };
export function comparisonEvidence(s, complete = false) {
  return {
    status: complete ? 'ready' : 'insufficient_evidence', readiness_scope: 'measured_workload_and_effective_knob_policy_only', reasons: complete ? [] : ['request_population_partial'],
    workload: { status: complete ? 'complete' : 'partial', population_complete: complete, observed_arrivals: 138, measured_requests: complete ? 138 : 0, matching_requests: complete ? 138 : 0,
      matching_basis: 'exact_joint_frequency_v1', distribution_id: complete ? 'a'.repeat(64) : null, truncated: false, reasons: complete ? [] : ['request_population_partial'],
      as_of_ms: s.as_of_ms, coverage_basis: 'accepted_retained_observations_as_of', event_time_final: false,
      partitions: complete ? [{ scope: { head: 'codex', model: 'gpt-fixture', transport: 'http', work: 'request', local: false, compact: false, streaming: true, outcome: 'ok' }, input_tokens: 1000, output_tokens: 100, cached_tokens: 800, cache_write_tokens: 0, cache_write_1h_tokens: 0, absorbed_rounds: 0, cut_rounds: 0, no_request: 0, count: 138 }] : [] },
    measurement_semantics: {
      population: 'arrivals_in_requested_window_followed_through_as_of', filter_attribution: 'request_scope_resolved_at_terminal_or_latest_checkpoint',
      input_tokens: 'canonical_inclusive_input_across_owned_rounds; includes_cache_read_and_write; not_prompt_length', output_tokens: 'canonical_output_including_reasoning_and_absorbed_output_once',
      cache_write_1h_tokens: 'subset_of_cache_write_tokens; requires_independent_measurement_presence_even_when_billing_defaults_to_zero', rounds: 'absorbed_totals_do_not_identify_individual_round_sizes; cut_rounds_have_missing_usage',
      matching_basis: 'exact_joint_frequency_v1', distribution_id: 'sha256_of_sorted_exact_joint_facts_and_integer_frequencies_reduced_by_gcd; counts_remain_explicit', scope: 'head_model_transport_work_local_compact_streaming_and_actual_terminal_outcome',
      presence: 'requires_usage_observed_input_output_cache_read_cache_write_cache_write_1h; billing_defaults_are_not_measurements', assurance: 'matching_workload_and_knob_revision_do_not_prove_provider_topology_or_causal_equivalence',
    },
    provenance: { builds: ['fixture-build-a'], versions: ['0.4-fixture'], schema_versions: [1], source: s.query.source, objective_id: s.query.objective_id, objective_revision: s.query.objective_revision,
      configuration_status: 'known', configuration_scope: 'effective_knob_policy', query: s.query, as_of_ms: s.as_of_ms, settled_until_ms: s.settled_until_ms },
    environment: { provider_topology: 'unknown', remote_environment: 'unknown', causal_equivalence: 'not_established' },
  };
}
export function summary(url, state = 'partial') {
  const p = new URL(url, 'http://fixture').searchParams, from = +(p.get('from') || now - 18000000), until = +(p.get('until') || now);
  const filters = Object.fromEntries(['head', 'model', 'transport', 'outcome', 'session', 'project', 'account', 'local', 'compact', 'streaming'].filter((k) => p.has(k)).map((k) => [k, p.get(k)]));
  const coverage = { from_ms: from, until_ms: until, observed_ms: 60000, complete: state === 'ok', issues: state === 'partial' ? [{ code: 'request_wall_coverage_unknown', source: 'evidence', count: 1 }] : [] };
  const data = {
    schema_version: 1, contract_version: 1, status: state === 'stale' ? 'partial' : state,
    query: { from_ms: from, until_ms: until, filters, source: p.get('source') || 'auto', objective_id: p.get('objective'), objective_revision: p.get('objective') ? 'objective-fixture-r1' : null },
    as_of_ms: state === 'stale' ? until - 120000 : until, settled_until_ms: until - 60000, requested_duration_ms: until - from, observed_duration_ms: 60000,
    identity_cohorts: [{ boot_id: 'fixture-boot', build: 'fixture-build-a', version: '0.4-fixture', schema_version: 1, boot_config_revision: 'boot-config', effective_config_revision: null, configuration_status: 'unknown' }],
    configuration: { status: 'known', comparison_compatible: true, comparison_scope: 'effective_knob_policy', requires_matching_provider_topology: true, excluded_dimensions: ['credentials', 'headers', 'paths', 'addresses', 'provider_topology', 'remote_environment'], intervals: [{ head: 'codex', boot_id: 'fixture-boot', from_ms: from, until_ms: until, revision: 'effective-config-a' }] },
    coverage, population_coverage: { request_scope_resolved_at_terminal_or_latest_checkpoint: coverage },
    counts: { record_unit: 'request', known_arrivals: 138, known_completions: 125, known_successful_completions: 121, population_complete: false, arrivals: null, completions: null, attribution: 'arrival_and_terminal_windows_separately', filter_attribution: 'request_scope_resolved_at_terminal_or_latest_checkpoint' },
    rates: { arrivals_per_s: .027, completions_per_s: .023, successful_completions_per_s: .021, attempts_per_s: .031, input_tokens_per_s: 1234, output_tokens_per_s: 22, cached_tokens_per_s: 998, upstream_bytes_per_s: 3488, downstream_bytes_per_s: null, upstream_events_per_s: 15.3, downstream_events_per_s: 14.1, denominator: 'requested_wall_duration', filter_attribution: 'request_scope_resolved_at_terminal_or_latest_checkpoint', interpretation: 'known_observations_with_reported_gaps', coverage },
    goodput: p.get('objective') ? { status: 'partial', objective_id: 'interactive', objective_revision: 'objective-fixture-r1', attribution: 'arrivals_in_requested_window; followed_through_as_of', eligible: null, known_evaluable: 123, observed_arrivals: 138, good: 119, bad: 4, pending: 2, unknown: 13, per_s: null, good_fraction: null, known_good_per_s: .019, known_good_rate_is_lower_bound: true } : { status: 'unconfigured' },
    histograms: state === 'empty' ? {} : { arrival_to_terminal_ms: histogram, arrival_to_first_meaningful_content_ms: histogram, arrival_to_first_client_byte_ms: histogram, queue_wait_ms: histogram, attempt_duration_ms: histogram, client_write_flush_ms: histogram },
    counters: { requests_arrived: { count: 138, sum: 138, per_s: .027 }, upstream_bytes: { count: 280, sum: 480000, per_s: 26.667 } },
    gauges: { gate_permits: gauge, gate_queued: { ...gauge, last: 2 }, gate_limit: { ...gauge, last: 8 }, gate_saturated: { ...gauge, last: 0, max: 1, time_weighted_mean: .2 }, runtime_cpu_ratio: { ...gauge, last: .31 }, runtime_heap_used_bytes: { ...gauge, last: 345000000 } },
    outcomes: { ok: 121, 'failure:overloaded_error': 4 }, legacy: { codex: { count: 125, local_steps: 24, total_ms: histogram, metrics_complete: true, read_status: 'readable' } }, missing_fields: ['downstream_bytes'], resolution_ms: 60000, truncated: false,
  };
  data.comparison = comparisonEvidence(data, state === 'ok');
  if (state === 'empty') {
    data.coverage = { ...coverage, complete: true, observed_ms: until - from, issues: [] };
    data.observed_duration_ms = until - from;
    data.counts = { record_unit: 'request', known_arrivals: 0, known_completions: 0, known_successful_completions: 0, population_complete: true, arrivals: 0, completions: 0 };
    data.rates = Object.fromEntries(Object.entries(data.rates).map(([k, v]) => [k, typeof v === 'number' ? 0 : v]));
    Object.assign(data, { goodput: { status: 'unconfigured' }, histograms: {}, counters: {}, gauges: {}, legacy: {}, outcomes: {}, missing_fields: [] });
  }
  if (['error', 'busy', 'unsupported'].includes(state)) {
    for (const key of ['rates','histograms','counters','gauges','legacy','outcomes','goodput','comparison']) delete data[key];
    data.settled_until_ms = null;
    data.error = { code: 'fixture_' + state, reason: 'fixture_' + state };
  }
  return data;
}
export function response(path, state = 'partial') {
  const u = new URL(path, 'http://fixture'), s = summary(path, state), from = s.query.from_ms;
  if (u.pathname === '/api/perf/summary') return s;
  if (u.pathname === '/api/perf/schema') return { schema_version: 1, metrics: { arrival_to_terminal_ms: { description: 'Request arrival to honest terminal', unit: 'ms', population: 'completed requests; attributed at terminal' } } };
  if (u.pathname === '/api/perf/series') return { ...s, series: Array.from({ length: 18 }, (_, i) => ({ from_ms: from + i * 60000, until_ms: from + (i + 1) * 60000, scope: { head: 'codex', model: 'gpt-fixture', transport: 'http' }, metrics: { gate_permits: { ...gauge, last: (i % 7) + 1 }, gate_queued: { ...gauge, last: i % 4 }, gate_limit: { ...gauge, last: 8 } }, coverage: s.coverage })) };
  if (u.pathname === '/api/perf/turns') return { ...s, record_unit: 'legacy_row', rows: [{ ts: from + 15000, head: 'codex', turn_id: u.searchParams.has('cursor') ? 'fixture-turn-2' : 'fixture-turn-1', session_id: 'fixture-session', model: 'gpt-fixture', outcome: 'ok', fields: { total: 14000 } }], total_matching: 2, record_ids: ['codex:row-1'], snapshot_id: { codex: 'snapshot-fixture' }, next_cursor: u.searchParams.has('cursor') ? null : 'opaque-fixture-cursor' };
  if (u.pathname === '/api/perf/runtime') return { schema_version: 1, as_of_ms: now, age_ms: 125, identity: s.identity_cohorts[0], capabilities: [{ id: 'thread_allocations', supported: false, reason: 'unsupported_vm' }], measurements: { runtime_cpu_ratio: .31, runtime_heap_used_bytes: 345000000, runtime_heap_max_bytes: 2147483648, runtime_gc_count: 12, runtime_fd_count: 180, self_queue_depth: 0, self_failures: 0, self_dropped: 0 }, issues: [] };
  if (u.pathname === '/api/perf/diagnose') return { ...s, evidence: [{ metric: 'queue_wait_ms', mean_ms: 1200, classification: 'measured', inference: 'large_observed_duration_is_not_proof_of_root_cause', next_falsifying_measurement: 'compare_same_cohort_stage_distribution_and_runtime_over_same_interval' }], resources: { status: 'unavailable_for_requested_window', conditions: [], reason: 'current_runtime_is_not_historical_evidence' }, timeline: { truncated: false, requests: [{ head: 'codex', request_id: 'request-fixture', turn_id: 'actual-turn-fixture', trace_id: 'trace-fixture', session_id: 'fixture-session', events: ['REQUEST_ARRIVED', 'REQUEST_ADMITTED', 'ATTEMPT_STARTED', 'CONTENT_PROGRESS', 'CLIENT_PROGRESS', 'TOOL_STARTED', 'TOOL_PHASE', 'TOOL_FINISHED', 'ATTEMPT_FINISHED', 'REQUEST_FINISHED'].map((kind, i) => ({ kind, head: 'codex', request_id: 'request-fixture', turn_id: 'actual-turn-fixture', session_id: 'fixture-session', wall_ms: from + i * 1000, monotonic_ms: 10000 + i * 1000, attempt_id: i > 1 ? 'attempt-1' : null, tool_id: i >= 5 && i < 8 ? 'tool-1' : null, trace_id: 'trace-fixture', measurements: kind === 'CLIENT_PROGRESS' ? { client_write_flush_ms: 2, arrival_to_first_client_byte_ms: 4000 } : kind === 'CONTENT_PROGRESS' ? { arrival_to_first_meaningful_content_ms: 3000 } : {} })) }] } };
  if (u.pathname === '/api/perf/profiles/capabilities') return { schema_version: 1, capabilities: [{ id: 'jfr.cpu', supported: true, reason: null }, { id: 'jfr.wall', supported: false, reason: 'jfr_wall_sampling_unavailable' }, { id: 'native.cpu', supported: false, reason: 'native_profiler_not_configured_or_executable' }] };
  if (u.pathname === '/api/perf/objectives') return settings;
  if (u.pathname === '/api/alerts') return { desktop: false, webhook_url: null };
  if (u.pathname.startsWith('/api/perf/profiles')) return { schema_version: 1, status: 'completed', profile_id: 'fixture-profile', from_ms: now - 60000, until_ms: now, size_bytes: 1234, artifact_available: true, limitations: ['sampled_process_not_request_cpu_ownership'], issues: [] };
  if (u.pathname === '/api/perf/alerts/status') return { schema_version: 1, status: 'partial', restart_uncertain: true, events: [{ alert_id: 'a1', dedup_key: 'dedup-fixture', rule_id: 'latency-watch', state: 'insufficient_data', active: true, observed: null, threshold: 5000, sample_count: 4, window: { from_ms: from, until_ms: s.query.until_ms }, cohort: s.query.filters, coverage: { complete: false, reason: 'minimum_coverage' }, delivery_enabled: false, delivery_status: 'disabled' }], expired_events: 0 };
  if (u.pathname === '/api/perf/alerts') return { schema_version: 1, status: 'ok', events: [], expired_events: 0, retention: 'bounded_transition_history' };
  return {};
}
