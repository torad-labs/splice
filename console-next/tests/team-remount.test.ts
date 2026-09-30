// A team page left and re-entered must read today's chat and activity again on every mount: the cached answer, or a read still in
// flight from the last mount, may be from before a hand-off landed. An older day stays a single read.
import { QueryClient, QueryObserver } from '@tanstack/react-query';
import { afterEach, describe, expect, test, vi } from 'vitest';
import { heldBefore, rereadOnMount, teamActivityOptions, teamChatOptions } from '../src/api/teams';

const DAY = { from: 1, to: 2 } as never;
type Options = typeof teamChatOptions;

/** A network whose answers the test releases by hand, in any order. */
function heldNetwork() {
  const asked: { resolve: (body: unknown) => void }[] = [];
  vi.stubGlobal('fetch', () => new Promise<Response>((resolve) => {
    asked.push({ resolve: (body) => resolve(new Response(JSON.stringify(body), { status: 200, headers: { 'Content-Type': 'application/json' } })) });
  }));
  vi.stubGlobal('localStorage', undefined);
  return asked;
}
const client = () => new QueryClient({ defaultOptions: { queries: { retry: false, staleTime: 5_000 } } });

/** One mount of the page: what the hook does on its first render, on subscribing and in its mount effect. */
function mount(c: QueryClient, options: Options, live: boolean) {
  const { queryKey } = options('t', DAY, live);
  const held = heldBefore(c, queryKey);
  const observer = new QueryObserver(c, options('t', DAY, live));
  const unsubscribe = observer.subscribe(() => undefined);
  rereadOnMount(c, queryKey, held, live);
  return { observer, unsubscribe };
}
const settle = () => new Promise((resolve) => setTimeout(resolve, 20));

afterEach(() => vi.unstubAllGlobals());

describe('re-entering a team page', () => {
  test.each([['chat', teamChatOptions], ['activity', teamActivityOptions]] as const)(
    'today’s %s read in flight from the last mount is replaced by a read of its own, and that one wins',
    async (_name, options) => {
      const asked = heldNetwork();
      const c = client();
      const first = mount(c, options as Options, true);
      await settle();
      expect(asked).toHaveLength(1);
      first.unsubscribe();
      const second = mount(c, options as Options, true);
      await settle();
      expect(asked, 'the remount issued its own read').toHaveLength(2);
      asked[1]?.resolve({ fresh: true });
      await settle();
      asked[0]?.resolve({ stale: true });
      await settle();
      expect(second.observer.getCurrentResult().data).toEqual({ fresh: true });
      second.unsubscribe();
    },
  );
  test('a fresh cached answer is read again on mount too', async () => {
    const asked = heldNetwork();
    const c = client();
    const { queryKey } = teamChatOptions('t', DAY, true);
    c.setQueryData(queryKey, { messages: [] });
    const { unsubscribe } = mount(c, teamChatOptions as Options, true);
    await settle();
    expect(asked).toHaveLength(1);
    unsubscribe();
  });
  test('a first visit makes one read, not a read and its replacement', async () => {
    const asked = heldNetwork();
    const { unsubscribe } = mount(client(), teamChatOptions as Options, true);
    await settle();
    expect(asked).toHaveLength(1);
    unsubscribe();
  });
  test('an older day stays one read: a fresh cache is not read again', async () => {
    const asked = heldNetwork();
    const c = client();
    c.setQueryData(teamChatOptions('t', DAY, false).queryKey, { messages: [] });
    const { unsubscribe } = mount(c, teamChatOptions as Options, false);
    await settle();
    expect(asked).toHaveLength(0);
    unsubscribe();
  });
});
