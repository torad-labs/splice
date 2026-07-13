import { mgmt } from '@shared/api';
import { modelsStore } from '../model/store';

export async function fetchModels(): Promise<void> {
  modelsStore.startLoading();
  try {
    modelsStore.setData(await mgmt.models());
  } catch (err) {
    modelsStore.setError(err instanceof Error ? err.message : String(err));
  }
}
