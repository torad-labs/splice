// The native Usage page still reads legacy usage rows; its integrated Results panel reads contract=1.
// Keep both shapes in the browser fixtures so the page/Results bridge is exercised, not only a standalone component.
export function performanceSummary(url: URL, legacy: Record<string, unknown>) {
  const p = url.searchParams, from = Number(p.get("from")), until = Number(p.get("until"));
  const filters = Object.fromEntries(["head", "model", "transport", "outcome", "session", "project", "account", "local", "compact", "streaming"].filter((k) => p.has(k)).map((k) => [k, p.get(k)]));
  return {
    schema_version: 1, contract_version: 1, status: "ok", as_of_ms: until, settled_until_ms: null,
    query: { from_ms: from, until_ms: until, filters, source: p.get("source") || "auto", objective_id: null, objective_revision: null },
    requested_duration_ms: until - from, observed_duration_ms: until - from,
    coverage: { from_ms: from, until_ms: until, observed_ms: until - from, complete: true, issues: [] },
    counts: { record_unit: "legacy_row" }, rates: { denominator: "requested_wall_duration", filter_attribution: "legacy_row", interpretation: "recorded_rows" },
    goodput: { status: "unconfigured" }, histograms: {}, counters: {}, gauges: {}, outcomes: {}, population_coverage: {}, legacy,
    identity_cohorts: [], missing_fields: [], truncated: false,
  };
}

export function performanceRows(url: URL) {
  return { ...performanceSummary(url, {}), record_unit: "legacy_row", rows: [], total_matching: 0, next_cursor: null };
}
