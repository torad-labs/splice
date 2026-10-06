// What an api-key head's key control says. Copy lives in modules like this one.
export const KW = {
  title: 'Key',
  readsFrom: 'Reads it from',
  configuredFile: 'Configured key file',
  store: 'Store key',
  replace: 'Replace key',
  remove: 'Remove key',
  removeAsk: 'Remove this key?',
  removeWhy: 'splice forgets the key it stored. A head that has no other key to read stops working.',
  removeAct: (name: string): string => `Remove ${name}`,
  ask: (name: string): string => `The key for ${name}`,
  why: 'It is written to splice’s key store and is never shown again.',
  field: 'New key',
  save: 'Save',
  cancel: 'Cancel',
  noReader: 'No command uses this key.',
  none: 'This command reads no key.',
} as const;

/** Where a head reads its key now: the link of its read chain the daemon names. */
export const KEY_SOURCE: Readonly<Record<string, string>> = {
  environment: 'Environment',
  file: 'Key file',
  store: 'Key store',
  missing: 'Nowhere',
};

/** What one head reads after a write, said per link. A stored key the environment or a key file shadows is said as
 *  shadowed, never as applied. */
export const KEY_READ = {
  store: (head: string): string => `${head} uses the stored key from its next request.`,
  environment: (head: string, name: string): string => `${head} still reads ${name} from the service environment.`,
  file: (head: string): string => `${head} still reads the key in its key file.`,
  missing: (head: string): string => `${head} has no key now.`,
  other: (head: string, source: string): string => `${head} reads its key from: ${source}.`,
} as const;
