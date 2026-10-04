// The Transcript view setting turning off empties what was read from a transcript, already on screen, without a reload.
import { QueryClient } from '@tanstack/react-query';
import { describe, expect, test } from 'vitest';
import { TRANSCRIPT_READS, followTranscriptView } from '../src/app/TranscriptPrivacy';
import { keys } from '../src/api/queries';

const seeded = (): QueryClient => {
  const client = new QueryClient();
  for (const key of TRANSCRIPT_READS) client.setQueryData([...key, 'a read'], { text: 'what the prompt said' });
  client.setQueryData([...keys.heads, '/api/heads'], { heads: [] });
  return client;
};

describe('following the Transcript view setting', () => {
  test('every read of transcript text is the gated one: conversation, session transcript, hand-offs and team messages', () => {
    expect(TRANSCRIPT_READS.map((key) => key[0])).toEqual(['conversation', 'transcript', 'edges', 'team-panels']);
  });

  test('turning it off leaves none of them holding text, and leaves every other read alone', () => {
    const client = seeded();
    followTranscriptView(client, false);
    for (const key of TRANSCRIPT_READS) expect(client.getQueryData([...key, 'a read']), key[0]).toBeUndefined();
    expect(client.getQueryData([...keys.heads, '/api/heads'])).toEqual({ heads: [] });
  });

  test('turning it on reads them again without dropping what is shown meanwhile', () => {
    const client = seeded();
    followTranscriptView(client, true);
    for (const key of TRANSCRIPT_READS) {
      expect(client.getQueryState([...key, 'a read'])?.isInvalidated, key[0]).toBe(true);
      expect(client.getQueryData([...key, 'a read']), key[0]).toEqual({ text: 'what the prompt said' });
    }
  });
});
