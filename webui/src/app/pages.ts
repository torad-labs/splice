// The discovery itself: one glob, keyed by directory name (CONTRACTS.md
// section 3). Vite resolves the pattern at build time, so the shipped bundle
// carries exactly the pages that exist in the tree, and a page that lands
// tomorrow needs no edit here.
//
// The glob is LAZY, and each page component is built once and cached. Eager
// importing would pull every page into the boot module graph, so one page that
// is momentarily broken (a peer mid-write in the shared worktree) would take
// the whole console down with it; lazily, it costs that page only. The artifact
// is a single file either way, so there is no extra request to pay for it.
import { createElement, lazy } from 'react';
import type { ComponentType } from 'react';
import { Empty } from '@shared/ui';
import { type Address, PAGE_ROW, pageModuleKey } from './rows';

interface PageModule {
  default?: ComponentType;
}

const loaders = import.meta.glob<PageModule>('../pages/*/index.tsx');

/** Every page module the tree currently holds, as glob keys. */
export const PAGE_KEYS: string[] = Object.keys(loaders);

const built = new Map<string, ComponentType>();

/**
 * The page component for an address, or null when that page is not built yet.
 *
 * A module that exists but exports no default is not a page either — the
 * contract says every page index has one — so it renders the same honest empty
 * its row would, rather than throwing inside the shell.
 */
export function pageFor(address: Address): ComponentType | null {
  const key = pageModuleKey(address, PAGE_KEYS);
  if (key === null) return null;
  const load = loaders[key];
  if (load === undefined) return null;

  let component = built.get(key);
  if (component === undefined) {
    component = lazy(async (): Promise<{ default: ComponentType }> => {
      const module = await load();
      const Page = module.default;
      if (Page === undefined) {
        // A .ts file holds no JSX (CONTRACTS.md section 4), so the fallback is
        // built with createElement.
        const Honest = () => createElement(Empty, { text: 'page not built', source: `row ${PAGE_ROW[address]}` });
        return { default: Honest };
      }
      return { default: Page };
    });
    built.set(key, component);
  }
  return component;
}
