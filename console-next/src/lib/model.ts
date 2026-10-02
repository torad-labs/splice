// A head wears its provider's colour, the model colours of the site and the film. A head whose family
// is not mapped wears the neutral: on this console a colour names a provider, and any slot it took
// would name the wrong one. Its name, always beside its mark, tells it apart.
import type { ControlStatusPayload } from '../types/core';

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

/** Every page joins its head key to the daemon's vendor family. An unread or unnamed family stays neutral. */
export function colourFromRegistry(status: Pick<ControlStatusPayload, 'registry'> | undefined): (head: string) => ModelColour {
  const table = new Map((status?.registry ?? []).map((head) => [head.key, colourOf(head.family)] as const));
  return (head) => table.get(head) ?? 'none';
}
