// What the Needs-you page says about the list it draws: each card's tone, the one-sentence summary, the calm figures
// beside it, and the inputs the list could not read. Pure; the page only lays them out.
import type { HeadStatus } from '../types/core';
import type { InputName, Need, NeedsList, Reading } from '../types/needs';
import type { SessionRow } from '../types/sessions';
import { clockTime, countWord, noun } from './format';
import { stateOf } from './sessions';
import type { TurnOf } from './sessions';
import { K } from './words-needs';
import { N } from './words-needs-page';

export type NeedTone = 'wait' | 'stuck' | 'quota';

/** The dot a card's state word wears: a session waiting for an answer waits, a quota refusal is its own colour, and anything
 *  else is stuck when it is danger and waiting for the operator when it is a warning. */
export function toneOf(need: Need): NeedTone {
  if (need.kind === K.waiting) return 'wait';
  if (need.kind === K.quota) return 'quota';
  return need.severity === 'danger' || need.kind === K.stuck || need.kind === K.signedOut || need.kind === K.keyMissing ? 'stuck' : 'wait';
}

/** A hash address (`#/fleet/claudex`) as the router path it names. */
export const routeOf = (href: string): string => (href.startsWith('#') ? href.slice(1) : href);


/** The page's one-sentence summary. It says "Everything else is running" only when every input was read: an input it could
 *  not read is not running, it is unknown. */
export function ledeOf(list: NeedsList): string {
  const all = list.readAt !== null;
  if (list.needs.length === 0) return all ? N.nothing : N.nothingYet;
  const count = list.needs.length;
  return `${countWord(count)} ${noun(count, N.thing, N.things)} ${N.aPersonHasToDo}${all ? ` ${N.restRunning}` : ` ${N.restUnread}`}`;
}

/** "Nothing needs you" is true as of a moment: the oldest of the reads it rests on. */
export const asOfText = (list: NeedsList): string | null => (list.readAt === null ? null : `${N.asOf} ${clockTime(list.readAt)}`);

/** The inputs that were not read, each with why: never silence. */
export function unreadOf(list: NeedsList): { input: InputName; text: string }[] {
  return list.readings.filter((reading) => reading.state !== 'read').map((reading) => ({ input: reading.input, text: unreadText(reading) }));
}

function unreadText(reading: Reading): string {
  const what = N.input[reading.input];
  switch (reading.state) {
    case 'failed':
      return `${N.couldNotRead} ${what}${reading.reason === null ? '' : `: ${reading.reason}`}`;
    case 'unserved':
      return `${N.unserved(what)}`;
    default:
      return `${N.stillReading} ${what}…`;
  }
}

export interface Calm {
  /** Sessions with a live turn making progress. */
  working: number;
  /** The heads that answer and have no item: a plan whose local runtime is off does not answer, as Fleet says. */
  serving: readonly string[];
}

export function calmOf(list: NeedsList, sessions: readonly SessionRow[], turnOf: TurnOf, heads: readonly HeadStatus[]): Calm {
  const working = sessions.filter((row) => stateOf(row, turnOf(row)) === 'working').length;
  const named = new Set(list.needs.flatMap((need) => (need.head === null ? [] : [need.head])));
  const serving = heads.filter((head) => head.running && head.healthy && head.runtimeNotAnswering === undefined && !named.has(head.key)).map((head) => head.label);
  return { working, serving };
}

/** `Claude`, `Claude and Grok`, `Claude, Grok and Kimi`. */
export function listText(names: readonly string[]): string {
  if (names.length <= 1) return names.join('');
  return `${names.slice(0, -1).join(', ')} ${N.and} ${names[names.length - 1]}`;
}
