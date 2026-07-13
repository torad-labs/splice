import { useEffect } from 'react';
import { startAuthPolling, useAuth } from '@entities/auth';
import { RefreshAuth } from '@features/refresh-auth';
import { timeAgo } from '@shared/lib';
import { ErrorNote, Metric, Panel, SkeletonRows, StatusPill } from '@shared/ui';

export function AuthPage() {
  useEffect(() => startAuthPolling(15000), []);
  const { data, error, loading } = useAuth((s) => s);

  return (
    <Panel title="upstream auth" actions={<RefreshAuth />}>
      {error ? <ErrorNote message={error} /> : null}
      {loading && !data ? <SkeletonRows rows={2} cols={3} /> : null}
      {data ? (
        <div className="myx-stack">
          <div className="myx-pill-row">
            <StatusPill tone={data.present ? 'pos' : 'neg'}>{data.present ? 'token present' : 'no token'}</StatusPill>
            {data.cached ? <StatusPill tone="info">cached</StatusPill> : null}
          </div>
          <div className="myx-metric-row">
            {data.account_id_masked ? <Metric label="account" value={data.account_id_masked} /> : null}
            {data.last_refresh ? <Metric label="last refresh" value={data.last_refresh} /> : null}
            {data.expires_at ? <Metric label="expires" value={timeAgo(data.expires_at)} /> : null}
          </div>
          <p className="myx-footnote">{data.auth_path ?? data.cred_path}</p>
        </div>
      ) : null}
    </Panel>
  );
}
