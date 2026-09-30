// The console's addresses. The six nav places are canonical; the retired addresses of the console this
// one replaces redirect to where their content now lives, so an old link or bookmark still lands.
import { Navigate, createHashRouter } from 'react-router';
import { Shell } from './Shell';
import { Pending } from './Pending';
import { SessionPage } from '../pages/session/SessionPage';
import { SessionsPage } from '../pages/sessions/SessionsPage';

/** Retired address -> where it went (DIRECTION.md, "Nav"). */
export const RETIRED: Readonly<Record<string, string>> = {
  accounts: '/fleet',
  models: '/fleet?open=models',
  logs: '/fleet?open=log',
  teams: '/sessions?group=team',
  projects: '/sessions?group=repo',
  compaction: '/settings/conversation',
  mcp: '/settings/tools',
  doctor: '/settings/health',
  kept: '/settings/storage',
  burn: '/usage',
  auth: '/fleet',
  config: '/settings',
};

export const PLACES = ['needs-you', 'sessions', 'fleet', 'turns', 'usage', 'settings'] as const;
export const HOME = '/needs-you';

export const router = createHashRouter([
  {
    path: '/',
    element: <Shell />,
    children: [
      { index: true, element: <Navigate to={HOME} replace /> },
      { path: 'sessions', element: <SessionsPage /> },
      { path: 'sessions/:id', element: <SessionPage /> },
      ...PLACES.filter((path) => path !== 'sessions').map((path) => ({ path, element: <Pending place={path} /> })),
      ...Object.entries(RETIRED).map(([path, to]) => ({ path, element: <Navigate to={to} replace /> })),
      { path: '*', element: <Navigate to={HOME} replace /> },
    ],
  },
]);
