import { economicsStore } from './model/store';

export { fetchEconomics, startEconomicsPolling } from './api';
export {
  sum, within, hitRate, amplification, perTurn, toolSurface, wireDelta, burn, hourly,
} from './model/derive';
export type { Totals, Burn } from './model/derive';
export const useEconomics = economicsStore.use;
