import { configStore } from './model/store';

export { fetchConfig, applyConfigPatch } from './api';
export { diffPatch, parseConfigInput } from './model/store';
export type { DiffEntry } from './model/store';
export const useConfig = configStore.use;
