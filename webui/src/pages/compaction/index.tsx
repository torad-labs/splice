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
import { Blank, Fault } from '@shared/controls';
import { Reveal } from '@shared/ui';
import { CompactFeed } from '@widgets/compact-feed';
import { dispositions } from './coverage';
import { S } from './strings';
import './compaction.css';

export { dispositions };

/** The name this page accepts in the hash query. Declared HERE, not in the fixture module: a
 *  static import of that module — even for one constant — is a real dependency edge, so the
 *  bundler would include the fixture and its strings would ship (CONTRACTS.md section 4). */
const FIXTURE = 'compaction';

/** Whether the address asks for THIS page's fixture, by that fixture's own FILE name. Exported
 *  because the capture marker's whole value rests on it (law 23): a name this page does not carry
 *  is not a fixture, so the page must end with no marker rather than a stale one, and a test pins
 *  that here rather than inferring it from a rendered label. */
export function wantsFixture(search: string): boolean {
  return import.meta.env.DEV && new URLSearchParams(search).get('fixture') === FIXTURE;
}


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
export function CompactionBoard({ payload, sample }: {
  payload: CompactPayload | null;
  /** The fixture's own file name when a fixture fed this board, undefined otherwise. */
  sample?: string | undefined;
}) {
  return (
    <div
      className="myx-compaction"
      {...(import.meta.env.DEV && sample !== undefined ? { 'data-sample': sample } : {})}
    >
      <header className="myx-compaction-head">
        <h1 className="myx-compaction-title">{S.title}</h1>
      </header>
      {/* Behind a Reveal, not inline: the brief allows a paragraph on a page only as an honest
          empty or a Doctor fix, and explanation is on demand (m1 design review B16). The copy is
          also the page's whole reason to be here, so it is kept — one click away, and out of the
          rack's way. */}
      <Reveal label={S.law}>{<p className="myx-compaction-law">{LAW_TEXT}</p>}</Reveal>
      {payload === null ? <Blank strips={4} /> : <CompactFeed payload={payload} sample={sample !== undefined} />}
    </div>
  );
}

export default function CompactionPage() {
  const { search } = useLocation();
  const compact = useCompact((state) => state);
  useEffect(() => startCompactPolling(5000), []);

  const [sample, setSample] = useState<{ name: string; payload: CompactPayload } | null>(null);

  // The fixture loads through a DYNAMIC import inside the DEV branch, so the module is a build-time
  // nothing: `import.meta.env.DEV` is statically false in a production build, the branch is dropped,
  // and the fixture is not a dependency of anything that ships. The board renders the store's
  // payload while the module loads, and the fixture replaces it when it arrives.
  useEffect(() => {
    if (!wantsFixture(search)) {
    // The address no longer asks for this page's fixture, so the marker must GO: a name that is
    // asked for and then dropped is exactly the stale marker this row exists to prevent (measured
    // in a browser on 2026-09-18 - five pages kept one across a hash change, because the early
    // return left the previous state in place; a static render cannot see an effect, so the suite
    // was green while it happened).
      setSample(null);
      return;
    }
    // The specifier is BUILT AT RUNTIME and not written as a literal: a statically analyzable
    // `import('./fixtures/compaction')` stays a dependency edge through the single-file build even
    // when the branch around it is dead, so the module's bytes end up inlined in dist/index.html
    // (measured 2026-09-18: this page shipped 2 of its own literals that way, and the four pages
    // that load by a runtime-composed specifier shipped none). CONTRACTS.md section 4's rule is the
    // dynamic import; this is the half of it that the bundler can actually drop.
    void import(/* @vite-ignore */ `./fixtures/${FIXTURE}.ts`).then((module: { fixtureCompact?: CompactPayload }) => {
      setSample(module.fixtureCompact === undefined ? null : { name: FIXTURE, payload: module.fixtureCompact });
    }).catch(() => undefined);
  }, [search]);

  return (
    <>
      {compact.error === null ? null : <Fault message={compact.error} />}
      <CompactionBoard payload={sample === null ? compact.data : sample.payload} sample={sample?.name} />
    </>
  );
}
