// In-flight requests from the gate snapshot: phase distinguishes wedged
// connects from silent streams; idle age is the watchdog's own signal.
import { useStatus } from '@entities/proxy-status';
import { fmtMs } from '@shared/lib';
import { EmptyState, ErrorNote, Panel, SkeletonRows, Stale, StatusPill } from '@shared/ui';

export function LiveRequestTable() {
  const { data, error, loading, lastUpdated } = useStatus((s) => s);
  const gate = data?.gate;

  return (
    <Panel title="in flight" tone="signal" actions={<Stale lastUpdated={lastUpdated} />}>
      {error ? <ErrorNote message={error} /> : null}
      {loading && !data ? <SkeletonRows rows={3} cols={5} /> : null}
      {gate && gate.live.length === 0 ? <EmptyState label="no requests in flight" /> : null}
      {gate && gate.live.length > 0 ? (
        <table className="myx-table">
          <thead>
            <tr><th>label</th><th>phase</th><th>kind</th><th>age</th><th>idle</th></tr>
          </thead>
          <tbody>
            {gate.live.map((r, i) => (
              <tr key={`${r.label}-${i}`}>
                <td>{r.label}</td>
                <td>
                  <StatusPill tone={r.phase === 'streaming' ? 'info' : 'amber'}>{r.phase}</StatusPill>
                </td>
                <td>{r.compact ? <StatusPill tone="amber">compact</StatusPill> : <span className="myx-ink-mute">turn</span>}</td>
                <td className="myx-num">{fmtMs(r.age_ms)}</td>
                <td className="myx-num">{fmtMs(r.idle_ms)}</td>
              </tr>
            ))}
          </tbody>
        </table>
      ) : null}
      {gate ? (
        <p className="myx-footnote">
          inflight {gate.inflight} / {gate.max === 'unlimited' ? 'unlimited' : gate.max}; queued {gate.queued};
          served {gate.released}; idle watchdog {fmtMs(gate.stream_idle_ms)}
        </p>
      ) : null}
    </Panel>
  );
}
