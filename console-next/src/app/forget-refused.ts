import type { QueryClient } from '@tanstack/react-query';

/**
 * The reads a refused key leaves behind. A key the daemon refused locks the page, and its other reads were already in flight
 * or had failed with the same 401. They are the cache of the old key: one still in flight would settle after the next key
 * was accepted and land an error on a query the new key's page believes is already being read, and one that failed would
 * sit as an error nothing reads again. Cancelled and dropped while locked, every read the next unlock mounts starts fresh.
 */
export async function forgetRefusedReads(client: QueryClient): Promise<void> {
  await client.cancelQueries();
  client.removeQueries();
}
