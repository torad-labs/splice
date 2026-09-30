// The names no page owns: the shell's own reads and the routes the console never shows. Every other name the denominator enumerates
// (Knob.kt entries, splice.toml's fields, the routes of FEATURES.md sections 2.1 and 6, and the CLI's verbs) is declared by the
// page that answers it, in that page's `coverage.ts`, and a name with no disposition anywhere fails the wall by name.
//
// `excluded` names why the console never shows it. The baseline once held every name as `pending` while the pages were built; each
// page's declaration replaced its names and the entries went with them.
import type { Disposition } from './checks';

/** Routes the shell reads, which no page owns and so no page's coverage.ts can declare. */
const SHELL: readonly { readonly names: readonly string[] }[] = [{ names: ['/api/events'] }];

const EXCLUDED: readonly { readonly reason: string; readonly names: readonly string[] }[] = [
  { reason: 'destructive, CLI only', names: ['/api/daemon/shutdown'] },
  { reason: 'head-internal', names: ['/launch/{head}', '/statusline/{head}'] },
  { reason: 'client transport, not an operator surface', names: ['/mcp/{name}'] },
  { reason: 'it is the console', names: ['/', '/dashboard'] },
];

export const dispositions: readonly Disposition[] = [
  ...SHELL.flatMap((group) => group.names.map((name): Disposition => ({ kind: 'route', name, disposition: 'read-only' }))),
  ...EXCLUDED.flatMap((group) => group.names.map((name): Disposition => ({ kind: 'route', name, disposition: 'excluded', reason: group.reason }))),
];
