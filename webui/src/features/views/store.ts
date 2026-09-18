// Saved views: one data source per page, Notion-style. A view owns its layout,
// filter, sort, group, visible fields and its name; the page renders the active
// one. The operations live here as plain functions over a storage slice, so the
// whole contract is tested without a DOM, and the React hook in index.tsx is
// only a subscription to them.
//
// The store is per page and cached per page: the page renders its tabs, and the
// command palette lists the same views without either owning the other.

export interface ViewSort {
  field: string;
  dir: 'asc' | 'desc';
}

export interface View {
  id: string;
  name: string;
  layout: string;
  filter: Record<string, string>;
  sort: ViewSort | null;
  group: string | null;
  fields: string[];
}

/** The slice of Storage the views need. */
export interface ViewStorage {
  getItem(key: string): string | null;
  setItem(key: string, value: string): void;
}

/** One page's saved views, as stored. */
export interface StoredViews {
  views: View[];
  activeId: string;
}

/** The contract's key: localStorage['splice.views.<pageId>']. */
export function viewsKey(pageId: string): string {
  return `splice.views.${pageId}`;
}

function isSort(value: unknown): value is ViewSort {
  if (typeof value !== 'object' || value === null) return false;
  const sort = value as Partial<ViewSort>;
  return typeof sort.field === 'string' && (sort.dir === 'asc' || sort.dir === 'desc');
}

function isView(value: unknown): value is View {
  if (typeof value !== 'object' || value === null) return false;
  const view = value as Partial<View>;
  return (
    typeof view.id === 'string' &&
    typeof view.name === 'string' &&
    typeof view.layout === 'string' &&
    typeof view.filter === 'object' &&
    view.filter !== null &&
    (view.sort === null || isSort(view.sort)) &&
    (view.group === null || typeof view.group === 'string') &&
    Array.isArray(view.fields)
  );
}

/** What was stored, or null when nothing valid is. Junk is not a view set. */
export function parseStored(raw: string | null): StoredViews | null {
  if (raw === null) return null;
  let parsed: unknown;
  try {
    parsed = JSON.parse(raw);
  } catch {
    return null;
  }
  if (typeof parsed !== 'object' || parsed === null) return null;
  const stored = parsed as Partial<StoredViews>;
  if (!Array.isArray(stored.views) || stored.views.length === 0) return null;
  if (!stored.views.every(isView)) return null;
  const activeId = typeof stored.activeId === 'string' ? stored.activeId : stored.views[0].id;
  return { views: stored.views, activeId };
}

const cloneView = (view: View): View => ({
  ...view,
  filter: { ...view.filter },
  sort: view.sort === null ? null : { ...view.sort },
  fields: [...view.fields],
});

const cloneAll = (views: readonly View[]): View[] => views.map(cloneView);

/** An id that is not taken in this page's view set. */
function freshId(views: readonly View[]): string {
  const taken = new Set(views.map((view) => view.id));
  let id: string;
  do {
    id = `view-${Math.random().toString(36).slice(2, 10)}`;
  } while (taken.has(id));
  return id;
}

export interface ViewsSnapshot {
  views: View[];
  active: View;
}

export interface ViewStore {
  get(): ViewsSnapshot;
  subscribe(listener: () => void): () => void;
  setActive(id: string): void;
  add(view: View): void;
  rename(id: string, name: string): void;
  duplicate(id: string): void;
  reorder(ids: string[]): void;
  remove(id: string): void;
  exportJson(): string;
}

/**
 * One page's view store. `defaults` are the page's own views and are what the
 * page returns to when its last view is removed; they are never mutated.
 */
