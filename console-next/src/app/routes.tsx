// The console's addresses. The six nav places are canonical; the retired addresses of the console this
// one replaces redirect to where their content now lives, so an old link or bookmark still lands.
import { Navigate, createHashRouter } from 'react-router';
import { Shell } from './Shell';
import { Pending } from './Pending';
import { FleetHeadPage } from '../pages/fleet/FleetHeadPage';
import { FleetPage } from '../pages/fleet/FleetPage';
import { NeedsPage } from '../pages/needs/NeedsPage';
import { SessionPage } from '../pages/session/SessionPage';
import { SessionsPage } from '../pages/sessions/SessionsPage';
import { TurnPage } from '../pages/turns/TurnPage';
import { TurnsPage } from '../pages/turns/TurnsPage';
import { SettingsPage } from '../pages/settings/SettingsPage';

/** Retired address -> where it went (DIRECTION.md, "Nav"). */
export const RETIRED: Readonly<Record<string, string>> = {
  accounts: '/fleet',
  models: '/fleet',
  logs: '/fleet',
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
      { path: 'needs-you', element: <NeedsPage /> },
      { path: 'settings/:section?', element: <SettingsPage /> },
      { path: 'fleet', element: <FleetPage /> },
      { path: 'fleet/:head', element: <FleetHeadPage /> },
      { path: 'sessions', element: <SessionsPage /> },
      { path: 'sessions/:id', element: <SessionPage /> },
      { path: 'turns', element: <TurnsPage /> },
      { path: 'turns/:head/:ts', element: <TurnPage /> },
      ...PLACES.filter((path) => path !== 'sessions' && path !== 'fleet' && path !== 'needs-you' && path !== 'settings' && path !== 'turns').map((path) => ({ path, element: <Pending place={path} /> })),
      ...Object.entries(RETIRED).map(([path, to]) => ({ path, element: <Navigate to={to} replace /> })),
      { path: '*', element: <Navigate to={HOME} replace /> },
    ],
  },
]);
