// What a person reads of a message. Claude Code wraps some of what it writes in tags (a slash command and its output, a
// background task's notice, a system reminder, a caveat about local commands) and the daemon passes them on as text.
// Every view that prints a message (the card line, the session and the turn conversation, the hand-off rail) reads it
// through here, so a tag never reaches a page: a caveat is dropped, a command is one quiet line, a notice is a named event.
import { MSG } from './words-message';

export type Cleaned =
  /** A message in the person's or the model's own words, tags removed. */
  | { kind: 'say'; text: string }
  /** Bookkeeping worth one quiet line: `line` is the whole line, `label` and `text` its two halves. */
  | { kind: 'event'; label: string; text: string; line: string }
  /** Nothing to show. */
  | { kind: 'hidden' };

const ESC = String.fromCharCode(27);
const ANSI = new RegExp(`${ESC}\\[[0-9;]*[A-Za-z]`, 'g');
const OUTER = /^\s*<([a-zA-Z][\w-]*)>/;
const CAVEAT = /<local-command-caveat>[\s\S]*?<\/local-command-caveat>/g;
const REMINDER = /<system-reminder>[\s\S]*?<\/system-reminder>/g;

const squash = (text: string): string => text.replace(/\s+/g, ' ').trim();
const firstLine = (text: string): string => text.trim().split('\n', 1)[0]?.trim() ?? '';

function inner(text: string, tag: string): string | null {
  const found = new RegExp(`<${tag}>([\\s\\S]*?)</${tag}>`).exec(text)?.[1];
  return found === undefined ? null : found.trim();
}

const event = (label: string, text: string): Cleaned => ({ kind: 'event', label, text, line: text === '' ? label : `${label} ${text}` });
const named = (label: string, text: string): Cleaned => ({ kind: 'event', label, text, line: text === '' ? label : `${label} · ${text}` });

/** One message's text read for people. */
export function cleanMessage(raw: string): Cleaned {
  const text = raw.replace(ANSI, '');
  const tag = OUTER.exec(text)?.[1];
  switch (tag) {
    case 'local-command-caveat':
      return { kind: 'hidden' };
    case 'command-name':
    case 'command-message': {
      const name = inner(text, 'command-name') ?? inner(text, 'command-message') ?? '';
      const args = inner(text, 'command-args') ?? '';
      const shown = [name.startsWith('/') || name === '' ? name : `/${name}`, args].filter((part) => part !== '').join(' ');
      return shown === '' ? { kind: 'hidden' } : event(MSG.ran, shown);
    }
    case 'local-command-stdout':
    case 'local-command-stderr': {
      const output = squash(inner(text, tag) ?? '');
      return output === '' ? { kind: 'hidden' } : named(MSG.output, output);
    }
    case 'task-notification': {
      const said = squash(inner(text, 'summary') ?? inner(text, 'result') ?? text.replace(/<[^>]+>/g, ' '));
      return named(MSG.task, said);
    }
    case 'system-reminder': {
      const said = firstLine(inner(text, 'system-reminder') ?? '');
      return said === '' ? { kind: 'hidden' } : named(MSG.note, said);
    }
    default:
      break;
  }
  // Blocks the client appended to an otherwise ordinary message.
  const kept = text.replace(CAVEAT, '').replace(REMINDER, '').trim();
  return kept === '' ? { kind: 'hidden' } : { kind: 'say', text: kept };
}

/** A message as one plain line: its first line, or the whole quiet line of an event. Null when there is nothing to show. */
export function plainLine(raw: string): string | null {
  const cleaned = cleanMessage(raw);
  if (cleaned.kind === 'hidden') return null;
  return cleaned.kind === 'event' ? cleaned.line : firstLine(cleaned.text);
}

/** A message's words for a block that prints them whole; an event prints as its line. Null when there is nothing to show. */
export function readable(raw: string): string | null {
  const cleaned = cleanMessage(raw);
  if (cleaned.kind === 'hidden') return null;
  return cleaned.kind === 'event' ? cleaned.line : cleaned.text;
}
