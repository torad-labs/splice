// The first-hour plan chooser's labels and one-sentence help.
export const S = {
  allPlans: 'All plans',
  connect: 'Connect a plan',
  chatgpt: 'ChatGPT',
  grok: 'Grok',
  kimi: 'Kimi',
  muse: 'Muse',
  openrouter: 'OpenRouter key',
  local: 'Local model',
  other: 'Other providers',
} as const;

export const H = {
  intro: 'Choose a plan to sign in, then copy its command.',
  unavailable: 'Unavailable in this splice build.',
  chatgpt: 'Sign in with your ChatGPT plan.',
  grok: 'Sign in with your Grok plan.',
  kimi: 'Connect your Kimi plan.',
  muse: 'Connect your Muse plan.',
  openrouter: 'Use an OpenRouter API key.',
  local: 'Connect a local OpenAI-compatible endpoint.',
  other: 'Choose DeepSeek or another compatible provider from the catalogue.',
} as const;
