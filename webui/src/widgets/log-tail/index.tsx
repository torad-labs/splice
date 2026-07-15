// Per-head log tail: GET /api/logs/:head?tail=N. The head selector reads the
// live registry (control-status) so labels never drift from HEAD_REGISTRY; a
// static fallback covers the brief window before that first fetch resolves.
import { currentLogHead, currentLogTail, setLogHead, setLogTail, useLogs } from '@entities/logs';
import { useControlStatus } from '@entities/control-status';
import { EmptyState, ErrorNote, Field, Panel, SkeletonRows, Stale, Well } from '@shared/ui';

const TAIL_SIZES = [50, 200, 500, 1000];
const FALLBACK_HEADS = [
  { key: 'codex', label: 'claudex' },
  { key: 'claudithos', label: 'claudithos' },
];

export function LogTail() {
  const { data, error, loading, lastUpdated } = useLogs((s) => s);
  const registry = useControlStatus((s) => s.data?.registry);
  const heads = registry && registry.length ? registry : FALLBACK_HEADS;

  return (
    <Panel
      title="log tail"
      actions={(
        <>
          <Field label="head" htmlFor="log-head">
            <select id="log-head" value={currentLogHead()} onChange={(e) => setLogHead(e.target.value)}>
              {heads.map((h) => <option key={h.key} value={h.key}>{h.label}</option>)}
            </select>
          </Field>
          <Field label="tail" htmlFor="log-tail-size">
            <select
              id="log-tail-size"
              value={currentLogTail()}
              onChange={(e) => setLogTail(parseInt(e.target.value, 10))}
            >
              {TAIL_SIZES.map((n) => <option key={n} value={n}>{n}</option>)}
            </select>
          </Field>
          <Stale lastUpdated={lastUpdated} />
        </>
      )}
    >
      {error ? <ErrorNote message={error} /> : null}
      {loading && !data ? <SkeletonRows rows={6} cols={1} /> : null}
      {data && data.lines.length > 0 ? (
        <Well>
          <pre className="myx-log">{data.lines.join('\n')}</pre>
        </Well>
      ) : null}
      {data && data.lines.length === 0 ? (
        <EmptyState label={data.note ? `no log lines (${data.note})` : 'no log lines'} />
      ) : null}
      {data ? <p className="myx-footnote">{data.path}</p> : null}
    </Panel>
  );
}
