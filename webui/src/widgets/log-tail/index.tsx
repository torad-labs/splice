import { currentLogTail, setLogTail, useLogs } from '@entities/logs';
import { EmptyState, ErrorNote, Field, Panel, SkeletonRows, Stale, Well } from '@shared/ui';

const TAIL_SIZES = [50, 200, 500, 1000];

export function LogTail({ filter }: { filter?: RegExp }) {
  const { data, error, loading, lastUpdated } = useLogs((s) => s);
  const lines = data ? (filter ? data.lines.filter((l) => filter.test(l)) : data.lines) : [];

  return (
    <Panel
      title="log tail"
      actions={(
        <>
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
      {data && lines.length > 0 ? (
        <Well>
          <pre className="myx-log">{lines.join('\n')}</pre>
        </Well>
      ) : null}
      {data && lines.length === 0 ? (
        <EmptyState label={data.note ? `no log lines (${data.note})` : 'no matching log lines'} />
      ) : null}
      {data ? <p className="myx-footnote">{data.path}</p> : null}
    </Panel>
  );
}
