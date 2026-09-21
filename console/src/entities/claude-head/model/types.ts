// The payload contract of the Claude head entity: which of the two modes Claude runs in through
// splice, what `claude` on PATH resolves to today, and which stored login a session would use.
//
//   GET  /api/claude-head            -> ClaudeHeadPayload       (V4-129, FEATURES.md 6 + 4.12)
//   POST /api/claude-head/wrap       -> ClaudeHeadActionResult  (same row)
//   POST /api/claude-head/unwrap     -> ClaudeHeadActionResult  (same row)
//
// The mode is not a preference, it is two different products, and the console's job is to say
// which one is in force rather than to hide the difference: Separate is a splice-owned head with
// its own config dir and no side effects on the operator's own setup; Wrap runs the operator's
// default `claude` command through splice and therefore EDITS two files in `~/.claude`.
//
// V4-175 — TRANSCRIBED FROM THE WIRE, and it has to be. This file was written while V4-129 was
// still open, so it declared the payload that row was EXPECTED to serve: `head`, `config_dir`,
// `auth_kind`, `claude_on_path`, `wrap_supported`. V4-129 then shipped `resolves_to`,
// `real_binary_path` and `claude_logins` instead, and nothing compared the two — /api/claude-head
// was the one route missing from the daemon's WebuiContractTest, so five declared fields that were
// never on the wire and four wire fields nobody read coexisted green. What the operator saw: two
// blank cells, "nothing named claude" for a claude that was right there, and — worst — a
// `separate` badge on a WRAPPED machine, because the daemon's mode string is `wrapped` and this
// file spelled it `wrap`, so `mode === 'wrap'` was never once true. The contract arm now exists
// (WebuiContractTest: "claude-head payload matches ClaudeHeadPayload"), so the next rename on
// either side fails the daemon's build instead of the operator's reading of their own setup.
export const CLAUDE_HEAD_MODES = ['separate', 'wrapped'] as const;

export type ClaudeHeadMode = (typeof CLAUDE_HEAD_MODES)[number];

/** The stored Claude logins (`ClaudeLogins.kt`). This is what stands in for the account rows every
 *  other head's page shows: a Claude head is `auth = { kind = "client" }`, so the daemon holds no
 *  Claude account, builds no pool and tracks no window — the absence is the design, and this card
 *  is the thing that is actually there instead. */
export interface ClaudeLoginsCard {
  count: number;
  /** The label a session would launch with. Null when nothing is selected, and also when the
   *  marker names a label that was since removed — a stale marker is not a selection
   *  (`ClaudeLogins.kt:45-48`). */
  selected: string | null;
  labels: string[];
  /** The daemon's own sentence about the one-login-per-head constraint, printed as-is
   *  (FEATURES 4.5: "the constraint ... is printed on the strip"). */
  constraint: string;
}

export interface ClaudeHeadPayload {
  /** `wrapped` once the shim shadows `claude`, `separate` otherwise. The daemon decides this by
   *  READING the link every time (`WrappedHead.kt:118-128`), never from stored state, so a shim
   *  removed by hand shows as `separate` on the next poll. */
  mode: ClaudeHeadMode;
  /** What `claude` on PATH resolves to TODAY. The symlink TARGET, not the link: "backing up the
   *  shadowed binary" means preserving the target (`~/.local/bin/claude` is a symlink to the
   *  versioned binary), and a console that printed the link would be printing the wrong string to
   *  restore (FEATURES 4.12). Null when nothing named claude is on PATH, or when it dangles. */
  resolves_to: string | null;
  /** The shim that shadows `claude` when wrapped, which must exec the real binary by absolute path
   *  because it takes its head from its own basename (`splice-launch:8`). Always reported: in
   *  `separate` it is where a wrap WOULD install one, which is the honest thing to show before an
   *  action rather than after it. */
  shim_path: string;
  /** Wrapped only: the binary the shim execs, read back from the wrap state. Null in `separate`,
   *  and null in `wrapped` too if the state file is unreadable — which is exactly the case the
   *  operator needs to see, since an unwrap restores from it. */
  real_binary_path: string | null;
  claude_logins: ClaudeLoginsCard;
}

/** Wrap and unwrap both answer with the status they just produced, so the page never has to guess
 *  the new mode from the click that asked for it. The two backup paths are WRAP's alone: unwrap
 *  consumes them and its own answer carries none. A refusal is not this type — it arrives as an
 *  HTTP 409 carrying the daemon's sentence, and `request` throws it. */
export interface ClaudeHeadActionResult extends Omit<ClaudeHeadPayload, 'claude_logins'> {
  ok: boolean;
  settings_backup_path?: string;
  claude_json_backup_path?: string;
}
