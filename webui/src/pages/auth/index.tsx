// Per-head credential cards from GET /api/auth. Browser/device login must run
// in the local terminal (`<head> login`); the dashboard only performs refreshes.
import { useEffect } from 'react';
import { startAuthPolling, useAuth } from '@entities/auth';
import { RefreshAuth } from '@features/refresh-auth';
import { EmptyState, ErrorNote, Metric, Panel, SkeletonRows, StatusPill } from '@shared/ui';

export function AuthPage() {
  useEffect(() => startAuthPolling(15000), []);
  const { data, error, loading } = useAuth((s) => s);

  return (
    <div className="myx-stack">
      {error ? <ErrorNote message={error} /> : null}
      {loading && !data ? <SkeletonRows rows={4} cols={3} /> : null}
      {data && Object.keys(data).length === 0 ? <EmptyState label="no configured heads" /> : null}
      {data ? Object.entries(data).map(([head, auth]) => (
        <Panel
          key={head}
          title={`${head} (${auth.kind})`}
          actions={auth.present && auth.kind.includes('oauth') ? <RefreshAuth head={head} /> : null}
        >
          <div className="myx-pill-row">
            <StatusPill tone={auth.present ? 'pos' : 'neg'}>
              {auth.present ? 'credentials present' : 'no credentials'}
            </StatusPill>
            {auth.refresh_latched ? <StatusPill tone="neg">refresh blocked</StatusPill> : null}
          </div>
          <div className="myx-metric-row">
            {auth.account_id_masked ? <Metric label="account" value={auth.account_id_masked} /> : null}
            {auth.last_refresh ? <Metric label="last refresh" value={auth.last_refresh} /> : null}
          </div>
          {auth.auth_path ? <p className="myx-footnote">{auth.auth_path}</p> : null}
          {!auth.present && auth.kind.includes('oauth')
            ? <p className="myx-footnote">Sign in from a terminal: <code>{head} login</code></p>
            : null}
        </Panel>
      )) : null}
    </div>
  );
}
