// What the models tab says about a rate and about how a model stands against its provider's published list.
import type { RosterVerdict } from '../types/models';

export const M = {
  rate: (input: string, output: string): string => `${input} in, ${output} out per million tokens`,
  cached: (price: string): string => `${price} for a cached token`,
  noRate: 'No price declared',
  commandDefault: 'Command default',
  enterModelId: 'Enter a model ID',
  customModelId: 'Custom model ID',
  verdict: {
    served: 'Served',
    capped: 'Served with a smaller window',
    'over-ceiling': 'Window is larger than the provider allows',
    unserved: 'The provider no longer serves it',
    new: 'Served, not on the roster',
    excluded: 'Served, kept off the roster',
  } as const satisfies Record<RosterVerdict, string>,
} as const;
