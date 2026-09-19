// The payload contract of the Claude head entity: which of the two modes Claude runs in through
// splice, what `claude` on PATH resolves to, and what a wrap would rewrite.
//
//   GET  /api/claude-head            -> ClaudeHeadPayload       (PENDING V4-129, FEATURES.md 6 + 4.12)
//   POST /api/claude-head/wrap       -> ClaudeHeadActionResult  (same row)
//   POST /api/claude-head/unwrap     -> ClaudeHeadActionResult  (same row)
//
// The mode is not a preference, it is two different products, and the console's job is to say
// which one is in force rather than to hide the difference: Separate is a splice-owned head with
// its own config dir and no side effects on the operator's own setup; Wrap runs the operator's
// default `claude` command through splice and therefore EDITS two files in `~/.claude`.
import type { PendingRoute } from '@shared/api';

export const CLAUDE_HEAD_MODES = ['separate', 'wrap'] as const;

export type ClaudeHeadMode = (typeof CLAUDE_HEAD_MODES)[number];

export interface ClaudeHeadPayload {
  mode: ClaudeHeadMode;
  /** The head key in separate mode (`claude-splice`) — the splice-owned head whose own config dir
   *  holds its logins, projects and transcripts. */
  head: string;
  config_dir: string;
  /** Always `client` for a Claude head: the client's own login passes through, the daemon holds no
   *  Claude account, builds no pool, polls nothing and tracks no Claude window (FEATURES 2.6,
   *  HeadAccountPools.kt:27,54). That is why this page has no account rows — the absence is the
   *  design, and the strip says so rather than showing an empty pool. */
  auth_kind: string;
  /** What `claude` on PATH resolves to TODAY. The symlink TARGET, not the link: "backing up the
   *  shadowed binary" means preserving the target (`~/.local/bin/claude` is a symlink to the
   *  versioned binary), and a console that printed the link would be printing the wrong string to
   *  restore (FEATURES 4.12). Null when nothing named claude is on PATH. */
  claude_on_path: string | null;
  /** Wrap only: the shim that shadows `claude`, which must exec the real binary by absolute path
   *  because it takes its head from its own basename (`splice-launch:8`). */
  shim_path?: string;
  /** Wrap only: the two files a wrap rewrites — a merged `settings.json` and `.claude.json` in the
   *  vanilla dir (`ClaudeConfigMaterializer.kt:88,108,295`). */
  rewritten_files?: string[];
  /** Where those files were put before the rewrite. Unwrap restores from here. */
  backup_paths?: string[];
  /** False when the daemon found no path that satisfies the materializer's DR-102 guard, which
   *  refuses `~/.claude` by design (`ClaudeConfigMaterializer.kt:121-131`). A wrap is then not
   *  offered; it is never attempted by bypassing that guard. */
  wrap_supported: boolean;
  /** The daemon's own sentence, printed as-is. */
  note?: string;
}

export interface ClaudeHeadActionResult {
  ok: boolean;
  mode: ClaudeHeadMode;
  shim_path?: string;
  backup_paths?: string[];
  note?: string;
}

/** The v0.4.0 item that will serve the three Claude head routes (FEATURES.md 6). */
export const PENDING_CLAUDE_HEAD = 'V4-129';

export type ClaudeHeadState = ClaudeHeadPayload | PendingRoute;
