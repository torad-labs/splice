// What each knob does, in one line of twelve words or fewer (docs/design/DESIGN.md section 10). The
// daemon sends a knob's value and where it came from; it does not say what the knob is for, and a
// camelCase key with a raw millisecond count answers none of the questions the operator opens
// Settings with. Each line is written from the knob's own entry in Knob.kt and the code that reads
// it, not from its name. tests/knob-copy.test.ts ties this table to Knob.kt in both directions, and
// the copy wall holds every line to one sentence.
export const KNOB_HELP: Record<string, string> = {
  // load limits
  maxInflight: 'Turns one head sends upstream at once; 0 means no limit.',
  maxQueued: 'Turns that can wait for a slot; past this, turns are refused.',
  maxRequestBytes: 'The largest request splice accepts from Claude Code.',
  requestReadTimeoutMs: 'How long splice waits for Claude Code to finish sending.',
  materializationPermits: "Requests converted to a provider's format at once, across all heads.",

  // retries and timeouts
  upstreamRetries: 'Tries per failing provider call, the first included, before the turn fails.',
  retryBackoffBaseMs: 'The wait before the first retry; each later retry waits longer.',
  retryBackoffCapMs: 'The longest any single retry waits.',
  retryBackoffJitterPct: 'Random spread in retry waits, so heads failing together retry apart.',
  upstreamTimeoutMs: 'The longest one turn can run before splice ends it.',
  firstByteTimeoutMs: 'How long a provider has to start answering before a liveness check.',
  streamIdleMs: 'Silence before splice checks the connection; a dead one is retried.',
  stallReanchorMs: 'Stall before splice resumes a cut answer, on heads that can.',

  // reasoning
  effort: 'Reasoning effort when Claude Code sets none.',
  summary: 'How much of its reasoning an OpenAI-style model sends back.',
  showReasoning: 'How reasoning shows in Claude Code: text, thinking blocks, or off.',
  replayReasoning: 'Resend earlier encrypted reasoning each turn; it shortens new reasoning.',
  mirrorReasoning: 'Always off; splice never writes its own reasoning summary.',
  progressLine: 'While a turn is silent, show how long splice has waited.',
  foldReasoningModels: 'Models asked to keep thinking when they cut reasoning short, comma-separated.',
  foldMaxContinue: 'Most times per turn splice asks a fold model to keep thinking.',
  foldMarkerText: 'The text splice sends to ask a model to keep thinking.',
  foldMaxTier: 'Largest n for which stopping at 518n − 2 tokens means cut.',

  // models and tools
  contextWindowOverride: "Report this context window for every model; empty uses the provider's.",
  toolSurface: 'Auto lets each provider load tools on demand; off sends every tool.',

  // usage and budgets
  quotaPoll: 'Subscription heads ask their provider for plan usage; off relies on turns.',
  quotaPollIntervalMs: 'How often subscription heads ask for plan usage, above a floor.',
  usageWarnPct: 'Warn when a plan window passes this share of its limit.',
  usageWarnTokens5h: 'Warn past this many tokens per head in five hours; 0 disables.',
  budgetDefaultAction: 'What a new budget does when reached: warn, or block turns.',

  // shared mcp servers
  mcpMaxServers: 'The most MCP servers splice runs for all sessions to share.',
  mcpIdleTimeoutMs: 'Stop a shared MCP server after it idles this long.',
  mcpRequestTimeoutMs: 'The longest one call to a shared MCP server can take.',
  mcpInitializeTimeoutMs: 'Time a shared MCP server has to start before splice gives up.',
  mcpSlice: 'The systemd slice for shared MCP servers; its memory limit caps all.',

  // recording and history
  trace: 'Record whole conversations for one head, secrets removed; use only to investigate.',
  traceRetentionDays: 'Days of trace files kept; older days are deleted.',
  traceMaxBodyChars: 'Longer bodies are cut in the trace and marked as cut.',
  wireTap: 'Recent provider requests kept in memory for splice wire; 0 keeps none.',
  activityRetentionDays: 'Days of session activity and message history kept for the console.',
  activityStoreHeads: 'Heads storing activity labels: * for all, empty for none, or keys.',
  perfArchiveRetentionDays: 'Days of past turn statistics kept; 0 keeps only the current file.',
  debug: 'Write detailed debug lines to the daemon log.',

  // chatgpt and grok logins
  port: 'The port every ChatGPT-login head listens on, over splice.toml.',
  pinnedModel: 'The model every ChatGPT-login head uses, over splice.toml.',
  chatgptApiBase: 'The ChatGPT backend address ChatGPT-login providers call.',
  codexAuthPath: 'Where the ChatGPT login is stored; splice login writes it.',
  grokPort: 'The port every Grok-login head listens on, over splice.toml.',
  grokModel: 'The model every Grok-login head uses, over splice.toml.',
  xaiApiBase: 'The xAI API address Grok-login providers call.',
  grokAuthPath: 'Where the Grok login is stored; splice login writes it.',
  authCacheMs: 'How long a head reuses a login before rereading it from disk.',

  // daemon
  controlPort: 'The port this console and the control API listen on.',
  supervisorUnit: 'The systemd user unit running splice; Restart daemon restarts it.',
  statuslineGitRoots: 'Colon-separated folders, beyond home and /tmp, where git branches show.',
};
