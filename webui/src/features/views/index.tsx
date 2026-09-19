// Saved views, as a page uses them: `useViews` hands a page its tabs and the
// six operations the contract names, and `ViewTabs` is the tab row plus an
// inline editor. Nothing here is modal: a view is renamed on the strip it
// belongs to, the way every other edit in this console happens.
import { useCallback, useState, useSyncExternalStore } from 'react';
import { cx } from '@shared/lib';
import { Input } from '@shared/controls';
import { onViewStoreCreated, peekViewStore, viewStoreFor } from './store';
import type { View } from './store';
import { S } from './strings';
import './views.css';

export type { View, ViewSort } from './store';

export interface ViewsApi {
  views: View[];
  active: View;
  setActive(id: string): void;
  add(view: View): void;
  rename(id: string, name: string): void;
  duplicate(id: string): void;
  reorder(ids: string[]): void;
  remove(id: string): void;
  exportJson(): string;
}

/** The page's views, and the operations over them. */
export function useViews(pageId: string, defaults: readonly View[]): ViewsApi {
  const store = viewStoreFor(pageId, defaults);
  const snapshot = useSyncExternalStore(store.subscribe, store.get, store.get);
  return {
    views: snapshot.views,
    active: snapshot.active,
    setActive: store.setActive,
    add: store.add,
    rename: store.rename,
    duplicate: store.duplicate,
    reorder: store.reorder,
    remove: store.remove,
    exportJson: store.exportJson,
  };
}

const NO_VIEWS: View[] = [];

/**
 * The same page's views, for a reader that is not the page (the palette).
 *
 * The page registers its store when it renders its tabs, which can be after
 * this reader first looks, so the subscription also watches for that
 * registration and attaches when it arrives — otherwise a palette opened on a
 * freshly mounted page would list the page's views as empty until something
 * else happened to re-render.
 */
export function usePageViews(pageId: string): View[] {
  const subscribe = useCallback(
    (onChange: () => void) => {
      let stopStore: () => void = () => undefined;
      const attach = () => {
        stopStore();
        stopStore = peekViewStore(pageId)?.subscribe(onChange) ?? (() => undefined);
      };
      const stopCreated = onViewStoreCreated(() => {
        attach();
        onChange();
      });
      attach();
      return () => {
        stopCreated();
        stopStore();
      };
    },
    [pageId],
  );
  return useSyncExternalStore(
    subscribe,
    () => peekViewStore(pageId)?.get().views ?? NO_VIEWS,
    () => NO_VIEWS,
  );
}

/** Activate one of the page's views. Does nothing if the page has none yet. */
export function selectView(pageId: string, viewId: string): void {
  peekViewStore(pageId)?.setActive(viewId);
}

export function ViewTabs({ pageId, defaults }: { pageId: string; defaults: readonly View[] }) {
  const { views, active, setActive, rename, duplicate, remove, reorder } = useViews(pageId, defaults);
  const [editing, setEditing] = useState(false);
  const [draft, setDraft] = useState('');

  const index = views.findIndex((view) => view.id === active.id);

  const move = (delta: number) => {
    const target = index + delta;
    if (index < 0 || target < 0 || target >= views.length) return;
    const ids = views.map((view) => view.id);
    const held = ids[index];
    ids[index] = ids[target];
    ids[target] = held;
    reorder(ids);
  };

  return (
    <div className="myx-views">
      <div className="myx-views-tabs" role="tablist" aria-label={S.tabs}>
        {views.map((view) => (
          <button
            key={view.id}
            type="button"
            role="tab"
            aria-selected={view.id === active.id}
            className={cx('myx-views-tab', view.id === active.id && 'myx-views-tab-active')}
            onClick={() => setActive(view.id)}
          >
            {view.name}
          </button>
        ))}
      </div>

      <button
        type="button"
        className="myx-views-edit"
        aria-expanded={editing}
        onClick={() => {
          setDraft(active.name);
          setEditing((open) => !open);
        }}
      >
        {S.edit}
      </button>

      {editing ? (
        <div className="myx-views-panel">
          <form
            className="myx-views-rename"
            onSubmit={(event) => {
              event.preventDefault();
              if (draft.trim() !== '') rename(active.id, draft.trim());
            }}
          >
            {/* THE RENAME FIELD IS THE WORLD'S INPUT (M1-103). This is the one site where Input is
                an EQUIVALENT replacement rather than a better or a worse one, and it was checked
                against the shape it replaces before it was made: both render a label wrapping a
                label span and a boxed input, both put the input inside the form so Enter still
                submits, and the box was the same paper, hairline, radius 0 and focus ring. TWO
                MEASURED DIFFERENCES, both in the primitive's favour: the box paper moves from
                --strip to --strip-field, which is the field paper the rest of the console uses for
                an editable box, and the label ink moves from --ink-mute to --strip-ink-mute --
                --ink-mute is the ROOM's ink printed on paper, the D7 defect this campaign found
                five times, and it was still standing here. The face moves from --font-label to
                --font-figure, which since M1-17 IS the label face with tabular figures, so a typed
                name renders in the same letterforms. */}
            <Input label={S.name} value={draft} onChange={(next) => setDraft(next)} />
            <button type="submit" className="myx-views-action">{S.rename}</button>
          </form>
          <div className="myx-views-actions">
            <button type="button" className="myx-views-action" onClick={() => duplicate(active.id)}>
              {S.duplicate}
            </button>
            <button
              type="button"
              className="myx-views-action"
              onClick={() => move(-1)}
              disabled={index <= 0}
            >
              {S.earlier}
            </button>
            <button
              type="button"
              className="myx-views-action"
              onClick={() => move(1)}
              disabled={index < 0 || index >= views.length - 1}
            >
              {S.later}
            </button>
            <button type="button" className="myx-views-action" onClick={() => remove(active.id)}>
              {S.remove}
            </button>
          </div>
        </div>
      ) : null}
    </div>
  );
}
