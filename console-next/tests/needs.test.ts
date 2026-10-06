// The retained Health fix boundary after the old Needs page and its ranked derivation are retired.
import { describe, expect, test } from 'vitest';
import { doctorFixOf } from '../src/lib/needs';
import { H } from '../src/lib/words-needs';

describe('the live doctor fix', () => {
  test('a remedy the report masked is never offered to copy', () => {
    const remedy = 'add to your shell rc: export PATH="<redacted:path>"';
    expect(doctorFixOf(remedy, null, 'command')).toEqual({ kind: 'masked', command: remedy });
  });
  test('a fix the daemon can run retains its identifier and optional command fallback', () => {
    expect(doctorFixOf('splice install --all', 'install_all', 'command')).toEqual({ kind: 'doctor-fix', id: 'install_all', fallback: 'splice install --all' });
    expect(doctorFixOf(null, 'install_all', null)).toEqual({ kind: 'doctor-fix', id: 'install_all' });
  });
  test('a remedy without a console action keeps its honest CLI fallback', () => {
    expect(doctorFixOf('repair by hand', null, 'advice')).toEqual({ kind: 'open', href: '#/settings/health', label: 'Open Health', fallback: 'repair by hand' });
    expect(doctorFixOf(null, null, null)).toEqual({ kind: 'open', href: '#/settings/health', label: 'Open Health' });
  });
  test('only a fix the daemon marked as a command is offered to paste', () => {
    const remedy = 'rm ~/.local/bin/claudeor';
    expect(doctorFixOf(remedy, null, 'command')).toEqual({ kind: 'copy', command: remedy });
    expect(doctorFixOf(remedy, null, null)).toEqual({ kind: 'open', href: '#/settings/health', label: 'Open Health', fallback: remedy });
    expect(doctorFixOf('set system_prompt_mode = "append" to add your text', null, 'advice')).toMatchObject({ kind: 'open', fallback: 'set system_prompt_mode = "append" to add your text' });
  });
  test('a log remedy opens the requested command and tail, while restart uses the restart action', () => {
    const command = 'splice logs --head codex --tail 50';
    expect(doctorFixOf(command, null, 'command')).toEqual({ kind: 'open', href: '#/models/codex?tab=log&tail=50', label: 'Open log', fallback: command });
    expect(doctorFixOf(' splice restart ', null, 'command')).toEqual({ kind: 'restart-daemon' });
  });
});

test('the live Accounts warning keeps unknown reset and period facts explicit', () => {
  const text = H.nativeSpare('Synthetic login', 94, {
    seconds: 604800, used_percent: 94, reset_epoch_seconds: null, length_known: false,
  }, []);
  expect(text).toContain('Synthetic login is at 94%');
  expect(text).toContain('Its reset has not been reported.');
  expect(text).toContain('No other login can take over.');
  expect(text).not.toContain('weekly');
});
