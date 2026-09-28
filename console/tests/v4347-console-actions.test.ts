import { createElement } from 'react';
import { renderToStaticMarkup } from 'react-dom/server';
import { describe, expect, test } from 'vitest';
import type { DoctorPayload } from '../src/entities/doctor';
import { FixLine } from '../src/pages/doctor';
import { tailFromSearch } from '../src/pages/logs';
import { FixCell, needsOf } from '../src/pages/needs-you';
import type { NeedInputs, Read } from '../src/pages/needs-you';

const render = (component: Parameters<typeof renderToStaticMarkup>[0]): string => renderToStaticMarkup(component);
const unread: Read<never> = { data: null, error: null, lastUpdated: null };
const read = <T>(data: T): Read<T> => ({ data, error: null, lastUpdated: 1_790_000_000_000 });

function doctorFix(fix: string): NeedInputs {
  const report = {
    schema_version: 1, generated_at: '2026-09-28T00:00:00Z', splice: { version: '0.4.0' },
    claude_code: { version: '2' }, os: { name: 'Linux', version: '6', arch: 'x64' },
    jvm: { version: '21', vendor: 'fixture' },
    checks: [{ id: 'daemon/logs', status: 'warn' as const, detail: 'look at the head log', fix }],
  } as DoctorPayload;
  return {
    heads: read([]), auth: unread, accounts: unread, usage: unread,
    sessions: unread, teams: unread, doctor: read(report), topology: read(false), restartPending: [],
  };
}

describe('fixes the console can carry out itself', () => {
  test('Doctor and Needs you open the requested head and tail instead of copying a log command', () => {
    const command = 'splice logs --head codex --tail 50';
    const doctor = render(createElement(FixLine, { fix: command }));
    expect(doctor).toContain('href="#/logs?head=codex&amp;tail=50"');
    expect(doctor).not.toContain(command);
    expect(doctor).not.toContain('Copy');
    expect(doctor).toContain('If unavailable');
    const [need] = needsOf(doctorFix(command), 1_790_000_000_000).needs;
    expect(need.fix).toMatchObject({ kind: 'open', href: '#/logs?head=codex&tail=50' });
    const needs = render(createElement(FixCell, { fix: need.fix }));
    expect(needs).toContain('href="#/logs?head=codex&amp;tail=50"');
    expect(needs).not.toContain(command);
    expect(needs).toContain('If unavailable');
    expect(tailFromSearch('?head=codex&tail=50')).toBe(50);
    expect(tailFromSearch('?head=codex&tail=1500')).toBe(1500);
    expect(tailFromSearch('?head=codex&tail=50000')).toBeNull();
  });

  test('restart and daemon-run install use controls; an unknown remedy keeps its CLI fallback', () => {
    const restart = render(createElement(FixLine, { fix: 'splice restart' }));
    expect(restart).toContain('Restart daemon');
    expect(restart).toContain('If unavailable');
    expect(restart).not.toContain('>splice restart<');
    const install = render(createElement(FixLine, { fix: 'splice install --all', fixId: 'install_all' }));
    expect(install).toContain('Run fix');
    expect(install).toContain('If unavailable');
    expect(install).not.toContain('>splice install --all<');
    const unknown = render(createElement(FixLine, { fix: 'repair by hand' }));
    expect(unknown).toContain('repair by hand');
    expect(unknown).toContain('Copy');
  });
});
