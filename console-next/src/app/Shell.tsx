import { NavLink, Outlet, useLocation } from 'react-router';
import { useQuotaOnOpen, useStatus } from '../api/queries';
import { failureText } from '../api/client';
import { Q } from '../lib/words-quota';
import { setTheme, useTheme } from '../lib/theme';
import { C, NAV } from './copy';
import { StaleBanner, useStalePage } from './StalePage';

/** A bookmarked project still opens inside Sessions, rather than adding another nav place. */
const INSIDE_SESSIONS = /^\/projects\//;

export function Shell() {
  const { pathname } = useLocation();
  const status = useStatus();
  const quota = useQuotaOnOpen(pathname);
  const theme = useTheme();
  const checking = status.isPending || (status.isFetching && status.data === undefined);
  const answering = status.isSuccess;
  const stale = useStalePage();
  return (
    <div className="app">
      <aside className="side">
        <div className="mark">{C.brand}</div>
        <nav className="nav" aria-label={C.pages}>
          {NAV.map(([path, label]) => (
            <NavLink key={path} to={`/${path}`} {...(path === 'sessions' && INSIDE_SESSIONS.test(pathname) ? { className: 'here' } : {})}>
              {label}
            </NavLink>
          ))}
        </nav>
        <div className="foot">
          <span className="live">
            <i className="dot" data-off={answering ? undefined : ''} style={checking ? { background: 'var(--wait)' } : undefined} />
            <b>{checking ? C.checking : answering ? C.running : C.notAnswering}</b>
          </span>
          <span>
            {status.data?.version ?? ''} · {C.thisComputer}
          </span>
          <div className="seg" role="group" aria-label={C.theme}>
            {(['day', 'night'] as const).map((choice) => (
              <button key={choice} type="button" aria-pressed={theme === choice} onClick={() => setTheme(choice)}>
                {choice === 'day' ? C.day : C.night}
              </button>
            ))}
          </div>
        </div>
      </aside>
      <main className="wall">
        <div className="wrap">
          {stale ? <StaleBanner /> : null}
          {quota.isFetching ? <p className="hint" role="status">{Q.checking}</p> : quota.isError ? <p className="hint" role="alert">{Q.failed(failureText(quota.error))}</p> : null}
          <Outlet />
        </div>
      </main>
    </div>
  );
}
