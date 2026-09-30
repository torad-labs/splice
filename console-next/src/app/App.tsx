import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { useEffect, useState, useSyncExternalStore } from 'react';
import { RouterProvider } from 'react-router';
import { followEvents } from '../api/events';
import { currentKey, isLocked, subscribeLock } from '../api/client';
import { Unlock } from './Unlock';
import { router } from './routes';

const client = new QueryClient({
  defaultOptions: { queries: { retry: false, refetchOnWindowFocus: true, staleTime: 5_000 } },
});

export function App() {
  const locked = useSyncExternalStore(subscribeLock, isLocked);
  // The unlock screen says "rejected" only when a key was tried: with none held it just asks.
  const [tried] = useState(() => currentKey() !== '');
  useEffect(() => {
    if (locked) return;
    const stop = new AbortController();
    void followEvents(client, stop.signal);
    return () => stop.abort();
  }, [locked]);
  return (
    <QueryClientProvider client={client}>
      {locked ? <Unlock rejected={tried} /> : <RouterProvider router={router} />}
    </QueryClientProvider>
  );
}
