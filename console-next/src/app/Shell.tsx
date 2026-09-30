import { NavLink, Outlet, useLocation } from 'react-router';
import { useNeeds } from '../api/needs';
import { useStatus } from '../api/queries';
import { setTheme, useTheme } from '../lib/theme';
import { C } from './copy';
import { StaleBanner, useStalePage } from './StalePage';

const NAV = [
  ['needs-you', C.needs],
  ['sessions', C.sessions],
  ['fleet', C.fleet],
  ['turns', C.turns],
  ['usage', C.usage],
  ['settings', C.settings],
] as const;

/** A team or a project is a place inside Sessions: its nav item stays lit there. */
const INSIDE_SESSIONS = /^\/(teams|projects)\//;

export function Shell() {
  const { pathname } = useLocation();
  const status = useStatus();
  const theme = useTheme();
  const answering = status.isSuccess || status.isPending;
  const waiting = useNeeds(Date.now()).needs.length;
  const stale = useStalePage();
  return (
    <div className="app">
      <aside className="side">
        <div className="mark">{C.brand}</div>
        <nav className="nav" aria-label={C.pages}>
          {NAV.map(([path, label]) => (
            <NavLink key={path} to={`/${path}`} {...(path === 'sessions' && INSIDE_SESSIONS.test(pathname) ? { className: 'here' } : {})}>
              {label}
              {path === 'needs-you' && waiting > 0 ? <span className="count" aria-hidden="true">{waiting}</span> : null}
            </NavLink>
          ))}
        </nav>
        <div className="foot">
          <span className="live">
            <i className="dot" data-off={answering ? undefined : ''} />
            <b>{answering ? C.running : C.notAnswering}</b>
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
          <Outlet />
        </div>
      </main>
    </div>
  );
}
