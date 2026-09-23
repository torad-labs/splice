import { modelsStore } from './model/store';

// `pendingOf` is NOT re-exported: it is a VALUE of @shared/api, and the boundary wall allows an
// entity to import shared/api for TYPES only (the client VALUE stays locked to api segments). The
// entity's own api segment imports it; nothing outside the slice needs it.
export { fetchModels, startModelsPolling, PENDING_MODELS } from './api';
export { slotTiers } from './model/derive';
export { MODEL_SLOTS } from './model/types';
export type {
  CatalogModel,
  HeadCatalog,
  ModelRates,
  ModelsPayload,
  ModelSlot,
  PendingRoute,
  SlotTier,
} from './model/types';
export const useModels = modelsStore.use;
