// A head wears its provider's colour, the model colours of the site and the film. A head whose family
// is not mapped wears the neutral: on this console a colour names a provider, and any slot it took
// would name the wrong one. Its name, always beside its mark, tells it apart.
import { providerFamily } from './heads';

export type ModelColour = 'claude' | 'gpt' | 'grok' | 'kimi' | 'muse' | 'local' | 'router' | 'deepseek' | 'none';

const FAMILY: Readonly<Record<string, ModelColour>> = {
  anthropic: 'claude',
  openai: 'gpt',
  xai: 'grok',
  moonshot: 'kimi',
  meta: 'muse',
  local: 'local',
  openrouter: 'router',
  deepseek: 'deepseek',
};

export const colourOf = (family: string | null | undefined): ModelColour =>
  (family == null ? undefined : FAMILY[family]) ?? 'none';

const COLOUR_OF_FAMILY = {
  chatgpt: 'gpt', grok: 'grok', kimi: 'kimi', muse: 'muse', anthropic: 'claude', key: 'router', local: 'local',
} as const satisfies Record<ReturnType<typeof providerFamily>, ModelColour>;

/** The colour of a head, from its auth kind: the only provider signal the heads route carries. */
export const colourOfHead = (authKind: string): ModelColour => COLOUR_OF_FAMILY[providerFamily(authKind)];