export function createViewStore(
  pageId: string,
  defaults: readonly View[],
  storage: ViewStorage,
): ViewStore {
  const listeners = new Set<() => void>();
  const stored = parseStored(safeGet(storage, viewsKey(pageId)));
  let views: View[] = stored === null ? cloneAll(defaults) : cloneAll(stored.views);
  let activeId: string = stored?.activeId ?? views[0]?.id ?? '';

  let snapshot: ViewsSnapshot = build();
  function build(): ViewsSnapshot {
    const active = views.find((view) => view.id === activeId) ?? views[0];
    return { views, active };
  }

  function commit(): void {
    snapshot = build();
    safeSet(storage, viewsKey(pageId), JSON.stringify({ views, activeId }));
    for (const listener of listeners) listener();
  }

  function withView(id: string, change: (view: View) => View): void {
    views = views.map((view) => (view.id === id ? change(cloneView(view)) : view));
  }

  return {
    get: () => snapshot,
    subscribe: (listener) => {
      listeners.add(listener);
      return () => listeners.delete(listener);
    },
    setActive: (id) => {
      if (id === activeId || !views.some((view) => view.id === id)) return;
      activeId = id;
      commit();
    },
    add: (view) => {
      views = [...views, cloneView({ ...view, id: view.id === '' ? freshId(views) : view.id })];
      activeId = views[views.length - 1].id;
      commit();
    },
    rename: (id, name) => {
      withView(id, (view) => ({ ...view, name }));
      commit();
    },
    duplicate: (id) => {
      const at = views.findIndex((view) => view.id === id);
      if (at < 0) return;
      const copy = cloneView({ ...views[at], id: freshId(views), name: `${views[at].name} copy` });
      views = [...views.slice(0, at + 1), copy, ...views.slice(at + 1)];
      activeId = copy.id;
      commit();
    },
    reorder: (ids) => {
      const byId = new Map(views.map((view) => [view.id, view]));
      const ordered = ids.flatMap((id) => {
        const view = byId.get(id);
        if (view === undefined) return [];
        byId.delete(id);
        return [view];
      });
      views = [...ordered, ...byId.values()];
      commit();
    },
    remove: (id) => {
      // Removing the last view restores the page's defaults: a page with no
      // views at all has nothing to render its data source under.
      if (views.length <= 1) {
        views = cloneAll(defaults);
        activeId = views[0]?.id ?? '';
        commit();
        return;
      }
      const at = views.findIndex((view) => view.id === id);
      if (at < 0) return;
      views = views.filter((view) => view.id !== id);
      if (activeId === id) activeId = views[Math.min(at, views.length - 1)].id;
      commit();
    },
    exportJson: () => JSON.stringify({ pageId, views, activeId }, null, 2),
  };
}

function safeGet(storage: ViewStorage, key: string): string | null {
  try {
    return storage.getItem(key);
  } catch {
    return null;
  }
}

function safeSet(storage: ViewStorage, key: string, value: string): void {
  try {
    storage.setItem(key, value);
  } catch {
    /* a storage that refuses writes still holds the views for this session */
  }
}

/** An in-memory storage, for a browser that refuses one. */
export function memoryStorage(): ViewStorage {
  const cells = new Map<string, string>();
  return {
    getItem: (key) => cells.get(key) ?? null,
    setItem: (key, value) => {
      cells.set(key, value);
    },
  };
}

// ── the per-page registry ────────────────────────────────────────────────────
// The page's tabs and the palette's view list are the same store, so the store
// is created once per page and handed to both.

const stores = new Map<string, ViewStore>();
const created = new Set<() => void>();

/** Fires when a page registers its store, so a reader that looked too early can re-read. */
export function onViewStoreCreated(listener: () => void): () => void {
  created.add(listener);
  return () => created.delete(listener);
}

export function viewStoreFor(pageId: string, defaults: readonly View[]): ViewStore {
  const existing = stores.get(pageId);
  if (existing !== undefined) return existing;
  const store = createViewStore(pageId, defaults, browserStorage());
  stores.set(pageId, store);
  for (const listener of created) listener();
  return store;
}

/** The page's store when that page has rendered its tabs, else null. */
export function peekViewStore(pageId: string): ViewStore | null {
  return stores.get(pageId) ?? null;
}

function browserStorage(): ViewStorage {
  try {
    return window.localStorage;
  } catch {
    return memoryStorage();
  }
}
