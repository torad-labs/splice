// VIEWS (row M1-03). Every operation the contract names, over an in-memory
// storage shim: the store is plain functions, so none of this needs a DOM, and
// the rules that have a wrong answer which LOOKS right are pinned here —
// removing the last view restores the page's defaults instead of leaving a
// page with nothing to render, and a stored set that is junk is not obeyed.
import { describe, expect, test } from 'vitest';
import {
  createViewStore,
  memoryStorage,
  onViewStoreCreated,
  parseStored,
  peekViewStore,
  viewStoreFor,
  viewsKey,
  type View,
  type ViewStorage,
} from '../src/features/views/store';

const view = (id: string, name = id): View => ({
  id,
  name,
  layout: 'table',
  filter: {},
  sort: null,
  group: null,
  fields: ['head', 'session'],
});

const DEFAULTS: View[] = [view('by-head', 'by head'), view('by-role', 'by role')];

function storeWith(seed: Record<string, string> = {}) {
  const storage = memoryStorage();
  for (const [key, value] of Object.entries(seed)) storage.setItem(key, value);
  return { storage, store: createViewStore('teams', DEFAULTS, storage) };
}

describe('what a page opens with', () => {
  test('the defaults, in order, with the first one active', () => {
    const { store } = storeWith();
    const { views, active } = store.get();
    expect(views.map((v) => v.id)).toEqual(['by-head', 'by-role']);
    expect(active.id).toBe('by-head');
  });

  test('a stored set wins over the defaults', () => {
    const stored = JSON.stringify({ views: [view('mine', 'mine')], activeId: 'mine' });
    const { store } = storeWith({ [viewsKey('teams')]: stored });
    expect(store.get().views.map((v) => v.id)).toEqual(['mine']);
    expect(store.get().active.id).toBe('mine');
  });

  test('junk in storage is ignored rather than obeyed', () => {
    expect(parseStored('not json')).toBeNull();
    expect(parseStored('{"views":[],"activeId":"x"}')).toBeNull();
    expect(parseStored('{"views":[{"id":"a"}],"activeId":"a"}')).toBeNull();
    const { store } = storeWith({ [viewsKey('teams')]: '{"views":[{"nope":true}]}' });
    expect(store.get().views.map((v) => v.id)).toEqual(['by-head', 'by-role']);
  });

  test('the defaults are not mutated by a page that edits its own views', () => {
    const { store } = storeWith();
    store.rename('by-head', 'renamed');
    store.remove('by-role');
    expect(DEFAULTS.map((v) => v.name)).toEqual(['by head', 'by role']);
  });
});

describe('the operations', () => {
  test('setActive moves the active view', () => {
    const { store } = storeWith();
    store.setActive('by-role');
    expect(store.get().active.id).toBe('by-role');
  });

  test('setActive ignores an id the page does not have', () => {
    const { store } = storeWith();
    store.setActive('nope');
    expect(store.get().active.id).toBe('by-head');
  });

  test('add appends and activates', () => {
    const { store } = storeWith();
    store.add(view('timeline', 'timeline'));
    expect(store.get().views.map((v) => v.id)).toEqual(['by-head', 'by-role', 'timeline']);
    expect(store.get().active.id).toBe('timeline');
  });

  test('rename changes the name and nothing else', () => {
    const { store } = storeWith();
    store.rename('by-head', 'heads');
    const renamed = store.get().views[0];
    expect(renamed.name).toBe('heads');
    expect(renamed.fields).toEqual(['head', 'session']);
  });

  test('duplicate copies a view in place, names it, and activates it', () => {
    const { store } = storeWith();
    store.duplicate('by-head');
    const { views, active } = store.get();
    expect(views).toHaveLength(3);
    expect(views[0].id).toBe('by-head');
    expect(views[1].name).toBe('by head copy');
    expect(views[1].id).not.toBe('by-head');
    expect(active.id).toBe(views[1].id);
  });

  test('a duplicated view is an independent copy', () => {
    const { store } = storeWith();
    store.duplicate('by-head');
    const copyId = store.get().views[1].id;
    store.rename(copyId, 'mine');
    expect(store.get().views[0].name).toBe('by head');
  });

  test('reorder applies the order it is given', () => {
    const { store } = storeWith();
    store.reorder(['by-role', 'by-head']);
    expect(store.get().views.map((v) => v.id)).toEqual(['by-role', 'by-head']);
  });

  test('reorder keeps a view nobody named, rather than dropping it', () => {
    const { store } = storeWith();
    store.add(view('timeline', 'timeline'));
    store.reorder(['timeline']);
    expect(store.get().views.map((v) => v.id)).toEqual(['timeline', 'by-head', 'by-role']);
  });

  test('remove drops a view and moves the active one to its neighbour', () => {
    const { store } = storeWith();
    store.setActive('by-head');
    store.remove('by-head');
    expect(store.get().views.map((v) => v.id)).toEqual(['by-role']);
    expect(store.get().active.id).toBe('by-role');
  });

  test('removing the last view restores the defaults', () => {
    const { store } = storeWith();
    store.remove('by-role');
    expect(store.get().views).toHaveLength(1);
    store.remove('by-head');
    expect(store.get().views.map((v) => v.id)).toEqual(['by-head', 'by-role']);
    expect(store.get().active.id).toBe('by-head');
  });
});

