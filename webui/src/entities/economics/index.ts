import { economicsStore } from './model/store';

export { fetchEconomics, startEconomicsPolling } from './api';
export {
  sum, within, hitRate, writeRate, amplification, perTurn, toolSurface, wireDelta, burn, hourly,
  costOf,
} from './model/derive';
export type { Totals, Burn, CostRates } from './model/derive';
export const useEconomics = economicsStore.use;
