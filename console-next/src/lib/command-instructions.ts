// A plan's standing instructions in splice.toml: `heads.<plan>.system_prompt` (written here) or `system_prompt_file` (read from a
// file), with `system_prompt_mode` saying how they meet the client's own. Pure over the parsed topology; nothing mutates its input.
import { INSTRUCTION_NOTE } from './words-instructions';

type Table = Record<string, unknown>;

export type InstructionMode = 'append' | 'replace' | 'strip';
export type InstructionSource = 'inline' | 'file';
export interface CommandInstruction {
  source: InstructionSource;
  text: string;
  mode: InstructionMode;
}

const tableOf = (value: unknown): Table => (typeof value === 'object' && value !== null && !Array.isArray(value) ? (value as Table) : {});

/** A plan has one source. The absent mode is append; "no instructions" is the absence of all three keys, not a mode. */
export function commandInstructionsOf(topology: Table, head: string): CommandInstruction | null {
  const entry = tableOf(tableOf(topology['heads'])[head]);
  const file = entry['system_prompt_file'];
  const inline = entry['system_prompt'];
  const mode = entry['system_prompt_mode'];
  const held = { mode: mode === 'replace' || mode === 'strip' ? mode : 'append' } as const;
  if (typeof file === 'string') return { source: 'file', text: file, ...held };
  if (typeof inline === 'string') return { source: 'inline', text: inline, ...held };
  return null;
}

/** [topology] with exactly one source set on the plan, or all three keys removed to give the client's instructions back. */
export function withCommandInstructions(topology: Table, head: string, next: CommandInstruction | null): Table {
  const heads = tableOf(topology['heads']);
  const kept = Object.fromEntries(Object.entries(tableOf(heads[head])).filter(([key]) => !['system_prompt', 'system_prompt_file', 'system_prompt_mode'].includes(key)));
  const edited = next === null ? kept : { ...kept, [next.source === 'file' ? 'system_prompt_file' : 'system_prompt']: next.text, system_prompt_mode: next.mode };
  return { ...topology, heads: { ...heads, [head]: edited } };
}

/** The sentence that says what the current choice does to the client's instructions. */
export const noteOf = (current: CommandInstruction | null): string => INSTRUCTION_NOTE[current === null ? 'none' : current.mode];

/** What the editor holds once a save of `saved` for `head` is done: nothing when the draft is what was saved, and the draft itself when the
 *  operator changed it while the save was in flight, so a newer edit is never discarded by an older save finishing. */
export function draftAfterSave<T extends { head: string; value: CommandInstruction | null }>(draft: T | null, head: string, saved: CommandInstruction | null): T | null {
  return draft !== null && draft.head === head && JSON.stringify(draft.value) === JSON.stringify(saved) ? null : draft;
}

/** A save is held while a file is unread or refused, and while the text is blank. */
export function canSave(current: CommandInstruction | null, changed: boolean, fileReady: boolean): boolean {
  return changed && (current === null || (current.text.trim() !== '' && (current.source === 'inline' || fileReady)));
}

export const topologyKeysOf = (head: string): string[] => [`heads.${head}.system_prompt`, `heads.${head}.system_prompt_file`, `heads.${head}.system_prompt_mode`];
