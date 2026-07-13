import { modelsStore } from './model/store';

export { fetchModels } from './api';
export const useModels = modelsStore.use;
