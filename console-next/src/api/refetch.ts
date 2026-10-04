// Reading data again after a write. A mutation stays pending until its onSettled promise settles, so a hook that RETURNS its
// refetches holds its own busy state, and any confirmation that reads the mutation's result, until the slowest read answers
// (a slow /health kept "Writing…" on screen after the PUT had been answered). The two names make the choice visible at each call:
// `refetch` lets the confirmation stand on the write's own answer; `awaitRefetch` waits, because the refreshed data IS the done
// state (the account switched, the census after a delete). The wall webui-mutation-hooks-never-return-refetch allows no other form.
import type { QueryClient } from '@tanstack/react-query';

type Keys = readonly (readonly string[])[];

/** Read these queries again and move on: the mutation is done when the write answered. */
export function refetch(client: QueryClient, keys: Keys): void {
  void Promise.all(keys.map((key) => client.invalidateQueries({ queryKey: [...key] })));
}

/** Read these queries again and wait: the mutation is done when the page shows what the write changed. */
export function awaitRefetch(client: QueryClient, keys: Keys): Promise<unknown> {
  return Promise.all(keys.map((key) => client.invalidateQueries({ queryKey: [...key] })));
}
