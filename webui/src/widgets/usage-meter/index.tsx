// 5-hour output-token window + the persisted OpenAI ratelimit state.
import { useUsage } from '@entities/usage';
import { fmtInt, fmtTokens, timeAgo } from '@shared/lib';
import { EmptyState, ErrorNote, Metric, Panel, SkeletonRows, Stale } from '@shared/ui';

export function UsageMeter() {
  const { data, error, loading, lastUpdated } = useUsage((s) => s);

  const rl = data?.ratelimit ?? null;
  const pct = rl && rl.remaining_tokens != null && rl.limit_tokens > 0
    ? Math.max(0, Math.min(100, (rl.remaining_tokens / rl.limit_tokens) * 100))
    : null;

  return (
    <Panel title="usage" actions={<Stale lastUpdated={lastUpdated} />}>
      {error ? <ErrorNote message={error} /> : null}
      {loading && !data ? <SkeletonRows rows={2} cols={3} /> : null}
      {data ? (
        <div className="myx-metric-row">
          <Metric label={`output tokens / ${data.window_hours}h`} value={fmtTokens(data.output_tokens_5h)} tone="info" />
          <Metric label="turns recorded" value={fmtInt(data.entries)} />
          {rl ? (
            <Metric
              label={`ratelimit remaining${rl.reset_tokens ? `, resets ${rl.reset_tokens}` : ''}`}
              value={rl.remaining_tokens != null ? fmtTokens(rl.remaining_tokens) : 'unknown'}
              unit={`of ${fmtTokens(rl.limit_tokens)}`}
              tone={pct != null && pct < 15 ? 'neg' : 'pos'}
            />
          ) : (
            <Metric label="ratelimit" value="not yet observed" tone="amber" />
          )}
        </div>
      ) : null}
      {pct != null ? (
        <div className="myx-bar" role="img" aria-label={`ratelimit ${Math.round(pct)} percent remaining`}>
          <div className="myx-bar-fill" style={{ width: `${pct}%` }} />
        </div>
      ) : null}
      {data && rl ? <p className="myx-footnote">ratelimit observed {timeAgo(rl.updated_at)}</p> : null}
      {data && !rl && !error ? <EmptyState label="no ratelimit headers captured yet" /> : null}
    </Panel>
  );
}
