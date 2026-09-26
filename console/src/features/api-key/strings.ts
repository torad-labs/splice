// Every word this feature prints. S: labels, three words or fewer, sentence case. H: the line an
// answer reads back, one sentence of twelve words or fewer, per head that reads the key.
export const S = {
  newKey: 'New key',
  store: 'Store key',
  remove: 'Remove key',
  /** The armed remove names the variable it removes. */
  removeArmed: (name: string): string => `Remove ${name}`,
} as const;

/** Where a head reads its key now: the link of ApiKeyAuthProvider's read chain the daemon names. */
export const SOURCE: Readonly<Record<string, string>> = {
  environment: 'Environment',
  file: 'Key file',
  store: 'Key store',
  missing: 'Nowhere',
};

/** What one head reads after a write, said per link. A stored key the environment or a key file
 *  shadows is said as shadowed, never as applied (KeyRoutes.kt). */
export const H = {
  store: (head: string): string => `${head} uses the stored key from its next request.`,
  environment: (head: string, name: string): string => `${head} still reads ${name} from the daemon's environment.`,
  file: (head: string): string => `${head} still reads the key in its key file.`,
  missing: (head: string): string => `${head} has no key now.`,
  other: (head: string, source: string): string => `${head} reads its key from: ${source}.`,
  noReader: 'No head reads this key.',
} as const;