describe('storage', () => {
  test('the key is the contract key', () => {
    expect(viewsKey('teams')).toBe('splice.views.teams');
  });

  test('an edit is written under that key', () => {
    const { storage, store } = storeWith();
    store.rename('by-head', 'heads');
    const raw = storage.getItem('splice.views.teams');
    expect(raw).not.toBeNull();
    expect(parseStored(raw)?.views[0].name).toBe('heads');
  });

  test('two pages do not read each other', () => {
    const storage = memoryStorage();
    const teams = createViewStore('teams', DEFAULTS, storage);
    const fleet = createViewStore('fleet', [view('heads', 'heads')], storage);
    teams.rename('by-head', 'renamed');
    expect(fleet.get().views[0].name).toBe('heads');
  });

  test('a storage that refuses writes does not throw', () => {
    const refusing: ViewStorage = {
      getItem: () => null,
      setItem: () => {
        throw new Error('quota');
      },
    };
    const store = createViewStore('teams', DEFAULTS, refusing);
    expect(() => store.rename('by-head', 'heads')).not.toThrow();
    expect(store.get().views[0].name).toBe('heads');
  });

  test('a storage that refuses reads falls back to the defaults', () => {
    const refusing: ViewStorage = {
      getItem: () => {
        throw new Error('blocked');
      },
      setItem: () => undefined,
    };
    const store = createViewStore('teams', DEFAULTS, refusing);
    expect(store.get().views.map((v) => v.id)).toEqual(['by-head', 'by-role']);
  });
});

describe('the per-page registry', () => {
  test('a page registers once, and the same page gets the same store back', () => {
    const first = viewStoreFor('registry-page', DEFAULTS);
    expect(peekViewStore('registry-page')).toBe(first);
    expect(viewStoreFor('registry-page', [view('other', 'other')])).toBe(first);
    expect(first.get().views.map((v) => v.id)).toEqual(['by-head', 'by-role']);
  });

  test('a reader that looked before the page registered is told when it arrives', () => {
    expect(peekViewStore('late-page')).toBeNull();
    const told: string[] = [];
    const stop = onViewStoreCreated(() => told.push('created'));
    viewStoreFor('late-page', DEFAULTS);
    stop();
    expect(told).toEqual(['created']);
    expect(peekViewStore('late-page')?.get().views).toHaveLength(2);
  });
});

describe('exportJson', () => {
  test('round-trips the page back through the same parser', () => {
    const { store } = storeWith();
    store.rename('by-head', 'heads');
    store.add(view('timeline', 'timeline'));
    store.setActive('by-role');

    const exported = store.exportJson();
    const parsed = JSON.parse(exported) as { pageId: string; views: View[]; activeId: string };
    expect(parsed.views).toEqual(store.get().views);
    expect(parsed.pageId).toBe('teams');

    // and the export is a working seed for a fresh page
    const restored = createViewStore('teams', DEFAULTS, memoryStorage());
    expect(restored.get().views.map((v) => v.id)).toEqual(['by-head', 'by-role']);
    const reread = parseStored(JSON.stringify(parsed));
    expect(reread?.views).toEqual(store.get().views);
    expect(reread?.activeId).toBe('by-role');
  });
});
