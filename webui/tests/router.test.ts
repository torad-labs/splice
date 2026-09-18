// ROUTER (row M1-03). The addressing contract of CONTRACTS.md section 3, held
// as pure functions: the thirteen addresses, the old bare addresses and where
// they land, the fallback, and how an address finds its page. None of it needs
// a router, a DOM or a page to be exercised, so none of it can rot behind one.
//
// The last block is the discovery itself against the REAL tree: the six pages
// that exist are found through their own directory or their alias, and the
// seven that do not are honest empties. A page directory renamed by mistake
// fails here rather than in a blank screen.
import { describe, expect, test } from 'vitest';
import {
  ADDRESSES,
  LEGACY_ADDRESS,
  LEGACY_PATHS,
  PAGE_ALIAS,
  PAGE_ROW,
  addressOf,
  canonicalHash,
  pageModuleKey,
} from '../src/app/rows';
import { PAGE_KEYS, pageFor } from '../src/app/pages';

describe('the address table', () => {
  test('is the thirteen addresses, in reading order', () => {
    expect(ADDRESSES).toEqual([
      'fleet',
      'turns',
      'sessions',
      'teams',
      'projects',
      'accounts',
      'usage',
      'settings',
      'models',
      'logs',
      'compaction',
      'mcp',
      'doctor',
    ]);
  });

  test('every address names the row that will build it', () => {
    for (const address of ADDRESSES) {
      expect(PAGE_ROW[address], address).toMatch(/^M[123]-/);
    }
  });
});

describe('canonicalHash', () => {
  test('a canonical path is left alone', () => {
    for (const address of ADDRESSES) {
      expect(canonicalHash(`#/${address}`)).toBe(`#/${address}`);
    }
  });

  test('the old bare addresses land on their address', () => {
    expect(canonicalHash('#fleet')).toBe('#/fleet');
    expect(canonicalHash('#burn')).toBe('#/usage');
    expect(canonicalHash('#auth')).toBe('#/accounts');
    expect(canonicalHash('#config')).toBe('#/settings');
    expect(canonicalHash('#logs')).toBe('#/logs');
    expect(canonicalHash('#compaction')).toBe('#/compaction');
  });

  test('keeps the query so a dev fixture survives boot (CONTRACTS.md section 4)', () => {
    expect(canonicalHash('#/accounts?fixture=demo')).toBe('#/accounts?fixture=demo');
    expect(canonicalHash('#auth?fixture=demo')).toBe('#/accounts?fixture=demo');
    expect(canonicalHash('#?fixture=demo')).toBe('#/fleet?fixture=demo');
  });

  test('a bare path lands too, because the hash history adds the slash', () => {
    // react-router's hash history prepends `/`, so `#fleet` and `#/fleet` both
    // reach the router as `/fleet` and a redirect route cannot tell them apart.
    // This function reads the raw hash, which is the only place they differ.
    expect(canonicalHash('#/burn')).toBe('#/usage');
  });

  test('an unknown or empty address goes to the fleet', () => {
    expect(canonicalHash('#/nowhere')).toBe('#/fleet');
    expect(canonicalHash('#nowhere')).toBe('#/fleet');
    expect(canonicalHash('')).toBe('#/fleet');
    expect(canonicalHash('#')).toBe('#/fleet');
    expect(canonicalHash('#/turns?fixture=day')).toBe('#/turns?fixture=day');
    expect(canonicalHash('#/fleet/')).toBe('#/fleet');
  });
});

describe('addressOf', () => {
  test('a canonical pathname is its address', () => {
    expect(addressOf('/usage')).toBe('usage');
    expect(addressOf('/doctor')).toBe('doctor');
    expect(addressOf('fleet')).toBe('fleet');
    expect(addressOf('/FLEET')).toBe('fleet');
  });

  test('an alias pathname is the address it stands for', () => {
    expect(addressOf('/burn')).toBe('usage');
    expect(addressOf('/auth')).toBe('accounts');
    expect(addressOf('/config')).toBe('settings');
  });

  test('anything else is the fleet', () => {
    expect(addressOf('/nowhere')).toBe('fleet');
    expect(addressOf('/')).toBe('fleet');
  });

  test('the old paths with their own redirect routes point at the right address', () => {
    expect(LEGACY_PATHS).toEqual([
      ['burn', 'usage'],
      ['auth', 'accounts'],
      ['config', 'settings'],
    ]);
    for (const [slug, address] of LEGACY_PATHS) {
      expect(LEGACY_ADDRESS[slug]).toBe(address);
    }
  });
});

describe('page discovery', () => {
  const available = ['../pages/fleet/index.tsx', '../pages/burn/index.tsx'];

  test('a page with its own directory renders from it', () => {
    expect(pageModuleKey('fleet', available)).toBe('../pages/fleet/index.tsx');
  });

  test('a page without one falls back to its alias', () => {
    expect(pageModuleKey('usage', available)).toBe('../pages/burn/index.tsx');
  });

  test('a page with neither has no key, and its caller prints the honest empty', () => {
    expect(pageModuleKey('doctor', available)).toBeNull();
    expect(pageModuleKey('settings', available)).toBeNull();
    expect(PAGE_ALIAS.settings).toBe('config');
  });

  test('its own directory wins over the alias the day it exists', () => {
    expect(pageModuleKey('usage', [...available, '../pages/usage/index.tsx'])).toBe(
      '../pages/usage/index.tsx',
    );
  });
});

describe('page discovery against the tree', () => {
  // The tree moves: M2 rows create pages/usage, pages/accounts and the rest
  // while this suite exists. So the expectations here are DERIVED from the
  // glob's own key list rather than pinned to which pages happen to be built
  // today — a pinned list would have to be edited by every page row, which is
  // the one thing discovery is for.

  const has = (address: string): boolean => PAGE_KEYS.includes(`../pages/${address}/index.tsx`);

  test('the glob only ever holds page indexes', () => {
    expect(PAGE_KEYS.length).toBeGreaterThan(0);
    for (const key of PAGE_KEYS) {
      expect(key, key).toMatch(/^\.\.\/pages\/[a-z]+\/index\.tsx$/);
    }
  });

  test('an address is routed when its own directory exists, or its alias does', () => {
    for (const address of ADDRESSES) {
      const alias = PAGE_ALIAS[address];
      const expected = has(address) || (alias !== undefined && has(alias));
      expect(pageFor(address) !== null, address).toBe(expected);
    }
  });

  test('no page directory is renamed: every alias target is still in the tree', () => {
    for (const [address, alias] of Object.entries(PAGE_ALIAS)) {
      expect(has(alias), `${address} -> ${alias}`).toBe(true);
    }
    expect(has('fleet')).toBe(true);
    expect(has('logs')).toBe(true);
    expect(has('compaction')).toBe(true);
  });
});
