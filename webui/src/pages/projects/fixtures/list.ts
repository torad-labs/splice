// Sample repository list for design captures (CONTRACTS.md section 4, the
// fixture rule): loaded only in DEV behind ?fixture=list, imported by name at
// runtime so the dist carries no sample bytes, and labeled with a grey "sample
// data" holder edge whenever it is in use.
//
// The file payload rides beside the list because the files route is pending
// V4-131 and the capture has to show the file view populated rather than its
// honest empty; the page hands it to the widget as a prop.
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
  },
  {
    id: '/home/user/Documents/dev/infra/storefront-bot/repo',
    root: '/home/user/Documents/dev/infra/storefront-bot/repo',
    live_sessions: 1,
    teams: 1,
    turns_today: 41,
    // No head that ran here declares rates, so the strip says no rates rather
    // than a cost of zero.
    cost_today_usd: null,
    day_start: midnight,
    last_activity: now - 51 * 60_000,
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
};

export const fixture = { projects, files };
