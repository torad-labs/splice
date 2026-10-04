// The plan-instructions model and the upgrade rows' rules.
import { describe, expect, test } from 'vitest';
import { canSave, commandInstructionsOf, draftAfterSave, noteOf, topologyKeysOf, withCommandInstructions } from '../src/lib/command-instructions';
import { askOf, commandOf, rollbackTarget, versionLede } from '../src/lib/upgrade';
import type { UpgradePayload, UpgradeRun } from '../src/types/doctor';

describe('a plan’s instructions in splice.toml', () => {
  const held = { heads: { grok: { auth: 'x', system_prompt: 'Be brief.', system_prompt_mode: 'replace' }, kimi: { system_prompt_file: '/p.md' } } };
  test('reads the one source and the mode, append when the mode is absent', () => {
    expect(commandInstructionsOf(held, 'grok')).toEqual({ source: 'inline', text: 'Be brief.', mode: 'replace' });
    expect(commandInstructionsOf(held, 'kimi')).toEqual({ source: 'file', text: '/p.md', mode: 'append' });
    expect(commandInstructionsOf(held, 'none')).toBeNull();
    expect(commandInstructionsOf({}, 'grok')).toBeNull();
  });
  test('writing sets exactly one source and keeps the plan’s other keys and the input intact', () => {
    const before = JSON.stringify(held);
    const next = withCommandInstructions(held, 'kimi', { source: 'inline', text: 'Hi.', mode: 'strip' });
    expect(next).toEqual({ heads: { ...held.heads, kimi: { system_prompt: 'Hi.', system_prompt_mode: 'strip' } } });
    expect(JSON.stringify(held)).toBe(before);
  });
  test('removing takes all three keys and only them', () => {
    expect(withCommandInstructions(held, 'grok', null)).toEqual({ heads: { ...held.heads, grok: { auth: 'x' } } });
  });
  test('names what each choice does and the keys a save touches', () => {
    expect(noteOf(null)).toContain('stand');
    expect(noteOf({ source: 'inline', text: 'x', mode: 'replace' })).toContain('replaces');
    expect(topologyKeysOf('grok')).toEqual(['heads.grok.system_prompt', 'heads.grok.system_prompt_file', 'heads.grok.system_prompt_mode']);
  });
  test('a save waits for a change, for text, and for a file to be read', () => {
    const inline = { source: 'inline', text: 'x', mode: 'append' } as const;
    const file = { source: 'file', text: '/p.md', mode: 'append' } as const;
    expect(canSave(inline, false, false)).toBe(false);
    expect(canSave(inline, true, false)).toBe(true);
    expect(canSave({ ...inline, text: '  ' }, true, false)).toBe(false);
    expect(canSave(file, true, false)).toBe(false);
    expect(canSave(file, true, true)).toBe(true);
    expect(canSave(null, true, false)).toBe(true);
  });
});

describe('the upgrade rows', () => {
  const status = (over: Partial<UpgradePayload> = {}): UpgradePayload => ({
    installed: '0.3.2', latest: '0.4.0', latest_basis: 'measured', rollback_target: '0.3.1', rollback_basis: 'measured', rollback_unavailable_reason: null, checked_at_epoch_millis: 1, ...over,
  });
  test('a blank release asks for the latest, anything else goes as typed and trimmed', () => {
    expect(askOf('  ')).toEqual({});
    expect(askOf(' 0.4.0 ')).toEqual({ to: '0.4.0' });
  });
  test('a rollback is offered only when the daemon looked and found a release on disk', () => {
    expect(rollbackTarget(status())).toBe('0.3.1');
    expect(rollbackTarget(status({ rollback_target: null }))).toBeNull();
    expect(rollbackTarget(status({ rollback_basis: 'unavailable' }))).toBeNull();
  });
  test('an unchecked release never reads as nothing newer', () => {
    expect(versionLede(status())).toBe('Running 0.3.2. The newest release is 0.4.0.');
    expect(versionLede(status({ latest: null }))).toBe('Running 0.3.2. There is no newer release.');
    expect(versionLede(status({ latest: null, latest_basis: 'unavailable' }))).toBe('Running 0.3.2. splice has not checked for a newer release.');
  });
  test('a run reads as the command a person would type', () => {
    const run: UpgradeRun = { id: 'r', args: ['upgrade', '--rollback'], state: 'running', started_at_epoch_millis: 1, exit_code: null, output: [] };
    expect(commandOf(run)).toBe('splice upgrade --rollback');
  });
});

describe('a finished save and the draft', () => {
  const saved = { source: 'inline', text: 'Be brief.', mode: 'append' } as const;
  test('the draft that was saved is cleared, and one changed while the save ran is kept', () => {
    expect(draftAfterSave({ head: 'a', value: saved }, 'a', saved)).toBeNull();
    const newer = { head: 'a', value: { ...saved, mode: 'replace' } } as const;
    expect(draftAfterSave(newer, 'a', saved)).toBe(newer);
  });
  test('a draft for another plan, or none, is left as it is', () => {
    const other = { head: 'b', value: saved };
    expect(draftAfterSave(other, 'a', saved)).toBe(other);
    expect(draftAfterSave(null, 'a', saved)).toBeNull();
  });
});
