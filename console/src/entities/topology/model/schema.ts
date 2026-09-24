// The documented topology key set as data, and the pure validator that checks a parsed topology
// against it. Source of every key below: FEATURES.md section 2.3 ("Topology"), which mirrors
// `Topology.kt`, `HeadConfig.kt`, `QuirksConfig.kt`, `TopologySchema.kt`, `CompactionScope.kt`,
// `HeadSystemPrompt.kt` and `TokenCost.kt`.
//
// What it is FOR: a topology edit is boot-only and a bad one is only discovered at the next
// restart, draining in-flight turns on the way. Catching a typo'd key before the write is the
// whole value. What it is NOT: a substitute for the daemon's own writer. The daemon validates
// again on PUT and its refusal is the authority; this only spares the operator the round trip.
import type { TopologyFinding } from './types';

/**
 * A node in the schema tree.
 *   `{}`            a leaf: any scalar is accepted.
 *   `keys`          a table with exactly these keys; a key not in the map is a finding.
 *   `each`          a table keyed by free names (providers.<name>, heads.<key>): every child table
 *                   is validated against this node.
 *   `array`         an array of tables ([[...]]): every element is validated against this node.
 *   `open`          a bag whose child KEYS are not schema (extra_headers, overrides). Key-checking
 *                   one of these would reject legal config, which is worse than not checking it.
 */
export interface SchemaNode {
  keys?: Record<string, SchemaNode>;
  each?: SchemaNode;
  array?: SchemaNode;
  open?: boolean;
}

/**
 * The 33 runtime knobs, which `[defaults]` accepts wholesale ("any runtime knob", FEATURES 2.3,
 * defined in 2.2).
 *
 * DUPLICATED ON PURPOSE, and the duplication is the honest part: the authoritative parse is from
 * `Knob.kt` at test time, which the coverage gate (row M1-04) owns and which fails a knob with no
 * console disposition. This list exists only so `[defaults]` can be key-checked here, and M1-04's
 * gate is what will catch it if a knob is renamed upstream.
 */
export const RUNTIME_KNOBS = [
  'port', 'chatgptApiBase', 'codexAuthPath', 'pinnedModel', 'effort', 'summary', 'showReasoning',
  'replayReasoning', 'mirrorReasoning', 'progressLine', 'foldReasoningModels', 'foldMaxContinue',
  'foldMarkerText', 'foldMaxTier', 'toolSurface', 'quotaPoll', 'maxInflight', 'maxQueued',
  'upstreamRetries', 'upstreamTimeoutMs', 'firstByteTimeoutMs', 'streamIdleMs', 'authCacheMs',
  'debug', 'contextWindowOverride', 'grokPort', 'grokModel', 'xaiApiBase', 'grokAuthPath',
  'controlPort', 'usageWarnPct', 'usageWarnTokens5h', 'statuslineGitRoots',
] as const;

/** The ten directories `[claude] share` / `[claude] isolate` accept by name (FEATURES 2.3). A
 *  typo in one of these is silent in the daemon — nothing is shared, and nothing says so. */
const SHARE_KEYS = [
  'settings', 'mcps', 'skills', 'hooks', 'agents', 'commands', 'plugins', 'claude_md', 'sessions',
  'projects',
] as const;

const shareTable: SchemaNode = {
  keys: Object.fromEntries(SHARE_KEYS.map((key) => [key, {}])),
};

/** The per-million-token rates, per model id (FEATURES 2.3). `cache_write` is optional; the absent
 *  case means "no dollar figure", never zero. */
const ratesTable: SchemaNode = {
  each: { keys: { input: {}, cache_read: {}, output: {}, cache_write: {} } },
};

const providerModels: SchemaNode = {
  array: { keys: { id: {}, label: {}, description: {}, context_window: {} } },
};

/** Every quirk key `QuirksConfig` parses. Which ones a given provider's dialect READS depends on
 *  its dialect — the console shows only those and says why the rest are absent — but all of them
 *  are legal keys in the file, so all of them belong in the schema. */
const quirks: SchemaNode = {
  keys: {
    store: {}, account_id_header: {}, cache_key: {}, effort_ceiling: {}, summary_field: {},
    // Retired in code but still PARSED so a config carrying it fails loudly at load rather than
    // being ignored in silence — which is exactly why it must stay in this key set.
    compact_effort: {},
    tool_choice: {}, reasoning_cache: {}, parallel_tool_calls: {}, websocket: {}, code_mode: {},
    zstd_request_body: {}, reasoning_effort: {}, stream_usage: {}, slot_affinity: {}, mfjs: {},
    block_allowlist: {},
    strip_cache_control: {}, synthesize_signatures: {}, map_thinking_adaptive: {},
    strip_sampling_params: {}, reanchor_prefill: {},
    tool_surface: {
      keys: {
        enabled: {}, defer_prefixes: {}, defer: {}, eager: {}, min_deferred: {}, search_limit: {},
        search_rounds: {},
      },
    },
  },
};

