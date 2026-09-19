// Sample board for design captures (CONTRACTS.md section 4, the fixture rule).
// It loads ONLY when import.meta.env.DEV is true and the address carries
// ?fixture=board, it is imported by name at runtime so the shipped dist carries
// no sample bytes, and the page prints a grey "sample data" holder edge beside
// the title whenever it is in use.
//
// The rows are shaped like the daemon's own payload, including the two states a
// live machine rarely shows at once: a stale session and a gone one, with a
// registration that carries no session id at all, so the capture exercises the
// honest empties rather than only the happy path.
import type { SessionsPayload } from '@entities/session';

const MINUTE = 60_000;
const now = Date.now();

export const fixture: SessionsPayload = {
  note: 'headless `claude -p` runs never register; gone = the process exited; stale = alive but no registry update inside the stale window',
  sessions: [
    {
      pid: 2204122,
      session_id: '3f67533e-6b1a-4f2c-9d21-8a0f2c1d4e77',
      name: 'splice-design',
      kind: 'interactive',
      version: '2.1.257',
      cwd: '/home/marcos/Documents/dev/projects/mythos/repo/.claude/worktrees/v0.4.0',
      status: 'busy',
      status_updated_at: now - 4_000,
      started_at: now - 3 * 60 * MINUTE,
      updated_at: now - 4_000,
      address: 'uds:/run/user/1000/cc-socks/2204122.sock',
      head: 'claude-deepseek',
      availability: 'live',
      repo: {
        root: '/home/marcos/Documents/dev/projects/mythos/repo',
        worktree: '/home/marcos/Documents/dev/projects/mythos/repo/.claude/worktrees/v0.4.0',
      },
      team: 'web-console',
    },
    {
      pid: 2155010,
      session_id: 'c8692118-2d51-4a11-8b70-51d2a0b7c9e1',
      name: 'design-builder3',
      kind: 'interactive',
      version: '2.1.257',
      cwd: '/home/marcos/Documents/dev/projects/mythos/repo/.claude/worktrees/v0.4.0',
      status: 'busy',
      status_updated_at: now - 30_000,
      started_at: now - 42 * MINUTE,
      updated_at: now - 30_000,
      address: 'uds:/run/user/1000/cc-socks/2155010.sock',
      head: 'claude-deepseek',
      availability: 'live',
      repo: { root: '/home/marcos/Documents/dev/projects/mythos/repo' },
      team: 'web-console',
    },
    {
      pid: 1042998,
      session_id: '4e22c196-64b7-4a64-9972-5ddd337dec15',
      name: 'qgre-orchestrator',
      kind: 'interactive',
      version: '2.1.257',
      cwd: '/home/marcos/Documents/dev/qgre/qgre-main',
      status: 'busy',
      status_updated_at: now - 2 * MINUTE,
      started_at: now - 5 * 60 * MINUTE,
      updated_at: now - 2 * MINUTE,
      address: 'uds:/run/user/1000/cc-socks/1042998.sock',
      head: 'claudex',
      availability: 'live',
      repo: { root: '/home/marcos/Documents/dev/qgre/qgre-main' },
    },
    {
      pid: 1975013,
      session_id: '4f0ddf5b-215d-4e7d-9554-30b18a59fc7f',
      name: 'gs-scout-claude',
      kind: 'interactive',
      version: '2.1.257',
      cwd: '/home/marcos/Documents/dev/infra/grailseeker-bot/repo',
      status: 'shell',
      status_updated_at: now - 51 * MINUTE,
      started_at: now - 20 * 60 * MINUTE,
      updated_at: now - 51 * MINUTE,
      address: 'uds:/run/user/1000/cc-socks/1975013.sock',
      head: 'claudex',
      availability: 'stale',
      repo: { root: '/home/marcos/Documents/dev/infra/grailseeker-bot/repo' },
    },
    {
      pid: 1880402,
      session_id: null,
      name: 'ledger-lead',
      kind: 'interactive',
      version: '2.1.257',
      cwd: null,
      status: null,
      status_updated_at: null,
      started_at: null,
      updated_at: null,
      address: null,
      head: 'unknown head',
      availability: 'gone',
    },
  ],
};
