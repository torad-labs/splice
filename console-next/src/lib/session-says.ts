// What one line may say of a session: the newest thing it SAID (its last assistant words, first sentence) or DID (a tool call as the tool
// and what it was for). A card on Sessions and an item on Needs you read it here, so the two cannot tell different stories. Never a
// tool's result, never a command or a path, never harness or system text: those have a place (the session's page) and this is not it.
import type { SessionLast } from '../types/sessions';
import { toolLabel } from './conversation';
import { cleanMessage } from './message';
import { MSG } from './words-message';
import { SW } from './words-sessions';

const KEY_OPENING = /^[[{\s]*"(?:[^"\\]|\\.)*"\s*:\s*/;
const ESCAPES: Readonly<Record<string, string>> = { n: ' ', t: ' ', r: ' ', '"': '"', '\\': '\\', '/': '/' };
const unescaped = (value: string): string => value.replace(/\\(.)/g, (_, char: string) => ESCAPES[char] ?? char);

/** A tool's input arrives as a cut-off JSON object; its first string value is the words (`{"questions":[{"question":"Which?"` says `Which?`). */
function wordsOf(text: string): string {
  if (!/^[[{]/.test(text)) return text;
  let rest = text;
  while (KEY_OPENING.test(rest)) rest = rest.replace(KEY_OPENING, '');
  const value = /^"((?:[^"\\]|\\.)*)/.exec(rest)?.[1];
  return value === undefined ? text : unescaped(value);
}

/** The string a key holds in a cut-off JSON object, or null when it is not there. A string the cut fell inside ends at its last
 *  whole word with an ellipsis. */
function valueOf(text: string, key: string): string | null {
  const found = new RegExp(`"${key}"\\s*:\\s*"((?:[^"\\\\]|\\\\.)*)("?)`).exec(text);
  const said = found?.[1] === undefined ? '' : unescaped(found[1]).trim();
  if (said === '') return null;
  return found?.[2] === '' && said.includes(' ') ? `${said.slice(0, said.lastIndexOf(' '))}…` : said;
}

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

/** What a call was for, in words: the description the model wrote, else the name of the file or thing it touched. Never a command or a path. */
function targetOf(text: string): string | null {
  const described = valueOf(text, 'description');
  if (described !== null) return firstSentence(described);
  for (const key of ['file_path', 'notebook_path', 'path']) {
    const path = valueOf(text, key);
    if (path !== null) return path.split('/').filter((part) => part !== '').at(-1) ?? null;
  }
  for (const key of ['pattern', 'query', 'url']) {
    const target = valueOf(text, key);
    if (target !== null) return key === 'url' ? (URL.canParse(target) ? new URL(target).hostname : null) : target;
  }
  return null;
}

/** The question an ask-the-user call carries, as a sentence. */
function questionOf(text: string): string | null {
  const asked = valueOf(text, 'question');
  return asked === null ? null : firstSentence(asked);
}

/** A tool call as `<tool> · <what for>`, or the tool alone when the call says nothing a person would read. */
function didText(tool: string, input: string): string {
  const target = targetOf(input);
  return target === null ? toolLabel(tool) : `${toolLabel(tool)} · ${target}`;
}

/** The newest message as what the session said or did, or null when there is nothing fit for a line: a tool's result, a system
 *  note, a slash command, a notice. The daemon marks a call as an assistant message that names its tool, and a result as a tool message. */
export function cardSays(last: SessionLast | null | undefined): string | null {
  if (last === null || last === undefined) return null;
  if (last.role === 'tool' || last.role === 'system') return null;
  if (last.role === 'assistant' && last.tool !== null) return last.tool === 'AskUserQuestion' ? questionOf(last.text) ?? toolLabel(last.tool) : didText(last.tool, last.text);
  const cleaned = cleanMessage(last.text);
  if (cleaned.kind === 'hidden') return null;
  if (cleaned.kind === 'event') return cleaned.label === MSG.output && /^compacted\b/i.test(cleaned.text) ? SW.compacted : null;
  const said = firstSentence(wordsOf(cleaned.text));
  if (said === '') return null;
  return last.role === 'user' ? `${SW.you}: ${said}` : said;
}

/** The question a waiting session asked: the last sentence of what it said that asks one. Null when it asked none, which is the
 *  state's own sentence to say ("Waiting for your answer"), never a system notice or a tool's output dressed as a question. */
export function waitingQuestion(last: SessionLast | null | undefined): string | null {
  if (last === null || last === undefined || last.role !== 'assistant') return null;
  if (last.tool === 'AskUserQuestion') return questionOf(last.text);
  if (last.tool !== null) return null;
  const cleaned = cleanMessage(last.text);
  if (cleaned.kind !== 'say') return null;
  const sentences = plain(wordsOf(cleaned.text)).match(/[^.!?]*[.!?]+|[^.!?]+$/g) ?? [];
  const asked = sentences.map((sentence) => sentence.trim()).filter((sentence) => sentence.endsWith('?'));
  return asked.at(-1) ?? null;
}