const provider: SchemaNode = {
  keys: {
    dialect: {}, base_url: {}, auth: { keys: { kind: {}, env: {}, client: {}, file: {} } },
    quirks,
    extra_headers: { open: true },
    models: providerModels,
    // Not documented field-by-field in 2.3; the daemon reads them as bags.
    extra_windows: { open: true },
    window_rules: { open: true },
    default_context_window: {},
    local: {},
    models_url: {},
    // Which published models join the picker beyond the declared rows (ModelDiscoveryConfig).
    discovery: { keys: { include: {}, exclude: {} } },
    rates: ratesTable,
  },
};

const head: SchemaNode = {
  keys: {
    provider: {}, port: {}, discovery_prefix: {}, pinned_model: {},
    models: { array: { keys: { id: {}, slot: {} } } },
    context_window: {},
    overrides: { open: true },
    claude: { keys: { command: {}, share: shareTable, isolate: shareTable } },
    system_prompt: {}, system_prompt_file: {}, system_prompt_mode: {},
    rates: ratesTable,
  },
};

const compaction: SchemaNode = {
  keys: {
    instructions: {}, file: {},
    model: { array: { keys: { model: {}, instructions: {}, file: {} } } },
    project: { array: { keys: { path: {}, model: {}, instructions: {}, file: {} } } },
  },
};

/** The whole documented key set, rooted at the tables splice.toml may carry. */
export const TOPOLOGY_SCHEMA: SchemaNode = {
  keys: {
    daemon: {
      keys: {
        control_port: {}, state_dir: {}, show_reasoning: {}, summary: {}, effort: {},
        replay_reasoning: {}, mirror_reasoning: {}, fold_reasoning_models: {}, fold_max_continue: {},
        fold_marker_text: {}, fold_max_tier: {}, mcp_hosting: {}, mcp_hosting_exclude: {},
      },
    },
    claude: { keys: { share: shareTable, isolate: shareTable, config_dir: {} } },
    compaction,
    defaults: { keys: Object.fromEntries(RUNTIME_KNOBS.map((key) => [key, {}])) },
    providers: { each: provider },
    heads: { each: head },
  },
};

function isTable(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null && !Array.isArray(value);
}

function join(path: string, key: string): string {
  return path === '' ? key : `${path}.${key}`;
}

function walk(value: unknown, node: SchemaNode, path: string, out: TopologyFinding[]): void {
  if (node.open === true) return;
  // An array of tables is checked BEFORE the table guard: an array is not a table, so the guard
  // would silently skip every [[...]] block, which is where most of a real topology lives.
  if (node.array !== undefined && Array.isArray(value)) {
    value.forEach((entry, index) => {
      walk(entry, node.array as SchemaNode, `${path}[${index}]`, out);
    });
    return;
  }
  if (!isTable(value)) return; // a scalar where a table belongs: TOML's own parse rejects it first

  if (node.keys !== undefined) {
    for (const [key, child] of Object.entries(value)) {
      const childNode = Object.hasOwn(node.keys, key) ? node.keys[key] : undefined;
      if (childNode === undefined) {
        out.push({ path: join(path, key), message: 'unknown key' });
        continue;
      }
      walk(child, childNode, join(path, key), out);
    }
  }
  if (node.each !== undefined) {
    for (const [key, child] of Object.entries(value)) walk(child, node.each, join(path, key), out);
  }
}

/**
 * Every key in `value` that the documented topology does not carry.
 *
 * Returns findings rather than throwing: a topology with one typo is still worth showing, and the
 * console's job is to point at the line, not to refuse the file. An empty array means every key is
 * one the daemon parses — not that the file is valid, which only the daemon's writer can say.
 */
export function validateTopology(value: unknown): TopologyFinding[] {
  const out: TopologyFinding[] = [];
  walk(value, TOPOLOGY_SCHEMA, '', out);
  return out;
}

/**
 * The closed value sets a topology key takes, keyed by the key's name; the auth table's `kind` is
 * `auth.kind`, since a bare `kind` means nothing else here. Each list is the daemon's own spelling:
 * `Dialect` (TopologySchema.kt), `SystemPromptMode` (HeadSystemPrompt.kt) and `AuthKind`
 * (AuthKind.kt). An editor offers these as a picker; the validator above stays the key check.
 */
export const TOPOLOGY_CHOICES: Readonly<Record<string, readonly string[]>> = {
  dialect: ['openai-responses', 'openai-chat', 'anthropic-passthrough'],
  system_prompt_mode: ['append', 'replace', 'strip'],
  'auth.kind': ['chatgpt-oauth', 'grok-oauth', 'kimi-oauth', 'muse-oauth', 'client', 'api-key'],
};
