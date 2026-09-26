// The one place per-head answers of GET /api/compaction/instructions become the fleet's rules.
//
// The route answers per head (a head is REQUIRED, an unknown one is a 400 naming it), and most
// rules apply to every head (global, project), so reading every head and printing every answer
// would print the same rule once per head. A rule is one configured entry: its source is core's
// composed label and names it exactly, so answers are merged by (scope, source), each rule
// carrying the heads that listed it. A model rule is listed only by the heads whose roster
// carries the model, which is how the page can say where it applies.
import type { InstructionRule, InstructionScopeWire, InstructionsWire } from './types';

/** CompactionInstructions.rules' own order: project-model, project, model, global. */
const PRECEDENCE: Record<InstructionScopeWire, number> = {
  'project-model': 0,
  project: 1,
  model: 2,
  global: 3,
  client: 4,
};

export function mergeInstructions(answers: readonly { head: string; wire: InstructionsWire }[]): InstructionRule[] {
  const rules = new Map<string, InstructionRule>();
  for (const { head, wire } of answers) {
    for (const scope of wire.scopes) {
      const key = `${scope.scope}\n${scope.source}`;
      const held = rules.get(key);
      if (held === undefined) rules.set(key, { ...scope, heads: [head] });
      else if (!held.heads.includes(head)) held.heads.push(head);
    }
  }
  // Stable within a tier, so two project rules keep the order the daemon listed them in.
  return [...rules.values()].sort((left, right) => PRECEDENCE[left.scope] - PRECEDENCE[right.scope]);
}
