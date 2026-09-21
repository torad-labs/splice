import { control } from '@shared/api';
import { controlStatusStore } from '../model/store';

/** GET /api/status — server version + head registry. Fetched once at shell
 * mount (the wordmark subtitle); near-static, no poller needed. */
export async function fetchControlStatus(): Promise<void> {
  controlStatusStore.startLoading();
  try {
    controlStatusStore.setData(await control.status());
  } catch (err) {
    controlStatusStore.setError(err instanceof Error ? err.message : String(err));
  }
}
