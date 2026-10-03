// The operator's eight nav places. Old bookmarks lead to the screen that now owns their content.
import { Navigate, createHashRouter, useParams } from 'react-router';
import { Shell } from './Shell';
import { Pending } from './Pending';
import { NAV } from './copy';
import { AccountsPage } from '../pages/accounts/AccountsPage';
import { SessionPage } from '../pages/session/SessionPage';
import { SessionsPage } from '../pages/sessions/SessionsPage';
import { ProjectPage } from '../pages/projects/ProjectPage';
import { TeamPage } from '../pages/teams/TeamPage';
import { UsagePage } from '../pages/usage/UsagePage';
import { TurnPage } from '../pages/turns/TurnPage';
import { TurnsPage } from '../pages/turns/TurnsPage';
import { FleetPage } from '../pages/fleet/FleetPage';
import { FleetHeadPage } from '../pages/fleet/FleetHeadPage';
import { SettingsPage } from '../pages/settings/SettingsPage';

/** Retired addresses keep bookmarks usable without creating more navigation places. */
export const RETIRED: Readonly<Record<string, string>> = {
  'needs-you': '/accounts',
  fleet: '/accounts',
  turns: '/requests',
  logs: '/requests',
  projects: '/sessions?group=repo',
  compaction: '/settings/conversation',
  mcp: '/settings/tools',
  doctor: '/settings/health',
  kept: '/settings/storage',
  burn: '/usage',
  auth: '/accounts',
  config: '/settings',
};

export const PLACES = NAV.map(([path]) => path);
export const HOME = '/accounts';

function RequestBookmark() {
  const { head, ts } = useParams();
  return <Navigate to={`/requests/${encodeURIComponent(head ?? '')}/${encodeURIComponent(ts ?? '')}`} replace />;
}

export const router = createHashRouter([
  {
    path: '/',
    element: <Shell />,
    children: [
      { index: true, element: <Navigate to={HOME} replace /> },
      { path: 'accounts', element: <AccountsPage /> },
      { path: 'settings/:section?', element: <SettingsPage /> },
      { path: 'sessions', element: <SessionsPage /> },
      { path: 'sessions/:id', element: <SessionPage /> },
      { path: 'teams/:id', element: <TeamPage /> },
      { path: 'projects/:id', element: <ProjectPage /> },
      { path: 'usage', element: <UsagePage /> },
      { path: 'requests', element: <TurnsPage /> },
      { path: 'models', element: <FleetPage /> },
      { path: 'models/:head', element: <FleetHeadPage /> },
      { path: 'requests/:head/:ts', element: <TurnPage /> },
      { path: 'turns/:head/:ts', element: <RequestBookmark /> },
      { path: 'fleet/:head', element: <Navigate to="/accounts" replace /> },
      ...NAV.filter(([path]) => path !== 'accounts' && path !== 'sessions' && path !== 'settings' && path !== 'usage' && path !== 'requests' && path !== 'models')
        .map(([path, label]) => ({ path, element: <Pending place={label} /> })),
      ...Object.entries(RETIRED).map(([path, to]) => ({ path, element: <Navigate to={to} replace /> })),
      { path: '*', element: <Navigate to={HOME} replace /> },
    ],
  },
]);
