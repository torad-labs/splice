// The console's addresses, and how an address becomes a page.
//
// Pages are DISCOVERED, never registered (CONTRACTS.md section 3): a row that
// creates pages/<name>/index.tsx is picked up with no shell edit, and until it
// exists the address renders the honest empty naming the row that will build
// it. Nothing is renamed, so an M2 row arrives without a shell change.
//
// Everything here is a function of plain strings: the address table, the
// redirect and the discovery map ARE the routing contract, and they are tested
// without a router, a DOM or a page.

export const ADDRESSES = [
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
] as const;

export type Address = (typeof ADDRESSES)[number];

/** The row that will create each page directory, printed in its honest empty. */
export const PAGE_ROW: Record<Address, string> = {
  fleet: 'M2-01',
  turns: 'M2-03',
  sessions: 'M2-02',
  teams: 'M1-05',
  projects: 'M2-02',
  accounts: 'M2-04',
  usage: 'M2-06',
  settings: 'M2-05',
  models: 'M2-06',
  logs: 'M2-03',
  compaction: 'M2-06',
  mcp: 'M2-07',
  doctor: 'M2-07',
};

/**
 * Where an address reads from until its own directory exists. The three
 * renamed pages keep their old directory and their old code; the day an M2 row
 * creates pages/usage, the discovery picks it up and this table stops being
 * used, with no edit here.
 */
export const PAGE_ALIAS: Partial<Record<Address, string>> = {
  usage: 'burn',
  accounts: 'auth',
  settings: 'config',
};

/** The retired addresses of the tab router, and the address each one lands on. */
export const LEGACY_ADDRESS: Record<string, Address> = {
  fleet: 'fleet',
  burn: 'usage',
  auth: 'accounts',
  config: 'settings',
  logs: 'logs',
  compaction: 'compaction',
};

const isAddress = (slug: string): slug is Address => (ADDRESSES as readonly string[]).includes(slug);

/** A path or hash fragment with its slashes, case and query stripped. */
const slugOf = (value: string): string =>
  value
    .replace(/^#/, '')
    .split('?')[0]
    .replace(/^\/+/, '')
    .replace(/\/+$/, '')
    .toLowerCase();

/**
 * The canonical hash for whatever the address bar is holding.
 *
 * This reads the RAW hash, which is the only place the old addresses are still
 * distinguishable: react-router's hash history prepends the slash itself, so
 * `#fleet` and `#/fleet` both arrive at the router as `/fleet` and a redirect
 * route could not tell them apart. Canonicalising once at boot (and any time
 * the hash arrives from outside the app) leaves the router only ever seeing
 * canonical paths.
 */
export function canonicalHash(rawHash: string): string {
  const slug = slugOf(rawHash);
  if (isAddress(slug)) return `#/${slug}`;
  const legacy = LEGACY_ADDRESS[slug];
  return `#/${legacy ?? ADDRESSES[0]}`;
}

/** The address a router pathname is showing. An alias path lands on its address. */
export function addressOf(pathname: string): Address {
  const slug = slugOf(pathname);
  if (isAddress(slug)) return slug;
  return LEGACY_ADDRESS[slug] ?? ADDRESSES[0];
}

/**
 * The page module an address renders, given the keys the glob found. Its own
 * directory wins; otherwise the alias; otherwise null, and the caller prints
 * the honest empty from PAGE_ROW.
 */
export function pageModuleKey(address: Address, available: readonly string[]): string | null {
  const own = `../pages/${address}/index.tsx`;
  if (available.includes(own)) return own;
  const alias = PAGE_ALIAS[address];
  if (alias !== undefined) {
    const aliased = `../pages/${alias}/index.tsx`;
    if (available.includes(aliased)) return aliased;
  }
  return null;
}

/** The old bare paths that still have their own redirect route. */
export const LEGACY_PATHS: ReadonlyArray<[string, Address]> = [
  ['burn', 'usage'],
  ['auth', 'accounts'],
  ['config', 'settings'],
];
