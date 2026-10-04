// Every outcome tag the daemon can write reads as its own word in the Turns list, never a bare "Failed". The denominator is PARSED
// FROM THE KOTLIN SOURCE at test time (the tag enum, the typed failures, every `OutcomeTags.error(...)` call and the conn-reset
// constant), so a tag added there with no word fails here BY NAME.
import { describe, expect, test } from 'vitest';
import { outcomeOf } from '../src/lib/turns-page';
import { OUTCOME_WORD } from '../src/lib/words-turns';
import { kotlinMain, read } from './support/coverage';

/** Fixed tags, from `NAME("wire")` entries of the OutcomeTag enum. */
export function parseFixedTags(source: string): string[] {
  const body = source.slice(source.indexOf('enum class OutcomeTag'), source.indexOf('\n}', source.indexOf('enum class OutcomeTag')));
  return [...body.matchAll(/^\s+[A-Z_]+\("([^"]+)"\)/gm)].map((match) => match[1] ?? '');
}

/** Typed failures, `failure:<wire>` for each ErrorType. */
export function parseFailureTags(source: string): string[] {
  const start = source.indexOf('enum class ErrorType');
  const body = source.slice(start, source.indexOf('\n}', start));
  return [...body.matchAll(/[A-Z_]+\("([a-z_]+)"\)/g)].map((match) => `failure:${match[1] ?? ''}`);
}

/** Locally-classified endings: `OutcomeTags.error("kind")` anywhere in main code, and the conn-reset kind constant. */
export function parseErrorTags(sources: readonly string[]): string[] {
  const kinds = sources.flatMap((source) => [
    ...[...source.matchAll(/OutcomeTags\.error\("([^"]+)"\)/g)].map((match) => match[1] ?? ''),
    ...[...source.matchAll(/CONN_RESET_KIND: String = "([^"]+)"/g)].map((match) => match[1] ?? ''),
  ]);
  return kinds.map((kind) => `error:${kind}`);
}

const main = kotlinMain.map(read);
const tags = [...new Set([
  ...parseFixedTags(read('core/src/main/kotlin/splice/core/perf/OutcomeTag.kt')),
  ...parseFailureTags(read('core/src/main/kotlin/splice/core/turn/TurnOutcome.kt')),
  ...parseErrorTags(main),
])].filter((tag) => tag !== 'ok');

describe('outcome words', () => {
  test('a proven refused runtime carries its actual port while a reset stays a lost connection', () => {
    expect(outcomeOf('error:conn-reset', 8123)).toMatchObject({ word: "Couldn't reach its runtime on :8123", failed: true });
    expect(outcomeOf('error:conn-reset')).toMatchObject({ word: 'Connection lost', failed: true });
    expect(outcomeOf('error:conn-reset', -1)).toMatchObject({ word: 'Connection lost', failed: true });
    expect(outcomeOf('error:conn-reset', 65536)).toMatchObject({ word: 'Connection lost', failed: true });
    expect(outcomeOf('ok', 8123)).toMatchObject({ word: 'Done', failed: false });
  });

  test('the denominator is parsed from the source', () => {
    expect(tags.length, `parsed ${tags.length} tags: ${tags.join(' ')}`).toBeGreaterThanOrEqual(25);
    expect(tags).toEqual(expect.arrayContaining(['error:conn-reset', 'error:stopped', 'error:compaction-preflight-compact-overflow', 'failure:api_error']));
  });

  test('every tag the daemon writes has its own word', () => {
    expect(tags.filter((tag) => OUTCOME_WORD[tag] === undefined || OUTCOME_WORD[tag] === 'Failed')).toEqual([]);
  });

  test('the wall fails by name on new tags in each Kotlin source shape', () => {
    const fixed = parseFixedTags('enum class OutcomeTag {\n    NEW("error:a-new-ending"),\n}');
    const typed = parseFailureTags('enum class ErrorType {\n    NEW("new_error"),\n}');
    const local = parseErrorTags(['OutcomeTags.error("new-local")', 'CONN_RESET_KIND: String = "new-reset"']);
    expect([...fixed, ...typed, ...local].filter((tag) => OUTCOME_WORD[tag] === undefined)).toEqual([
      'error:a-new-ending', 'failure:new_error', 'error:new-local', 'error:new-reset',
    ]);
  });

  test('a tag this console has never heard of is still a failure and says so plainly', () => {
    expect(outcomeOf('error:a-new-ending')).toEqual({ word: 'Failed', tone: 'stuck', failed: true });
  });

  test('a stop the operator asked for is not a failure; a lost connection is', () => {
    expect(outcomeOf('error:stopped')).toMatchObject({ word: 'Stopped', failed: false });
    expect(outcomeOf('error:conn-reset')).toMatchObject({ word: 'Connection lost', failed: true });
    expect(outcomeOf('error:compaction-preflight-compact-overflow')).toMatchObject({ word: 'Too large to compact', failed: true });
  });
});
