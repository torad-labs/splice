// The compaction instrument: outcome totals + the event tail + the shadow
// classifier (predicted vs observed) — ground truth for marker drift.
import { useCompact } from '@entities/compact-stats';
import { fmtInt, fmtMs, timeAgo } from '@shared/lib';
import { EmptyState, ErrorNote, Panel, SkeletonRows, Stale, StatusPill } from '@shared/ui';
import type { PillTone } from '@shared/ui';

function outcomeTone(outcome: string): PillTone {
  if (outcome.startsWith('model')) return 'pos';
  if (outcome === 'empty_model' || outcome === 'stream_error' || outcome === 'upstream_error') return 'neg';
  return 'amber';
}

export function CompactFeed() {
  const { data, error, loading, lastUpdated } = useCompact((s) => s);

  return (
    <div className="myx-stack">
      <Panel title="compact outcomes" actions={<Stale lastUpdated={lastUpdated} />}>
        {error ? <ErrorNote message={error} /> : null}
        {loading && !data ? <SkeletonRows rows={2} cols={4} /> : null}
        {data ? (
          <div className="myx-pill-row">
            <StatusPill tone="mute">total {fmtInt(data.stats.total)}</StatusPill>
            {Object.entries(data.stats.by_outcome).map(([outcome, count]) => (
              <StatusPill key={outcome} tone={outcomeTone(outcome)}>{outcome}: {fmtInt(count)}</StatusPill>
            ))}
          </div>
        ) : null}
        {data && data.stats.tail.length > 0 ? (
          <table className="myx-table">
            <thead>
              <tr><th>when</th><th>outcome</th><th>chars</th><th>took</th></tr>
            </thead>
            <tbody>
              {[...data.stats.tail].reverse().map((row, i) => (
                <tr key={`${row.ts}-${i}`}>
                  <td>{timeAgo(row.ts)}</td>
                  <td><StatusPill tone={outcomeTone(row.outcome ?? 'unknown')}>{row.outcome ?? 'unknown'}</StatusPill></td>
                  <td className="myx-num">{row.chars != null ? fmtInt(row.chars) : ''}</td>
                  <td className="myx-num">{row.ms != null ? fmtMs(row.ms) : ''}</td>
                </tr>
              ))}
            </tbody>
          </table>
        ) : null}
        {data && data.stats.tail.length === 0 ? <EmptyState label="no compaction events recorded yet" /> : null}
      </Panel>

      <Panel title="shadow classifier (every request)" tone="signal">
        {data && data.shadow.length > 0 ? (
          <table className="myx-table">
            <thead>
              <tr><th>when</th><th>verdict</th><th>marker</th><th>tools</th><th>sys chars</th><th>model</th></tr>
            </thead>
            <tbody>
              {[...data.shadow].reverse().slice(0, 40).map((row, i) => (
                <tr key={`${row.ts}-${i}`}>
                  <td>{timeAgo(row.ts)}</td>
                  <td>{row.compact ? <StatusPill tone="amber">compact</StatusPill> : <span className="myx-ink-mute">turn</span>}</td>
                  <td>{row.has_marker
                    ? <StatusPill tone={row.compact ? 'pos' : 'neg'}>present</StatusPill>
                    : <span className="myx-ink-mute">absent</span>}</td>
                  <td className="myx-num">{row.tool_count}</td>
                  <td className="myx-num">{fmtInt(row.sys_len)}</td>
                  <td className="myx-trunc">{row.model}</td>
                </tr>
              ))}
            </tbody>
          </table>
        ) : (
          <EmptyState label="no requests observed since proxy start" />
        )}
        <p className="myx-footnote">
          marker present with verdict turn, or absent with verdict compact, means classifier drift: check the pinned canary.
        </p>
      </Panel>
    </div>
  );
}
