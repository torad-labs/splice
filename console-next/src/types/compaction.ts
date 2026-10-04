// The compaction outcomes and the instruction rules, as the daemon writes them: GET /api/compact and
// GET /api/compaction/instructions.
import type { UnreadHead } from './perf';

export interface CompactRow {
  head: string;
  ts: number;
  outcome?: string;
  chars?: number;
  ms?: number;
  error?: string;
  /** The instructions the compaction ran under: `client` for the client's own, else a rule's source label. */
  instructions_source?: string;
}

/** One head's compaction counts and the span of rows they came from. `first_ts`/`last_ts` are absent for a head
 *  with no rows: no rows, no span to claim. */
export interface CompactHeadStats {
  total: number;
  by_outcome: Record<string, number>;
  by_outcome_7d?: Record<string, number>;
  first_ts?: number;
  last_ts?: number;
}

/** GET /api/compact. */
export interface CompactPayload {
  stats: {
    total: number;
    by_outcome: Record<string, number>;
    tail: CompactRow[];
    /** The last seven days among the counted rows; absent on a daemon older than #226. */
    by_outcome_7d?: Record<string, number>;
    heads?: Record<string, CompactHeadStats>;
  };
}

/** CompactionScope.wire. `client` is what a turn resolves to when no rule matched; the route lists configured
 *  rules, so it never sends it. */
export type InstructionScopeWire = 'client' | 'global' | 'model' | 'project' | 'project-model';

/** One configured rule as the route writes it. */
export interface InstructionScope {
  scope: InstructionScopeWire;
  /** Core's composed label (`global`, `model:<id>`, `project:/abs/path`, each optionally ` file:/abs/path`). Printed, never parsed. */
  source: string;
  /** The live length of the text the rule would produce now: 0 for an explicit opt-out, null when its file is unreadable. */
  chars: number | null;
}

/** GET /api/compaction/instructions?head=<key>, as the daemon writes it: no text, lengths only. */
export interface InstructionsWire {
  scopes: InstructionScope[];
}

/** One rule, once, with every head whose answer listed it. */
export interface InstructionRule extends InstructionScope {
  heads: string[];
}

/** What the fleet's compaction rules read as: the rules, and every head that could not be asked. */
export interface InstructionsState {
  rules: InstructionRule[];
  unread: UnreadHead[];
}
