// NEW: V4-444 — isolated daemon stack and lifecycle, adding only synthetic registry states.
import globalSetup from './global-setup';
import { STACK, saveTranscript } from './stack';
import { readFileSync, writeFileSync } from 'node:fs';
import { dirname, join } from 'node:path';

export const FINISHED = { id: 'synthetic-console-finished', name: 'Synthetic finished session' } as const;

export default async function setup(): Promise<() => Promise<void>> {
  const stop = await globalSetup();
  try {
    const html = process.env.CONSOLE_E2E_HTML;
    if (html !== undefined) {
      const response = await fetch((process.env.CONSOLE_E2E_BASE ?? '') + '/');
      if (!response.ok || await response.text() !== readFileSync(html, 'utf8')) {
        throw new Error('real daemon did not serve the selected built console byte-for-byte');
      }
    }
    const config = process.env.CONSOLE_E2E_CONFIG;
    const root = process.env.CONSOLE_E2E_TRANSCRIPT_ROOT;
    if (config === undefined || root === undefined) throw new Error('shared stack did not publish its isolated paths');
    const sessions = join(dirname(dirname(dirname(config))), '.claude', 'sessions');
    const senderFile = join(sessions, STACK.sender.name + '.json');
    const peerFile = join(sessions, STACK.peer.name + '.json');
    const sender = JSON.parse(readFileSync(senderFile, 'utf8')) as Record<string, unknown>;
    const peer = JSON.parse(readFileSync(peerFile, 'utf8')) as Record<string, unknown>;
    // Busy with no live turn means a local tool, not a hang, even after an old status change.
    writeFileSync(senderFile, JSON.stringify({ ...sender, status: 'busy', statusUpdatedAt: Date.now() - 900_000 }));
    writeFileSync(peerFile, JSON.stringify({ ...peer, status: 'waiting' }));
    writeFileSync(join(sessions, 'synthetic-finished.json'), JSON.stringify({
      ...sender, sessionId: FINISHED.id, name: FINISHED.name, status: 'idle',
      messagingSocketPath: null,
    }));
    saveTranscript(root, STACK.sender.id, 'synthetic-console-reply', 'Synthetic question', '**Synthetic answer** with `code`.');
    const response = await fetch((process.env.CONSOLE_E2E_BASE ?? '') + '/api/teams', {
      method: 'PUT',
      headers: {
        Authorization: 'Bearer ' + (process.env.CONSOLE_E2E_KEY ?? ''),
        'content-type': 'application/json',
        'Idempotency-Key': 'synthetic-console-team',
      },
      body: JSON.stringify({
        name: 'Synthetic console team', goal: '', features: [], repo: process.env.CONSOLE_E2E_REPO,
        archived: false,
        slots: [{
          id: 'synthetic-console-lead', role: 'lead', head: STACK.oauthHead,
          model: STACK.model, account: null, lead: true, instructions: null, session: null,
        }],
      }),
    });
    if (!response.ok) throw new Error('isolated daemon refused synthetic team: ' + response.status);
    const team = await response.json() as { id: string };
    process.env.CONSOLE_NEXT_E2E_TEAM = team.id;
    return stop;
  } catch (failure) {
    await stop();
    throw failure;
  }
}
