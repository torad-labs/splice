import { describe, expect, test } from 'vitest';
import { inputView, languageOf, lineDiff, outputView, partialObject } from '../src/lib/tool-view';

const ops = (before: string, after: string): string[] => lineDiff(before, after).map((line) => `${line.op === 'same' ? ' ' : line.op === 'del' ? '-' : '+'}${line.text}`);

describe('an edit as a line diff', () => {
  test('shared lines are context and only the changed lines are removed and added', () => {
    expect(ops('a\nb\nc', 'a\nB\nc')).toEqual([' a', '-b', '+B', ' c']);
  });
  test('a line kept between two changes stays context', () => {
    expect(ops('x\nkeep\ny', 'X\nkeep\nY')).toEqual(['-x', '+X', ' keep', '-y', '+Y']);
  });
  test('an insertion removes nothing, and a new file adds every line', () => {
    expect(ops('a\nc', 'a\nb\nc')).toEqual([' a', '+b', ' c']);
    expect(ops('', 'new\n')).toEqual(['+new']);
  });
});

describe('an input the daemon cut short', () => {
  test('an open string is closed and a key left with no value is dropped', () => {
    expect(partialObject('{"command":"echo \\"hi\\"\\nls","descr')).toEqual({ command: 'echo "hi"\nls' });
    expect(partialObject('{"a":"x","b":')).toEqual({ a: 'x' });
    expect(partialObject('{"a":tr')).toEqual({});
  });
  test('an open list or object is closed, and a dangling escape is dropped', () => {
    expect(partialObject('{"a":[1,2')).toEqual({ a: [1, 2] });
    expect(partialObject('{"a":{"b":1,"c')).toEqual({ a: { b: 1 } });
    expect(partialObject('{"a":"line\\')).toEqual({ a: 'line' });
  });
  test('text that is not the start of an object is not repaired', () => {
    expect(partialObject('[1,2')).toBeNull();
    expect(partialObject('plain words')).toBeNull();
  });
  test('the view says the input was cut, and shows the repaired command', () => {
    const text = '{"command":"npm test","run_in_background":tr';
    expect(inputView('Bash', text, text)).toEqual({ view: { kind: 'command', description: null, command: 'npm test', extra: [] }, cut: true });
  });
});

describe('a result', () => {
  test('a read keeps its line numbers apart from the code', () => {
    expect(outputView('Read', '  7\tval a = 1\n  8\tval b = 2', { file_path: '/r/A.kt' })).toEqual({ kind: 'numbered', numbers: [7, 8], code: 'val a = 1\nval b = 2', language: 'kt', rest: '' });
  });
  test('a shell result that is JSON stays the program\'s text, and another tool\'s JSON is labelled values', () => {
    expect(outputView('Bash', '{"a":1}', {})).toEqual({ kind: 'text', text: '{"a":1}' });
    expect(outputView('TaskStop', '{"message":"stopped"}', {})).toEqual({ kind: 'fields', fields: [{ key: 'message', value: { kind: 'text', text: 'stopped' } }] });
  });
  test('a file\'s language is its extension as written, lower-cased, and a file with none has none', () => {
    expect(languageOf('/r/src/App.tsx')).toBe('tsx');
    expect(languageOf('/r/build.gradle.kts')).toBe('kts');
    expect(languageOf('/r/Makefile')).toBeNull();
    expect(languageOf('/r/.env')).toBeNull();
  });
});
