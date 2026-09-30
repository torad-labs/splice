import { NavLink, Outlet } from 'react-router';
import { useStatus } from '../api/queries';
import { setTheme, useTheme } from '../lib/theme';
import { C } from './copy';

const NAV = [
  ['needs-you', C.needs],
  ['sessions', C.sessions],
  ['fleet', C.fleet],
  ['turns', C.turns],
  ['usage', C.usage],
  ['settings', C.settings],
] as const;

export function Shell() {
  const status = useStatus();
  const theme = useTheme();
  const answering = status.isSuccess || status.isPending;
  return (
    <div className="app">
      <aside className="side">
        <div className="mark">{C.brand}</div>
        <nav className="nav" aria-label={C.pages}>
          {NAV.map(([path, label]) => (
            <NavLink key={path} to={`/${path}`}>
              {label}
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
          <Outlet />
        </div>
      </main>
    </div>
  );
}
