// V4-220 item 4's console half: POST /api/doctor/fix/{id} through the real client. The daemon answers
// with doctor re-run after the fix, applied or not, so both answers land that report in the doctor
// store (the page then reads the state its next poll would), and only an answer with no report is a
// failure.
import { afterEach, beforeEach, describe, expect, test, vi } from 'vitest';
import { doctorStore } from '../src/entities/doctor/model/store';
import { runDoctorFix } from '../src/entities/doctor';
import type { DoctorPayload } from '../src/entities/doctor';

interface Sent {
  path: string;
  method: string | undefined;
}

function transport(status: number, body: unknown, sent: Sent[]): void {
  vi.stubGlobal('fetch', async (path: string, init?: RequestInit) => {
    sent.push({ path, method: init?.method });
    return { ok: status >= 200 && status < 300, status, json: async () => body };
  });
}

function report(detail: string): DoctorPayload {
  return {
    schema_version: 1, generated_at: '2026-09-25T00:00:00Z', splice: { version: '0.4.0' }, claude_code: { version: '2' },
    os: { name: 'linux', version: '6', arch: 'x64' }, jvm: { version: '21', vendor: 'x' },
    checks: [{ id: 'installation/wrapper', status: 'ok', detail }],
  } as DoctorPayload;
}

describe('POST /api/doctor/fix/{id} through the real client', () => {
  beforeEach(() => doctorStore.setData(report('before the fix')));
  afterEach(() => vi.unstubAllGlobals());

  test('a fix the re-run finds done is one POST on the fix\'s path, and its report is the doctor\'s now', async () => {
    const sent: Sent[] = [];
    transport(200, { fix: 'install_all', report: report('linked after the fix') }, sent);
    expect(await runDoctorFix('install_all')).toEqual({ applied: true });
    expect(sent).toEqual([{ path: '/api/doctor/fix/install_all', method: 'POST' }]);
    expect(doctorStore.get().data).toEqual(report('linked after the fix'));
  });

  test('a refusal is the daemon\'s sentence, and the report it ran after still replaces the old one', async () => {
    const refusal = 'splice install --all ran, but 2 doctor row(s) still call for it';
    transport(409, { error: refusal, fix: 'install_all', report: report('still unlinked') }, []);
    expect(await runDoctorFix('install_all')).toEqual({ applied: false, refusal });
    expect(doctorStore.get().data).toEqual(report('still unlinked'));
  });

  test('an answer with no report is a failure in the daemon\'s words, and the report stays as it was', async () => {
    transport(503, { error: 'doctor fixes are not wired on this daemon' }, []);
    await expect(runDoctorFix('install_all')).rejects.toThrow('doctor fixes are not wired on this daemon');
    expect(doctorStore.get().data).toEqual(report('before the fix'));
  });
});
