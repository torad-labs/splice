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
import { Bay, Empty, ErrorNote, HolderEdge, SkeletonRows } from '@shared/ui';
import { HeadCatalogBay, ModelDetail } from './components';
import { DEFAULT_VIEWS, EMPTIES, byProvider, findModel } from './model';
import { fixtureCatalog, fixtureName } from './fixtures/models';
import { S } from './strings';
import './models.css';

const PAGE_ID = 'models';
const POLL_MS = 30000;

export function ModelsBoard({ catalog, sample = false }: {
  catalog: ModelsPayload | PendingRoute | null;
  sample?: boolean;
}) {
  const views = useViews(PAGE_ID, DEFAULT_VIEWS);
  const [open, setOpen] = useState<string | null>(null);

  const pending = catalog === null || 'pending' in catalog;
  const heads = catalog === null || 'pending' in catalog ? [] : catalog.heads;
  const opened = catalog === null ? null : findModel(catalog, open);

  return (
    <div className="myx-models">
      <header className="myx-models-head">
        <h1 className="myx-models-title">{S.title}</h1>
        <ViewTabs pageId={PAGE_ID} defaults={DEFAULT_VIEWS} />
        {sample ? <HolderEdge state="grey" label={S.sample} /> : null}
      </header>

      {catalog === null ? <SkeletonRows rows={5} cols={6} /> : null}
      {pending && catalog !== null ? (
        <Empty text={EMPTIES.catalogPending.text} source={EMPTIES.catalogPending.source} />
      ) : null}

      {pending ? null : (
        <div className="myx-models-body">
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

          <aside className="myx-models-detail" aria-label={S.catalog}>
            {opened === null ? (
              <Empty text={EMPTIES.noneOpen.text} source={EMPTIES.noneOpen.source} />
            ) : (
              <ModelDetail model={opened.model} head={opened.head} />
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
  useEffect(() => startModelsPolling(POLL_MS), []);

  const fixture = fixtureName(search, import.meta.env.DEV);

  return (
    <>
      {models.error === null ? null : <ErrorNote message={models.error} />}
      <ModelsBoard catalog={fixture === null ? models.data : fixtureCatalog} sample={fixture !== null} />
    </>
  );
}
