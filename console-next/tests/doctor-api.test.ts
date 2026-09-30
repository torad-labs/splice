// The doctor api's logic that is not a hook: the pending-route read, and a fix's two answers (200 and the
// 409 that still carries the re-run report), against a stubbed fetch.
import { afterEach, beforeEach, describe, expect, test, vi } from 'vitest';

const reply = (status: number, body: unknown) => new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } });

async function fresh() {
  vi.resetModules();
  const client = await import('../src/api/client');
  client.storeKey('k');
  return import('../src/api/doctor');
}

const report = { schema_version: 1, checks: [] };

beforeEach(() => {
  vi.stubGlobal('localStorage', undefined);
});
afterEach(() => {
  vi.unstubAllGlobals();
});

describe('the doctor read', () => {
  test('a daemon that does not serve it answers pending; any other failure rejects', async () => {
    const doctor = await fresh();
    vi.stubGlobal('fetch', vi.fn(async () => reply(404, {})));
    expect(await doctor.fetchDoctor()).toEqual({ pending: 'V4-127' });
    vi.stubGlobal('fetch', vi.fn(async () => reply(500, { error: 'probe failed' })));
    await expect(doctor.fetchDoctor()).rejects.toThrow('probe failed');
  });
});

describe('a doctor fix', () => {
  test('the fix id is encoded in the path', async () => {
    const doctor = await fresh();
    expect(doctor.doctorFixPath('a/b c')).toBe('/api/doctor/fix/a%2Fb%20c');
  });

  test('a 200 is applied and carries the re-run report', async () => {
    const doctor = await fresh();
    const fetchMock = vi.fn(async () => reply(200, { fix: 'x', report }));
    vi.stubGlobal('fetch', fetchMock);
    expect(await doctor.runDoctorFix('x')).toEqual({ applied: true, report });
    expect((fetchMock.mock.calls[0] as unknown as [string, RequestInit])[1].method).toBe('POST');
  });

  test('a 409 with a report is a refusal in the daemon\'s words, and the report still replaces the read', async () => {
    const doctor = await fresh();
    vi.stubGlobal('fetch', vi.fn(async () => reply(409, { error: 'still failing', fix: 'x', report })));
    expect(await doctor.runDoctorFix('x')).toEqual({ applied: false, refusal: 'still failing', report });
  });

  test('a 409 with no report, and a 404, reject with the daemon\'s words', async () => {
    const doctor = await fresh();
    vi.stubGlobal('fetch', vi.fn(async () => reply(409, { error: 'busy' })));
    await expect(doctor.runDoctorFix('x')).rejects.toThrow('busy');
    vi.stubGlobal('fetch', vi.fn(async () => reply(404, { error: 'unknown fix' })));
    await expect(doctor.runDoctorFix('x')).rejects.toThrow('unknown fix');
  });
});

describe('the daemon restart', () => {
  test('posts to the restart route and answers the phase it entered; a refusal rejects with its sentence', async () => {
    const doctor = await fresh();
    const fetchMock = vi.fn(async () => reply(202, { status: 'draining' }));
    vi.stubGlobal('fetch', fetchMock);
    expect(await doctor.restartDaemon()).toEqual({ status: 'draining' });
    expect((fetchMock.mock.calls[0] as unknown as [string, RequestInit])[0]).toBe('/api/daemon/restart');
    vi.stubGlobal('fetch', vi.fn(async () => reply(409, { error: 'nothing will restart this daemon' })));
    await expect(doctor.restartDaemon()).rejects.toThrow('nothing will restart this daemon');
  });
});
