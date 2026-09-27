import type { AddProfile } from '@entities/add';
import { H, S } from './strings';

export interface PlanChoice {
  id: string;
  label: string;
  description: string;
  profile: AddProfile | null;
}

const PLANS = [
  { id: 'codex', label: S.chatgpt, description: H.chatgpt },
  { id: 'grok', label: S.grok, description: H.grok },
  { id: 'kimi', label: S.kimi, description: H.kimi },
  { id: 'muse', label: S.muse, description: H.muse },
  { id: 'openrouter', label: S.openrouter, description: H.openrouter },
  { id: 'local', label: S.local, description: H.local },
] as const;

/** The first-hour choices, joined to what this daemon can actually add. */
export function planChoices(profiles: readonly AddProfile[]): PlanChoice[] {
  return PLANS.map((plan) => ({
    ...plan,
    profile: profiles.find((profile) => profile.name === plan.id) ?? null,
  }));
}
