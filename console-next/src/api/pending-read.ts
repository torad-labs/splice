/** Management-only opt-in: an older daemon ignores it and returns its normal complete answer. */
export const PENDING_READ_HEADERS = { 'x-splice-read-pending': '1' } as const;
export const PENDING_READ_POLL_MS = 1000;

/** Keep a pending read's pinned window unchanged; abort clears the only timer and listener. */
export function awaitPendingRead(signal?: AbortSignal): Promise<void> {
  return new Promise((resolve, reject) => {
    signal?.throwIfAborted();
    const abort = (): void => {
      clearTimeout(timer);
      signal?.removeEventListener('abort', abort);
      reject(signal?.reason);
    };
    const timer = setTimeout(() => {
      signal?.removeEventListener('abort', abort);
      resolve();
    }, PENDING_READ_POLL_MS);
    signal?.addEventListener('abort', abort, { once: true });
  });
}
