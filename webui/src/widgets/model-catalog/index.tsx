import { useModels } from '@entities/models';
import { fmtTokens } from '@shared/lib';
import { EmptyState, ErrorNote, Panel, SkeletonRows, StatusPill } from '@shared/ui';

export function ModelCatalog() {
  const { data, error, loading } = useModels((s) => s);

  return (
    <Panel title="model catalog">
      {error ? <ErrorNote message={error} /> : null}
      {loading && !data ? <SkeletonRows rows={5} cols={4} /> : null}
      {data ? (
        <table className="myx-table">
          <thead>
            <tr><th>id</th><th>label</th><th>window</th><th>exposure</th></tr>
          </thead>
          <tbody>
            {data.catalog.map((m) => (
              <tr key={m.value}>
                <td>{m.value}</td>
                <td className="myx-ink-mute">{m.label}</td>
                <td className="myx-num">{fmtTokens(data.context_windows[m.value] ?? m.context_window)}</td>
                <td>
                  {m.value === data.pinned
                    ? <StatusPill tone="info">pinned default</StatusPill>
                    : <span className="myx-ink-mute">discovery</span>}
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      ) : null}
      {data ? (
        <p className="myx-footnote">
          discovery serves {data.discovery.length} wrapped ids at /v1/models; the pinned default rides
          ANTHROPIC_CUSTOM_MODEL_OPTION instead
        </p>
      ) : null}
      {!data && !loading && !error ? <EmptyState label="catalog not loaded" /> : null}
    </Panel>
  );
}
