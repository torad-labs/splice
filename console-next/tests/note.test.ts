// What the note box says before anyone types: the daemon's list of Claude Code versions it sends a note to (note_versions on the
// sessions payload), against the version a session registered.
import { describe, expect, test } from 'vitest';
import { noteRefusal } from '../src/lib/note';

const ADMITTED = ['2.1.282', '2.1.283', '2.1.284', '2.1.285', '2.1.286'];

describe('noteRefusal', () => {
  test('a version the daemon sends to has no refusal', () => {
    expect(noteRefusal('2.1.284', ADMITTED)).toBeNull();
    expect(noteRefusal('2.1.286', ADMITTED)).toBeNull();
  });
  test('a version it does not send to is refused in a sentence that names it, the versions it does send to, and the fix', () => {
    expect(noteRefusal('2.1.280', ADMITTED)).toBe(
      'This session runs Claude Code 2.1.280, and notes reach only 2.1.282 to 2.1.286. Relaunch it on Claude Code 2.1.286.',
    );
  });
  test('a non-Claude client is named without claiming a Claude Code version or prescribing a relaunch', () => {
    expect(noteRefusal('eli-telegram/0.2.0', ADMITTED)).toBe(
      'This session uses eli-telegram/0.2.0. Notes from this console reach Claude Code sessions only.',
    );
  });
  test('a session that registered no version is refused with the same fix', () => {
    expect(noteRefusal(null, ADMITTED)).toBe(
      'This session did not say which Claude Code it runs, so a note cannot be sent. Relaunch it on Claude Code 2.1.286.',
    );
  });
  test('one admitted version reads without a list', () => {
    expect(noteRefusal('2.1.9', ['2.1.286'])).toBe('This session runs Claude Code 2.1.9, and notes reach only 2.1.286. Relaunch it on Claude Code 2.1.286.');
  });
  test('a daemon that does not send the list claims nothing, and neither does an empty one', () => {
    expect(noteRefusal('2.1.1', undefined)).toBeNull();
    expect(noteRefusal(null, undefined)).toBeNull();
    expect(noteRefusal('2.1.1', [])).toBeNull();
  });
});
