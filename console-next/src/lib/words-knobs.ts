// The words the knob rack prints, ported from the old console's knob-form strings.ts and copy.ts: the
// group names, each knob's name as the operator reads it, and one line of help per knob (twelve words
// or fewer, one sentence). Copy lives here, never in a component or in lib/knobs.ts, so the copy wall
// reads one place. The daemon sends a knob's value and where it came from; it does not say what the
// knob is for, and a camelCase key with a raw millisecond count answers none of the questions the
// operator opens Settings with. Each line is written from the knob's own entry in Knob.kt and the code
// that reads it, not from its name. tests/knobs.test.ts ties all three tables to Knob.kt in both
// directions.

/** What each group of knobs is about, in the order the rack shows them. */
export const GROUP_LABELS = {
  limits: 'Load limits',
  retries: 'Retries and timeouts',
  reasoning: 'Reasoning',
  models: 'Models and tools',
  usage: 'Usage and budgets',
  mcp: 'Shared MCP servers',
  records: 'Recording and history',
  logins: 'ChatGPT and Grok',
  daemon: 'splice',
} as const;

/** Every knob's name as the operator reads it. The key stays visible beside it, small, because
 *  it is what splice.toml, the env and the CLI spell. tests/knobs.test.ts ties this table to Knob.kt
 *  in both directions. */
export const KNOB_LABELS = {
  activityRetentionDays: 'Message history',
  activityStoreHeads: 'Activity commands',
  messageEdges: 'Message edges',
  authCacheMs: 'Login cache',
  budgetDefaultAction: 'Budget default',
  chatgptApiBase: 'ChatGPT API URL',
  codexAuthPath: 'ChatGPT login file',
  contextWindowOverride: 'Context window',
  controlPort: 'Console port',
  debug: 'Debug logging',
  effort: 'Reasoning effort',
  firstByteTimeoutMs: 'First output wait',
  foldMarkerText: 'Fold marker',
  foldMaxContinue: 'Fold rounds',
  foldMaxTier: 'Fold tier cap',
  foldReasoningModels: 'Fold models',
  grokAuthPath: 'Grok login file',
  grokModel: 'Grok model',
  grokPort: 'Grok command port',
  materializationHeapBytes: 'Request memory budget',
  maxInflight: 'Concurrent turns',
  maxQueued: 'Queued turns',
  maxRequestBytes: 'Max request size',
  mcpIdleTimeoutMs: 'Idle shutdown',
  mcpInitializeTimeoutMs: 'Startup time limit',
  mcpMaxServers: 'Max servers',
  mcpRequestTimeoutMs: 'Call time limit',
  mcpSlice: 'Systemd slice',
  mirrorReasoning: 'Mirror reasoning',
  perfArchiveRetentionDays: 'Turn stats history',
  pinnedModel: 'ChatGPT model',
  port: 'ChatGPT command port',
  progressLine: 'Progress line',
  quotaPoll: 'Plan usage polling',
  quotaPollIntervalMs: 'Usage poll interval',
  replayReasoning: 'Replay reasoning',
  requestReadTimeoutMs: 'Request read limit',
  retryBackoffBaseMs: 'First retry delay',
  retryBackoffCapMs: 'Longest retry delay',
  retryBackoffJitterPct: 'Retry jitter',
  showReasoning: 'Show reasoning',
  stallReanchorMs: 'Stall resume',
  statuslineGitRoots: 'Git roots',
  streamIdleMs: 'Silence check',
  summary: 'Reasoning summary',
  supervisorUnit: 'Systemd unit',
  toolSurface: 'Deferred tools',
  trace: 'Full trace',
  traceMaxBodyChars: 'Trace body cap',
  traceRetentionDays: 'Trace history',
  transcriptView: 'Transcript view',
  upstreamRetries: 'Retry attempts',
  upstreamTimeoutMs: 'Turn time limit',
  usageWarnPct: 'Usage warning',
  usageWarnTokens5h: '5h token warning',
  wireTap: 'Wire tap',
  xaiApiBase: 'Grok API URL',
} as const;

