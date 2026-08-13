// Runtime knobs, hot-applied through the layered config. The layers table
// shows WHY a value is what it is (defaults, file, env, runtime PATCH).
import { useEffect, useState } from 'react';
import { fetchConfig, fetchTopologyStale, headOptions, useConfig } from '@entities/config';
import { EditConfig } from '@features/edit-config';
import { ErrorNote, Panel, SkeletonRows } from '@shared/ui';

export function ConfigPage() {
  // JW-04: topology loads once at boot by design — this banner is the visibility half.
  const [topologyStale, setTopologyStale] = useState(false);
  // JW-06: 'global' or one override-carrying head; refetch folds that head's layer in.
  const [head, setHead] = useState('global');
  useEffect(() => {
    void fetchConfig(head === 'global' ? undefined : head);
    void fetchTopologyStale().then(setTopologyStale);
  }, [head]);
  const { data, error, loading } = useConfig((s) => s);
  const heads = headOptions(data?.layers.perHead);

  return (
    <div className="myx-stack">
      {topologyStale ? (
        <ErrorNote message="splice.toml changed since the daemon booted; the running topology is stale. Run: splice restart" />
      ) : null}
      <Panel title="runtime config (hot unless marked restart)">
        {heads.length > 1 ? (
          <p className="myx-footnote">
            view as head:{' '}
            <select value={head} onChange={(e) => setHead(e.target.value)}>
              {heads.map((h) => (
                <option key={h} value={h}>{h}</option>
              ))}
            </select>
            {head !== 'global' ? ' (effective folds this head\'s overrides)' : null}
          </p>
        ) : null}
        {error ? <ErrorNote message={error} /> : null}
        {loading && !data ? <SkeletonRows rows={8} cols={2} /> : null}
        {data ? <EditConfig head={head === 'global' ? undefined : head} /> : null}
        {data ? <p className="myx-footnote">effective config observed from: {data.source}</p> : null}
      </Panel>
      {data ? (
        <Panel title="layers (defaults, TOML, file, env, runtime patch)">
          <table className="myx-table">
            <thead>
              <tr>
                <th>key</th><th>default</th><th>TOML</th><th>file</th>
                <th>env</th><th>runtime</th><th>effective</th>
              </tr>
            </thead>
            <tbody>
              {Object.keys(data.effective).map((key) => (
                <tr key={key}>
                  <td>{key}</td>
                  <td className="myx-ink-mute">{String(data.layers.defaults[key] ?? '')}</td>
                  <td className="myx-ink-mute">{key in data.layers.toml ? String(data.layers.toml[key]) : ''}</td>
                  <td className="myx-ink-mute">{key in data.layers.file ? String(data.layers.file[key]) : ''}</td>
                  <td className="myx-ink-mute">{key in data.layers.env ? String(data.layers.env[key]) : ''}</td>
                  <td className="myx-ink-amber">{key in data.layers.runtime ? String(data.layers.runtime[key]) : ''}</td>
                  <td className="myx-ink-strong">{String(data.effective[key])}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </Panel>
      ) : null}
    </div>
  );
}
