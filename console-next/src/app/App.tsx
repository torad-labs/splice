import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { useEffect, useSyncExternalStore } from 'react';
import { RouterProvider } from 'react-router';
import { followEvents } from '../api/events';
import { currentKey, isLocked, subscribeLock } from '../api/client';
import { forgetRefusedReads } from './forget-refused';
import { TranscriptPrivacy } from './TranscriptPrivacy';
import { Unlock } from './Unlock';
import { router } from './routes';

const client = new QueryClient({
  defaultOptions: { queries: { retry: false, refetchOnWindowFocus: true, staleTime: 5_000 } },
});

export function App() {
  const locked = useSyncExternalStore(subscribeLock, isLocked);
  // The unlock screen says "rejected" only when a key was tried: with none held it just asks. Read at render, not once at
  // mount: a page that opened with no key and was then given a wrong one is locked again with a key held.
  const tried = currentKey() !== '';
  useEffect(() => {
    if (locked) void forgetRefusedReads(client);
  }, [locked]);
  useEffect(() => {
    if (locked) return;
    const stop = new AbortController();
    void followEvents(client, stop.signal);
    return () => stop.abort();
  }, [locked]);
  return (
    <QueryClientProvider client={client}>
      {locked ? <Unlock rejected={tried} /> : <><TranscriptPrivacy /><RouterProvider router={router} /></>}
    </QueryClientProvider>
  );
}
