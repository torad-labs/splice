// Per-head credential cards from GET /api/auth. codex is automated OAuth
// (sign-in + refresh from here); claude auth is maintained by plain `claude`,
// this card only observes it.
import { useEffect } from 'react';
import { startAuthPolling, useAuth } from '@entities/auth';
import { LoginCodex } from '@features/login-codex';
import { RefreshAuth } from '@features/refresh-auth';
import { timeAgo } from '@shared/lib';
import { ErrorNote, Metric, Panel, SkeletonRows, StatusPill } from '@shared/ui';

export function AuthPage() {
  useEffect(() => startAuthPolling(15000), []);
  const { data, error, loading } = useAuth((s) => s);

  return (
    <div className="myx-stack">
      {error ? <ErrorNote message={error} /> : null}
      {loading && !data ? <SkeletonRows rows={4} cols={3} /> : null}
      {data ? (
        <>
          <Panel
            title="claudex (codex auth)"
            actions={<><LoginCodex /><RefreshAuth head="codex" /></>}
          >
            <div className="myx-pill-row">
              <StatusPill tone={data.codex.present ? 'pos' : 'neg'}>
                {data.codex.present ? 'token present' : 'no token'}
              </StatusPill>
              {data.codex.cached ? <StatusPill tone="info">cached</StatusPill> : null}
            </div>
            <div className="myx-metric-row">
              {data.codex.account_id_masked ? <Metric label="account" value={data.codex.account_id_masked} /> : null}
              {data.codex.last_refresh ? <Metric label="last refresh" value={data.codex.last_refresh} /> : null}
            </div>
            {data.codex.auth_path ? <p className="myx-footnote">{data.codex.auth_path}</p> : null}
          </Panel>

          <Panel title="claudithos (claude auth)" actions={<RefreshAuth head="claude" />}>
            <div className="myx-pill-row">
              <StatusPill tone={data.claude.present ? 'pos' : 'neg'}>
                {data.claude.present ? 'token present' : 'no token'}
              </StatusPill>
            </div>
            <div className="myx-metric-row">
              {data.claude.expires_at ? <Metric label="expires" value={timeAgo(data.claude.expires_at)} /> : null}
            </div>
            <p className="myx-footnote">
              maintained by plain claude{data.claude.cred_path ? `, ${data.claude.cred_path}` : ''}
            </p>
          </Panel>
        </>
      ) : null}
    </div>
  );
}
