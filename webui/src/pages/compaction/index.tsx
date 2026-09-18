// Compaction: what the daemon's compactions did, as strips.
//
// This page is a reader. It shows the outcome totals and the event tail from GET /api/compact, with
// the one thing an operator needs to know about the feature printed beside them: compaction runs on
// the session's OWN model and effort, and the console will never offer a knob to change that. The
// reason is not policy for its own sake — a pin moves the reasoning off the session's model and the
// backend's prompt cache then misses the whole transcript on the most expensive turn class there is
// (the retired `compact_effort` quirk, refused loudly at load, carries the same story).
import { useEffect, useState } from 'react';
import { useLocation } from 'react-router';
import type { CompactPayload } from '@shared/api';
import { startCompactPolling, useCompact } from '@entities/compact-stats';
import { ErrorNote, SkeletonRows } from '@shared/ui';
import { CompactFeed } from '@widgets/compact-feed';
import { dispositions } from './coverage';
import { S } from './strings';
import './compaction.css';

export { dispositions };

/** The name this page accepts in the hash query. Declared HERE, not in the fixture module: a
 *  static import of that module — even for one constant — is a real dependency edge, so the
 *  bundler would include the fixture and its strings would ship (CONTRACTS.md section 4). */
const FIXTURE = 'compaction';

/** The law, in words. A sentence, so it lives here and not in the string table. */
export const LAW_TEXT =
  'compaction runs on the session own model and effort by law: pinning another model would move the '
  + 'reasoning off the session and miss the backend prompt cache on the whole transcript, which is '
  + 'the most expensive turn class there is. This page reads outcomes; it never offers a model.';

/**
 * The board takes its payload as a prop rather than reading the store (CONTRACTS.md section 4): a
 * static render only ever sees a zustand store's initial state, so a board that read the store
 * could not be rendered from data by a test or a capture.
 */
export function CompactionBoard({ payload, sample = false }: {
  payload: CompactPayload | null;
  sample?: boolean;
}) {
  return (
    <div className="myx-compaction">
      <header className="myx-compaction-head">
        <h1 className="myx-compaction-title">{S.title}</h1>
      </header>
      <p className="myx-compaction-law">{LAW_TEXT}</p>
      {payload === null ? <SkeletonRows rows={4} cols={4} /> : <CompactFeed payload={payload} sample={sample} />}
    </div>
  );
}

export default function CompactionPage() {
  const { search } = useLocation();
  const compact = useCompact((state) => state);
  useEffect(() => startCompactPolling(5000), []);

  const [fixture, setFixture] = useState<CompactPayload | null>(null);

  // The fixture loads through a DYNAMIC import inside the DEV branch, so the module is a build-time
  // nothing: `import.meta.env.DEV` is statically false in a production build, the branch is dropped,
  // and the fixture is not a dependency of anything that ships. The board renders the store's
  // payload while the module loads, and the fixture replaces it when it arrives.
  useEffect(() => {
    if (!import.meta.env.DEV) return;
    if (new URLSearchParams(search).get('fixture') !== FIXTURE) return;
    void import('./fixtures/compaction').then((module) => setFixture(module.fixtureCompact));
  }, [search]);

  return (
    <>
      {compact.error === null ? null : <ErrorNote message={compact.error} />}
      <CompactionBoard payload={fixture === null ? compact.data : fixture} sample={fixture !== null} />
    </>
  );
}
