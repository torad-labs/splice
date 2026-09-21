// Models: the catalog per head, as strips, with the tiers a head will and will not fill.
//
// The page exists for one question — what is this head actually going to run, and what does it cost
// per million tokens — and it answers it from GET /api/models, which is pending V4-127. Until the
// route lands the page prints the honest empty that names the row, never a catalog it assembled
// from the topology on its own: the topology is boot-only and the console does not read the file.
//
// The context-window SOURCE is printed on every strip because a window is the one number on this
// page that Claude Code itself acts on, and "400k" means something different when it came from the
// head's own declaration than when it fell out of a provider default.
import { useEffect, useState } from 'react';
import { useLocation } from 'react-router';
import { startModelsPolling, useModels } from '@entities/model';
import type { ModelsPayload, PendingRoute } from '@entities/model';
import { useViews, ViewTabs } from '@features/views';
import { Bay, Empty, HolderEdge } from '@shared/ui';
import { Blank, Fault, Key } from '@shared/controls';
import { HeadCatalogBay, ModelDetail } from './components';
import { DEFAULT_VIEWS, EMPTIES, byProvider, findModel } from './model';
import { S } from './strings';
import './models.css';

const PAGE_ID = 'models';
const POLL_MS = 30000;

/** The name this page's fixture answers to: the fixture's own FILE name (CONTRACTS.md section 4).
 *  One vocabulary for every page, so a driver's table is the fixtures directory listing. */
const FIXTURE = 'models';

/** The fixture name this page accepts, or null. The name must RESOLVE and not merely be present: a
 *  page that renders fixture bytes for a name it does not carry would set the capture marker to a
 *  fixture that does not exist, which is the stale marker by another route (law 23). Exported
 *  because a test pins exactly that. */
export function fixtureModels(name: string | null): string | null {
  return import.meta.env.DEV && name === FIXTURE ? name : null;
}

export function ModelsBoard({ catalog, sample }: {
  catalog: ModelsPayload | PendingRoute | null;
  /** The fixture's own file name when a fixture fed this board, undefined otherwise. */
  sample?: string | undefined;
}) {
  const views = useViews(PAGE_ID, DEFAULT_VIEWS);
  const [open, setOpen] = useState<string | null>(null);

  const pending = catalog === null || 'pending' in catalog;
  const heads = catalog === null || 'pending' in catalog ? [] : catalog.heads;
  const opened = catalog === null ? null : findModel(catalog, open);

  return (
    <div
      className="myx-models"
      {...(import.meta.env.DEV && sample !== undefined ? { 'data-sample': sample } : {})}
    >
      <header className="myx-models-head">
        <h1 className="myx-models-title">{S.title}</h1>
        <ViewTabs pageId={PAGE_ID} defaults={DEFAULT_VIEWS} />
        {sample === undefined ? null : <HolderEdge state="grey" label={S.sample} />}
      </header>

      {catalog === null ? <Blank strips={5} /> : null}
      {pending && catalog !== null ? (
        <Empty text={EMPTIES.catalogPending.text} source={EMPTIES.catalogPending.source} />
      ) : null}

      {pending ? null : (
        <div className={opened === null ? 'myx-models-body' : 'myx-models-body myx-models-body-open'}>
          <div className="myx-models-bays">
            {views.active.id === 'by-provider' ? (
              byProvider(heads).map((group) => (
                <Bay key={group.provider} label={group.provider} count={group.heads.length}>
                  {group.heads.map((head) => (
                    <HeadCatalogBay key={head.key} head={head} selected={open} onSelect={setOpen} />
                  ))}
                </Bay>
              ))
            ) : (
              heads.map((head) => (
                <HeadCatalogBay key={head.key} head={head} selected={open} onSelect={setOpen} />
              ))
            )}
          </div>

          {/* THE COLUMN IS A ZERO TRACK AT REST AND SWELLS OPEN (M1-116 rules the collapse idiom;
              M1-112 measured the defect). The resting column was a 432x784 dead region, 21.5% of
              the frame, against the comp's own 11.2% -- a fifth of the page reserved for a response
              to a click nobody has made. The aside STAYS MOUNTED and empty, which is what gives the
              track something to transition FROM: M1-112 unmounted it, and an unmounted column has
              to mount and then fade its content up, which pops if the mount lands a frame late.
              This shape is turns', sessions' and projects', mirrored rather than re-invented.
              THE EMPTY STAYS GONE, deliberately: an honest empty says what a panel is missing and
              which source supplies it, and at rest there is no panel to be missing anything, so a
              card reading "none open" captions a panel that does not exist. `EMPTIES.noneOpen` went
              with it: M1-112 could not delete it from model.ts, outside that fence, and M2-28 did. */}
          <aside className="myx-models-detail myx-swell" aria-label={S.catalog} aria-hidden={opened === null}>
            {opened === null ? null : (
              <>
                <Key className="myx-swell-close" onClick={() => setOpen(null)}>{S.close}</Key>
                <ModelDetail model={opened.model} head={opened.head} />
              </>
            )}
          </aside>
        </div>
      )}
    </div>
  );
}

export default function ModelsPage() {
  const { search } = useLocation();
  const models = useModels((state) => state);
  const [sample, setSample] = useState<{ name: string; payload: ModelsPayload } | null>(null);
  useEffect(() => startModelsPolling(POLL_MS), []);

  // The name must RESOLVE, not merely be present (law 23): a page that rendered fixture bytes for a
  // name it does not carry would set the capture marker to a fixture that does not exist.
  const name = fixtureModels(new URLSearchParams(search).get('fixture'));

  useEffect(() => {
    if (name === null) {
      // A name that is asked for and then dropped must take the marker with it: an early return
      // that leaves the previous state in place is the stale marker this row exists to prevent
      // (measured in a browser: five pages kept one across a hash change).
      setSample(null);
      return undefined;
    }
    // The specifier is BUILT AT RUNTIME, not written as a literal, and the module is reached by a
    // DYNAMIC import: a static `import { fixtureCatalog } from './fixtures/models'` is a real
    // dependency edge whatever the DEV branch says, so the fixture's bytes are inlined into
    // dist/index.html and ship to the operator (measured 2026-09-18 - the wall named this fixture's
    // literals, and this is the import CONTRACTS.md section 4 warns about).
    void import(/* @vite-ignore */ `./fixtures/${name}.ts`)
      .then((module: { fixtureCatalog?: ModelsPayload }) => {
        setSample(module.fixtureCatalog === undefined ? null : { name, payload: module.fixtureCatalog });
      })
      .catch(() => undefined);
  }, [name]);

  const catalog = sample === null ? models.data : sample.payload;

  return (
    <>
      {models.error === null ? null : <Fault message={models.error} />}
      <ModelsBoard catalog={catalog} sample={sample?.name} />
    </>
  );
}
