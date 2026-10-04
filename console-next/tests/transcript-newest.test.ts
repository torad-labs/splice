// A session opens at its NEWEST messages: the first read asks the daemon for the end of the conversation, the next read asks
// for what lies before the page it already holds, and the view is the pages stitched oldest first.
import { QueryClient } from '@tanstack/react-query';
import { afterEach, describe, expect, test, vi } from 'vitest';
import { transcriptOptions, transcriptView } from '../src/api/sessions';
import type { TranscriptMessage, TranscriptRead } from '../src/types/sessions';

const message = (index: number, text: string): TranscriptMessage => ({ index, role: 'user', ts: 0, text });
const page = (messages: TranscriptMessage[], earlier: string | null): TranscriptRead => ({
  session_id: 's', path: '/p', messages, next: null, earlier, unparseable_lines: 0, sidechain_records: 0, skipped_records: {},
}) as unknown as TranscriptRead;

afterEach(() => vi.unstubAllGlobals());

function network(pages: Record<string, TranscriptRead>) {
  const asked: string[] = [];
  vi.stubGlobal('fetch', (url: string) => {
    asked.push(url);
    const before = new URL(url, 'http://x').searchParams.get('before') ?? '';
    return Promise.resolve(new Response(JSON.stringify(pages[before]), { status: 200, headers: { 'Content-Type': 'application/json' } }));
  });
  vi.stubGlobal('localStorage', undefined);
  return asked;
}

describe('the conversation a session opens on', () => {
  test('the first read is the end of the conversation and each next read is the cursor the last page named', async () => {
    const asked = network({
      end: page([message(3000, 'newest')], '2000'),
      '2000': page([message(1000, 'older')], null),
    });
    const client = new QueryClient();
    const options = transcriptOptions('sess-1');
    await client.fetchInfiniteQuery({ ...options, pages: 3 });
    expect(asked).toEqual([
      '/api/sessions/sess-1/transcript?before=end&limit=100',
      '/api/sessions/sess-1/transcript?before=2000&limit=100',
    ]);
  });

  test('the view is the pages oldest first, and there is an earlier page until one names none', () => {
    const newest = page([message(3000, 'c'), message(3001, 'd')], '2000');
    const older = page([message(1000, 'a'), message(1001, 'b')], null);
    const view = transcriptView([newest, older]);
    expect(view?.kind === 'messages' && view.messages.map((m) => m.text)).toEqual(['a', 'b', 'c', 'd']);
    const opts = transcriptOptions('s');
    expect(opts.getNextPageParam(newest, [newest], 'end', [])).toBe('2000');
    expect(opts.getNextPageParam(older, [newest, older], '2000', [])).toBeUndefined();
  });

  test('a missing transcript and a switched-off view stay states, never empty conversations', () => {
    expect(transcriptView([{ missing: ['/a'] }])).toEqual({ kind: 'missing', searched: ['/a'] });
    expect(transcriptView([{ state: 'off', reason: 'no' } as TranscriptRead])).toEqual({ kind: 'off', reason: 'no' });
  });
});
