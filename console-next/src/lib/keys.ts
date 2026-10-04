// The keys an api-key head reads, from the daemon's key store list. The daemon names, per key, every head that reads it and
// the link of its read chain that supplies it, so this page never guesses a variable name.
import type { KeyReader, KeysPayload, KeyState } from '../types/login';
import { KEY_READ, KEY_SOURCE } from './words-keys';

/** One key of one head: the variable, whether splice's store holds it, and where this head reads it from now. */
export interface HeadKeyFacts {
  name: string;
  stored: boolean;
  source: string;
}

/** A read-chain link in the page's words, or the daemon's own word for a link this console does not know yet. */
export const sourceWord = (source: string): string => KEY_SOURCE[source] ?? source;

/** The keys `head` reads, by name. Empty when the store lists none for it. */
export function headKeys(store: KeysPayload | undefined, head: string): HeadKeyFacts[] {
  return (store?.keys ?? []).flatMap((key) => {
    const reader = key.heads.find((each) => each.head === head);
    return reader === undefined ? [] : [{ name: key.name, stored: key.stored, source: reader.source }];
  });
}

/** One head's line after a write: where it reads the key from now. */
export function readerLine(reader: KeyReader, name: string): string {
  switch (reader.source) {
    case 'store': return KEY_READ.store(reader.head);
    case 'environment': return KEY_READ.environment(reader.head, name);
    case 'file': return KEY_READ.file(reader.head);
    case 'missing': return KEY_READ.missing(reader.head);
    default: return KEY_READ.other(reader.head, reader.source);
  }
}

/** What the daemon says `head` reads after a write. Empty when no head reads the key any more. */
export const answerLines = (state: KeyState, head: string): string[] =>
  state.heads.filter((reader) => reader.head === head).map((reader) => readerLine(reader, state.name));
