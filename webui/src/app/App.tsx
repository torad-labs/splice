// The console shell: a fixed rule on top, a rail of bays on the left, the
// page's bay in the middle. The rule never scrolls and the rail is always
// visible; everything else is the page's own composition.
//
// Routing is hash-based because the artifact is one file served at / and
// /dashboard by the control server (CONTRACTS.md section 3). Addresses are
// canonical (`#/fleet`); the tab router's bare addresses are rewritten once at
// boot in index.tsx, and a bare path typed mid-session still lands on its
// address through the redirect routes below.
import { Suspense } from 'react';
import { Navigate, Outlet, RouterProvider, createHashRouter, useLocation } from 'react-router';
import { useTheme } from '@features/theme';
import { Palette } from '@features/palette';
import { selectView, usePageViews } from '@features/views';
import { UnlockMgmt } from '@features/unlock-mgmt';
import { Rail } from '@widgets/rail';
import { Rule } from '@widgets/rule';
import { Empty } from '@shared/ui';
import { ADDRESSES, LEGACY_PATHS, PAGE_ROW, addressOf, type Address } from './rows';
import { pageFor } from './pages';
import './app.css';

/** The page an address shows: its own module, or the honest empty naming its row. */
function PageView({ address }: { address: Address }) {
  const Page = pageFor(address);
  if (Page === null) return <Empty text="page not built" source={`row ${PAGE_ROW[address]}`} />;
  // The page module is fetched lazily, so the bay holds its own space for the
  // frame before it arrives rather than claiming anything about it.
  return (
    <Suspense fallback={<div className="myx-console-pending" aria-busy="true" />}>
      <Page />
    </Suspense>
  );
}

/** The shell every address renders inside. */
function Console() {
  const { pathname } = useLocation();
  const address = addressOf(pathname);
  const { theme, set } = useTheme();
  // The palette lists the current page's saved views, and the views feature
  // cannot import the palette (or the reverse). The app layer is where the two
  // meet: it hands the list down and takes the selection back.
  const views = usePageViews(address);

  return (
    <div className="myx-console">
      <Rule />
      <div className="myx-console-body">
        <Rail active={address} addresses={ADDRESSES} />
        <main className="myx-console-page">
          <Outlet />
        </main>
      </div>
      <Palette
        addresses={ADDRESSES}
        views={views}
        onSelectView={(id) => selectView(address, id)}
        theme={theme}
        onTheme={set}
      />
      <UnlockMgmt />
    </div>
  );
}

const HOME = `/${ADDRESSES[0]}`;

export const router = createHashRouter([
  {
    path: '/',
    element: <Console />,
    children: [
      { index: true, element: <Navigate to={HOME} replace /> },
      ...ADDRESSES.map((address) => ({ path: address, element: <PageView address={address} /> })),
      // A bare old path typed while the console is open. On load these are
      // rewritten before the router exists (rows.canonicalHash).
      ...LEGACY_PATHS.map(([slug, address]) => ({
        path: slug,
        element: <Navigate to={`/${address}`} replace />,
      })),
      { path: '*', element: <Navigate to={HOME} replace /> },
    ],
  },
]);

export function App() {
  return <RouterProvider router={router} />;
}
