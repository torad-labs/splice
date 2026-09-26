// The compaction instructions in effect, typed from the daemon that serves them:
// GET /api/compaction/instructions?head=<key> (CompactionInstructionsRoute, V4-136). ONE ENTRY PER
// CONFIGURED RULE that applies to the head (global, every project rule, and the model rules for
// models in that head's roster), in precedence order, and NO TEXT: `chars` is the length only.

/** CompactionScope.wire. `client` is what a turn resolves to when no rule matched; the route lists
 *  configured rules, so it never sends it, and the type keeps it because the wire enum carries it. */
export type InstructionScopeWire = 'client' | 'global' | 'model' | 'project' | 'project-model';

/** One configured rule as the route writes it. */
export interface InstructionScope {
  scope: InstructionScopeWire;
  /** Core's composed label (`global`, `model:<id>`, `project:/abs/path`, `project:/abs/path
   *  model:<id>`, each optionally ` file:/abs/path` or ` file:/abs/path unreadable`). Printed, never
   *  parsed. */
  source: string;
  /** The live length of the text the rule would produce now: 0 for an explicit opt-out (the
   *  client's own instructions stand), null when the text is unavailable because its file is
   *  unreadable, which the source also says. */
  chars: number | null;
}

/** GET /api/compaction/instructions?head=<key>, exactly as the daemon writes it. */
export interface InstructionsWire {
  scopes: InstructionScope[];
}

/** One rule, once, with every head whose answer listed it. */
export interface InstructionRule extends InstructionScope {
  heads: string[];
}

/** A head whose instructions could not be read, and the daemon's reason in its own words. */
export interface UnreadInstructions {
  head: string;
  reason: string;
}

/** What the instructions store holds: the fleet's rules, and every head that could not be asked. */
export interface InstructionsState {
  rules: InstructionRule[];
  unread: UnreadInstructions[];
}
