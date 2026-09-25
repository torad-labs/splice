// Sample repository list for design captures (CONTRACTS.md section 4, the
// fixture rule): loaded only in DEV behind ?fixture=list, imported by name at
// runtime so the dist carries no sample bytes, and marked with a Sample badge
// whenever it is in use.
//
// The file payload rides beside the list, one entry per repo, so an opened sample repo never reads
// the live daemon for files it has never seen; the page hands it to the widget as a prop. Between
// them the four show every shape the file view has: instructions and memory, instructions alone,
// memory switched off, and nothing found.
import type { ProjectFilesPayload, ProjectRow } from '@entities/project';

const HOUR = 3_600_000;
const now = Date.now();
const midnight = new Date(now).setHours(0, 0, 0, 0);

const projects: ProjectRow[] = [
  {
    id: '/home/user/Documents/dev/projects/atlas/repo',
    root: '/home/user/Documents/dev/projects/atlas/repo',
    live_sessions: 4,
    teams: 1,
    turns_today: 218,
    cost_today_usd: 12.84,
    day_start: midnight,
    last_activity: now - 4_000,
    compaction: [
      { scope: 'project', source: 'project:/home/user/Documents/dev/projects/atlas file:/home/user/.config/splice/atlas-compact.md', chars: 1843 },
    ],
    statusline_roots: [
      { head: 'claude-splice', root: '/home/user', entry: 'home' },
      { head: 'claudex', root: '/home/user', entry: 'home' },
    ],
  },
  {
    id: '/home/user/Documents/dev/relay/relay-main',
    root: '/home/user/Documents/dev/relay/relay-main',
    live_sessions: 2,
    teams: 1,
    turns_today: 96,
    cost_today_usd: 4.1,
    day_start: midnight,
    last_activity: now - 2 * 60_000,
    compaction: [
      { scope: 'project-model', source: 'project:/home/user/Documents/dev/relay model:gpt-5.6-sol', chars: 612 },
      { scope: 'model', source: 'model:grok-4.3', chars: 0 },
      { scope: 'global', source: 'global', chars: 402 },
    ],
    statusline_roots: [
      { head: 'claude-splice', root: '/home/user', entry: 'home' },
      { head: 'claudex', root: '/home/user/Documents/dev/relay', entry: 'statuslineGitRoots' },
    ],
  },
  {
    id: '/home/user/Documents/dev/infra/storefront-bot/repo',
    root: '/home/user/Documents/dev/infra/storefront-bot/repo',
    live_sessions: 1,
    teams: 1,
    turns_today: 41,
    // No head that ran here declares rates, so the cost is an absence rather
    // than a figure of zero.
    cost_today_usd: null,
    day_start: midnight,
    last_activity: now - 51 * 60_000,
    compaction: [],
    statusline_roots: [{ head: 'claudex', root: '/home/user', entry: 'home' }],
  },
  {
    id: '/home/user/Documents/dev/infra/nodewatch',
    root: '/home/user/Documents/dev/infra/nodewatch',
    live_sessions: 0,
    teams: 0,
    turns_today: 0,
    cost_today_usd: 0,
    day_start: midnight,
    last_activity: now - 3 * HOUR,
    // The daemon never wired its compaction table: a sample of the null that is not "no rule".
    compaction: null,
    statusline_roots: [{ head: 'claudex', root: null, entry: null }],
  },
];

const files: Record<string, ProjectFilesPayload> = {
  '/home/user/Documents/dev/projects/atlas/repo': {
    id: '/home/user/Documents/dev/projects/atlas/repo',
    looked_in: [
      '/home/user/Documents/dev/projects/atlas/repo',
      '/home/user/.claude/projects/-home-user-Documents-dev-projects-atlas-repo/memory',
    ],
    auto_memory_enabled: true,
    files: [
      {
        kind: 'instructions',
        path: '/home/user/Documents/dev/projects/atlas/repo/CLAUDE.md',
        head: null,
        text: '# repo rules\n\n- run the gate before any install\n- one worktree, one seat\n',
      },
      {
        kind: 'instructions',
        path: '/home/user/Documents/dev/projects/atlas/repo/AGENTS.md',
        head: null,
        text: '# agents\n\ncontracts, invariants and gates live here\n',
      },
      {
        kind: 'memory',
        path: '/home/user/.claude/projects/-home-user-Documents-dev-projects-atlas-repo/memory/MEMORY.md',
        head: 'claude-deepseek',
        text: '# memory\n\nthe console campaign reads its own ledger\n',
      },
    ],
  },
  '/home/user/Documents/dev/relay/relay-main': {
    id: '/home/user/Documents/dev/relay/relay-main',
    looked_in: [
      '/home/user/Documents/dev/relay/relay-main',
      '/home/user/.claude/projects/-home-user-Documents-dev-relay-relay-main/memory',
    ],
    auto_memory_enabled: true,
    files: [
      {
        kind: 'instructions',
        path: '/home/user/Documents/dev/relay/relay-main/CLAUDE.md',
        head: null,
        text: '# relay\n\n- every route has a contract test\n- no retries without a budget\n',
      },
    ],
  },
  '/home/user/Documents/dev/infra/storefront-bot/repo': {
    id: '/home/user/Documents/dev/infra/storefront-bot/repo',
    looked_in: ['/home/user/Documents/dev/infra/storefront-bot/repo'],
    auto_memory_enabled: false,
    files: [
      {
        kind: 'instructions',
        path: '/home/user/Documents/dev/infra/storefront-bot/repo/AGENTS.md',
        head: null,
        text: '# storefront-bot\n\nparity before merge\n',
      },
    ],
  },
  '/home/user/Documents/dev/infra/nodewatch': {
    id: '/home/user/Documents/dev/infra/nodewatch',
    looked_in: [
      '/home/user/Documents/dev/infra/nodewatch',
      '/home/user/.claude/projects/-home-user-Documents-dev-infra-nodewatch/memory',
    ],
    auto_memory_enabled: true,
    files: [],
  },
};

export const fixture = { projects, files };