/** One line of help per knob, keyed by the knob's key. */
export const KNOB_HELP: Record<string, string> = {
  // load limits
  maxInflight: 'Turns one command sends upstream at once; 0 means no limit.',
  maxQueued: 'Turns that can wait for a slot; past this, turns are refused.',
  maxRequestBytes: 'The largest request splice accepts from Claude Code.',
  requestReadTimeoutMs: 'How long splice waits for Claude Code to finish sending.',
  materializationHeapBytes: 'Heap bytes for requests across all commands. 0 uses spare heap; larger bodies reserve more.',

  // retries and timeouts
  upstreamRetries: 'Tries per failing provider call, the first included, before the turn fails.',
  retryBackoffBaseMs: 'The wait before the first retry; each later retry waits longer.',
  retryBackoffCapMs: 'The longest any single retry waits.',
  retryBackoffJitterPct: 'Random retry delays keep failing commands from retrying together.',
  upstreamTimeoutMs: 'The longest one turn can run before splice ends it.',
  firstByteTimeoutMs: 'How long a provider has to start answering before a liveness check.',
  streamIdleMs: 'Silence before splice checks the connection; a dead one is retried.',
  stallReanchorMs: 'Stall before splice resumes a cut answer, on supported commands.',

  // reasoning
  effort: 'Reasoning effort when Claude Code sets none.',
  summary: 'How much of its reasoning an OpenAI-style model sends back.',
  showReasoning: 'How reasoning shows in Claude Code: text, thinking blocks, or off.',
  replayReasoning: 'Resend earlier encrypted reasoning each turn; it shortens new reasoning.',
  mirrorReasoning: 'Always off; splice never writes its own reasoning summary.',
  progressLine: 'While a turn is silent, show how long splice has waited.',
  foldReasoningModels: 'Models asked to keep thinking when they cut reasoning short.',
  foldMaxContinue: 'Most times per turn splice asks a fold model to keep thinking.',
  foldMarkerText: 'The text splice sends to ask a model to keep thinking.',
  foldMaxTier: 'Largest n for which stopping at 518n − 2 tokens means cut.',

  // models and tools
  contextWindowOverride: "Report this context window for every model; empty uses the provider's.",
  toolSurface: 'Auto lets each provider load tools on demand; off sends every tool.',

  // usage and budgets
  quotaPoll: 'Subscription plans ask their provider for usage; off relies on turns.',
  quotaPollIntervalMs: 'How often subscription plans ask for usage, above a floor.',
  usageWarnPct: 'Warn when a command’s window passes this share of its limit.',
  usageWarnTokens5h: 'Warn past this many tokens per command in five hours; 0 disables.',
  budgetDefaultAction: 'What a new budget does when reached: warn, or block turns.',

  // shared mcp servers
  mcpMaxServers: 'The most MCP servers splice runs for all sessions to share.',
  mcpIdleTimeoutMs: 'Stop a shared MCP server after it idles this long.',
  mcpRequestTimeoutMs: 'The longest one call to a shared MCP server can take.',
  mcpInitializeTimeoutMs: 'Time a shared MCP server has to start before splice gives up.',
  mcpSlice: 'The systemd slice for shared MCP servers; its memory limit caps all.',

  // recording and history
  transcriptView: "Read Claude Code's already-saved redacted conversation; off hides it everywhere.",
  trace: 'Record whole conversations for one command, secrets removed; investigate only.',
  traceRetentionDays: 'Days of trace files kept; older days are deleted.',
  traceMaxBodyChars: 'Longer bodies are cut in the trace and marked as cut.',
  wireTap: 'Recent provider requests kept in memory for splice wire; 0 keeps none.',
  activityRetentionDays: 'Message history days, at least two; activity labels keep today and yesterday.',
  activityStoreHeads: 'Commands storing activity labels: * for all, empty for none, or keys.',
  messageEdges: 'Keep conversation hand-off metadata; off stops new edges after restart.',
  perfArchiveRetentionDays: 'Days of past turn statistics kept; 0 keeps only the current file.',
  debug: 'Write detailed debug lines to the splice log.',

  // chatgpt and grok logins
  port: 'The port every ChatGPT command listens on, over splice.toml.',
  pinnedModel: 'The model every ChatGPT command uses, over splice.toml.',
  chatgptApiBase: 'The ChatGPT API address this provider calls.',
  codexAuthPath: 'Where the ChatGPT login is stored; splice login writes it.',
  grokPort: 'The port every Grok command listens on, over splice.toml.',
  grokModel: 'The model every Grok command uses, over splice.toml.',
  xaiApiBase: 'The xAI API address Grok-login providers call.',
  grokAuthPath: 'Where the Grok login is stored; splice login writes it.',
  authCacheMs: 'How long a command reuses a login before rereading it from disk.',

  // daemon
  controlPort: 'The port this console and the control API listen on.',
  supervisorUnit: 'The systemd unit running splice; Restart splice restarts it.',
  statuslineGitRoots: 'Colon-separated folders, beyond home and /tmp, where git branches show.',
};
