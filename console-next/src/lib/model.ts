// A head wears its provider's colour, the model colours of the site and the film. A head whose family
// is not mapped wears the neutral: on this console a colour names a provider, and any slot it took
// would name the wrong one. Its name, always beside its mark, tells it apart.
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
