// The Claude head's mode as the daemon writes it: ClaudeHeadRoutes.
//   GET  /api/claude-head          ClaudeHeadPayload
//   POST /api/claude-head/wrap     ClaudeHeadActionResult
//   POST /api/claude-head/unwrap   ClaudeHeadActionResult
// Separate is a splice-owned head with its own config dir; wrapped runs the operator's own `claude` command through splice and
// writes nothing into `~/.claude` (V4-445): it swaps one link. The daemon reads the link every time, so a shim removed by hand reads as separate.
export type ClaudeHeadMode = 'separate' | 'wrapped';

/** Saved login copies and their selection marker, distinct from the live identities on Accounts. */
export interface ClaudeLoginsCard {
  count: number;
  /** The label last saved or switched to; launches use the live login, never restore this copy. */
  selected: string | null;
  labels: string[];
  /** The daemon's own sentence about one login per head. */
  constraint: string;
}

export interface ClaudeHeadPayload {
  mode: ClaudeHeadMode;
  /** What `claude` on PATH resolves to now: the link's target, null when nothing named claude is on PATH or it dangles. */
  resolves_to: string | null;
  /** The shim that shadows `claude` when wrapped; where a wrap would install one when separate. */
  shim_path: string;
  /** Wrapped only: the binary the shim runs. Null when separate, and when the wrap state is unreadable. */
  real_binary_path: string | null;
  claude_logins: ClaudeLoginsCard;
}

/** What wrap and unwrap answer: the status they just produced. The two backups are wrap's alone. A refusal is a 409 with a sentence. */
export interface ClaudeHeadActionResult extends Omit<ClaudeHeadPayload, 'claude_logins'> {
  ok: boolean;
  settings_backup_path?: string;
  claude_json_backup_path?: string;
}
