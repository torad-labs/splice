// What one line may say of a session: the newest thing it SAID (its last assistant words, first sentence) or DID (a tool call as the tool
// and what it was for). A card on Sessions and an item on Needs you read it here, so the two cannot tell different stories. The daemon
// never sends a tool's result or a system note as a session's activity; harness text that rides in a message is dropped here.
import type { SessionAsk, SessionLast, SessionRow } from '../types/sessions';
import { toolLabel } from './conversation';
import { cleanMessage } from './message';
import { MSG } from './words-message';
import { SW } from './words-sessions';

const ASK_USER = 'AskUserQuestion';

/** Claude Code's entrypoint for a session started at the command line. */
const CLI = 'cli';

/** Claude Code's `waitingFor` for a session held on a permission prompt rather than a question. */
const PERMISSION_PROMPT = 'permission prompt';

const SENTENCE = /^[\s\S]*?[.!?](?=\s|$)/;

/** The daemon cuts a message at this many characters without saying so. */
const CUT_AT = 150;

/** Words as a person reads them: the markdown marks a model types around code and emphasis are dropped, and a text the daemon cut
 *  short ends at its last whole word with an ellipsis, not in the middle of one. */
function plain(text: string): string {
  const words = text.replace(/\[([^\]]*)\]\([^)]*\)/g, '$1').replace(/`+|\*\*/g, '').replace(/\s+/g, ' ').trim();
  return words.length >= CUT_AT && !/[.!?…]$/.test(words) ? `${words.slice(0, words.lastIndexOf(' '))}…` : words;
}

/** A text's first sentence, whole lines squashed: the whole text when it has no full stop. */
export function firstSentence(text: string): string {
  const squashed = plain(text);
  return SENTENCE.exec(squashed)?.[0] ?? squashed;
}

/** A tool call as `<tool> · <what for>`, or the tool alone when the daemon found nothing a person would read (its text is then empty). */
function didText(tool: string, what: string): string {
  const target = firstSentence(what);
  return target === '' ? toolLabel(tool) : `${toolLabel(tool)} · ${target}`;
}

/** The newest message as what the session said or did, or null when there is nothing fit for a line: a slash command, a notice, a
 *  system note. The daemon sends the newest message that is not a tool result or a system note, and projects a call as what it was
 *  for (its description, else the name of what it touched), so a command or a path never arrives. */
export function cardSays(last: SessionLast | null | undefined): string | null {
  if (last === null || last === undefined) return null;
  if (last.role === 'assistant' && last.tool !== null) return last.tool === ASK_USER ? (firstSentence(last.text) || toolLabel(last.tool)) : didText(last.tool, last.text);
  const cleaned = cleanMessage(last.text);
  if (cleaned.kind === 'hidden') return null;
  if (cleaned.kind === 'event') return cleaned.label === MSG.output && /^compacted\b/i.test(cleaned.text) ? SW.compacted : null;
  const said = firstSentence(cleaned.text);
  if (said === '') return null;
  return last.role === 'user' ? `${SW.you}: ${said}` : said;
}

/** The question a waiting session asked: the last sentence of what it said that asks one. Null when it asked none, which is the
 *  state's own sentence to say ("Waiting for your answer"), never a system notice or a tool's output dressed as a question. */
export function waitingQuestion(last: SessionLast | null | undefined): string | null {
  if (last === null || last === undefined || last.role !== 'assistant') return null;
  if (last.tool === ASK_USER) return firstSentence(last.text) || null;
  if (last.tool !== null) return null;
  const cleaned = cleanMessage(last.text);
  if (cleaned.kind !== 'say') return null;
  const sentences = plain(cleaned.text).match(/[^.!?]*[.!?]+|[^.!?]+$/g) ?? [];
  const asked = sentences.map((sentence) => sentence.trim()).filter((sentence) => sentence.endsWith('?'));
  return asked.at(-1) ?? null;
}

/** Whether a waiting session is held on a permission prompt. Its transcript then holds no pending call, so nothing it last said, an
 *  ask it was already answered included, is what it waits on. */
export const onPermission = (row: Pick<SessionRow, 'waiting_for'>): boolean => row.waiting_for === PERMISSION_PROMPT;

/** The questions a waiting session asked through AskUserQuestion, as the daemon read them: none when it asked in plain words, called
 *  another tool, waits on a permission prompt, or the daemon is older than the field. */
export function waitingAsks(row: Pick<SessionRow, 'last' | 'waiting_for'>): SessionAsk[] {
  const last = row.last;
  return !onPermission(row) && last?.role === 'assistant' && last.tool === ASK_USER ? last.asks ?? [] : [];
}

/** Where a person answers a waiting session: its terminal when it was started at the command line, else the client it names. Null when
 *  the registry did not say, never a guessed place. */
export function answerWhere(row: Pick<SessionRow, 'entrypoint'>): string | null {
  const entry = row.entrypoint?.trim() ?? '';
  if (entry === '') return null;
  return entry === CLI ? SW.answerInTerminal : SW.answerIn(entry);
}
