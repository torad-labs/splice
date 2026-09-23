// WALLS for M4-07: nothing of a refused doctor report reaches the screen, and the CLI's own mask
// is not a leak.
//
//   - The gate used to guard the checks rack alone. The fix list and the opened check's detail were
//     drawn from the payload whether or not it passed, so a secret the gate caught still printed in
//     the aside. The render test plants one in a fix and one in a detail, opens each check in turn,
//     and asserts that NO string of the refused report is in the markup. The denominator is the
//     payload's own string leaves, walked here, never a hand list of the surfaces that happen to
//     exist today; a control render of the same report without the secrets shows the leaves ARE
//     printed when the report is clean, so the assertion is one that can fail.
//   - The redaction mirror matched the CLI's own output (`NAME_KEY=<redacted>` is still a key, a
//     separator and a value), so the e2e stack's report was refused for the CLI having done its job.
//
// A `.ts` test cannot hold JSX (TS1161), so elements are built with React.createElement and
// asserted against renderToStaticMarkup's string.
import * as React from 'react';
import { renderToStaticMarkup } from 'react-dom/server';
import { describe, expect, test } from 'vitest';
import { isRedacted, leaksIn, leaksInText } from '../src/entities/doctor';
import type { DoctorCheck, DoctorPayload } from '../src/entities/doctor';
import { DoctorBoard } from '../src/pages/doctor';
import { EMPTIES } from '../src/pages/doctor/model';

const h = React.createElement;

/** The daemon's own separator: a space, U+2014, then " fix: " (DoctorReportShape.kt:59). */
const SEP = ` ${String.fromCharCode(0x2014)} fix: `;

function check(id: string, status: DoctorCheck['status'], detail: string): DoctorCheck {
  return { id, status, detail };
}

/** A report whose every string is distinctive, so its absence from the markup means something. */
function report(checks: DoctorCheck[]): DoctorPayload {
  return {
    schema_version: 1,
    generated_at: '2026-01-02T03:04:05Z',
    splice: { version: '0.0.0-planted' },
    claude_code: { version: '9.9.9-planted' },
    os: { name: 'PlantedOS', version: 'planted-os-1', arch: 'planted-arch' },
    jvm: { version: 'planted-jvm-21', vendor: 'Planted Vendor' },
    topology: { note: 'planted-topology' },
    checks,
    accounts: { note: 'planted-accounts' },
    perf: { note: 'planted-perf' },
  };
}

function board(payload: DoctorPayload, openKey: string | null = null): string {
  return renderToStaticMarkup(h(DoctorBoard, { report: payload, openKey, onToggle: () => undefined }));
}

/** Every string leaf of a payload, except a check's status: `ok`, `warn` and `fail` are the page's
 *  own vocabulary too, and a substring match on them would pass or fail for reasons that are not
 *  the report. */
function leaves(value: unknown, key = ''): string[] {
  if (typeof value === 'string') return key === 'status' ? [] : [value];
  if (Array.isArray(value)) return value.flatMap((entry) => leaves(entry));
  if (typeof value === 'object' && value !== null) {
    return Object.entries(value).flatMap(([name, entry]) => leaves(entry, name));
  }
  return [];
}

/** renderToStaticMarkup escapes text; a leaf is looked for the way the markup would carry it. */
function escaped(text: string): string {
  return text.replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;').replace(/"/g, '&quot;').replace(/'/g, '&#x27;');
}

const FIX_SECRET = 'sk-live-PLANTEDFIXSECRET0001';
const DETAIL_SECRET = 'PLANTEDDETAILSECRET0002';

describe('a refused report prints nothing of itself', () => {
  const ids = ['auth/planted-codex', 'env/planted-proxy', 'daemon/planted-port'];
  const planted = report([
    check(ids[0] ?? '', 'fail', `the codex key was rejected${SEP}export OPENAI_API_KEY=${FIX_SECRET}`),
    check(ids[1] ?? '', 'warn', `Authorization: Bearer ${DETAIL_SECRET} was found in the environment`),
    check(ids[2] ?? '', 'ok', 'control plane is listening on 3096'),
  ]);
  // The same report with both secrets gone: the control that proves the leaves below are printed
  // when the gate passes, so their absence from the refused render is the gate's doing.
  const clean = report([
    check(ids[0] ?? '', 'fail', `the codex key was rejected${SEP}splice login planted-codex`),
    check(ids[1] ?? '', 'warn', 'a proxy variable was found in the environment'),
    check(ids[2] ?? '', 'ok', 'control plane is listening on 3096'),
  ]);

  test('the planted report is refused and the control is not', () => {
    expect(isRedacted(planted)).toBe(false);
    expect(isRedacted(clean)).toBe(true);
  });

  test('the secret planted in a fix never reaches the markup, whichever check is open', () => {
    for (const open of [null, ...ids]) {
      const out = board(planted, open);
      expect(out, `open: ${open}`).toContain('report refused');
      expect(out, `open: ${open}`).not.toContain(FIX_SECRET);
    }
  });

  test('the secret planted in a detail never reaches the markup, with its check opened', () => {
    const out = board(planted, ids[1] ?? '');
    expect(out).toContain('report refused');
    expect(out).not.toContain(DETAIL_SECRET);
  });

  test('no string of the refused report reaches the markup, whichever check is open', () => {
    for (const open of [null, ...ids]) {
      const out = board(planted, open);
      for (const leaf of leaves(planted)) {
        expect(out, `open: ${open}, leaf: ${leaf}`).not.toContain(escaped(leaf));
      }
    }
  });

  test('the control shows the same surfaces are printed when the report passes', () => {
    const rack = board(clean);
    expect(rack).not.toContain('report refused');
    for (const id of ids) expect(rack).toContain(id);
    expect(rack).toContain('splice login planted-codex');
    expect(rack).toContain('0.0.0-planted');
    expect(rack).toContain('9.9.9-planted');
    expect(board(clean, ids[1] ?? '')).toContain('a proxy variable was found in the environment');
  });

  test('a refused report is not described as one with no fixes or no attention', () => {
    // "no fix offered" and "0 need attention" are claims about a report; the page cannot make them
    // about one it refused to read.
    const out = board(planted);
    expect(out).not.toContain('no fix offered');
    expect(out).not.toMatch(/\d+ need attention/);
  });

  test('the refusal names where and which shape, never what', () => {
    const where = leaksIn(planted).map((leak) => `${leak.kind} at ${leak.where}`);
    expect(where).toEqual(expect.arrayContaining(['key-value at checks[0].detail', 'bearer at checks[1].detail']));
    const out = board(planted);
    for (const line of where) expect(out).toContain(line);
  });
});

describe('the CLI\'s own mask is not a leak', () => {
  // The stack's api-key head, exactly as the daemon serves it: the verdict's sentence and fix
  // (DoctorAuthVerdict.kt:18-20), the fix riding in the detail (DoctorReportShape.kt:59), and the
  // CLI's key=value pass masking the fix's `…` (DoctorRedaction.kt:65). This is checks[28].detail on
  // the e2e stack, which the console refused as `key-value`.
  const STACK_DETAIL = `CONSOLE_E2E_NO_SUCH_KEY is not set${SEP}export CONSOLE_E2E_NO_SUCH_KEY=<redacted>   then: splice restart`;

  test('the stack\'s masked check passes the gate', () => {
    expect(leaksInText(STACK_DETAIL)).toEqual([]);
    expect(isRedacted(report([check('auth/e2e-openrouter', 'fail', STACK_DETAIL)]))).toBe(true);
  });

  test('every form the CLI writes its mask in passes', () => {
    for (const text of [
      'api_key=<redacted>',
      'API_KEY=<redacted> is the value',
      '{"refresh_token": "<redacted>", "id": 3}',
      'Bearer <redacted>',
      // "Authorization: Bearer x" after both passes: bearer masks the token, then key=value masks
      // the word Bearer that now stands where the value was.
      'Authorization: <redacted> <redacted>',
    ]) {
      expect(leaksInText(text), text).toEqual([]);
    }
  });

  test('a real value beside the mask still refuses', () => {
    expect(leaksInText('A_KEY=<redacted> B_TOKEN=hunter2')).toContain('key-value');
    // Written flush against the mask, so it rides inside the greedy match the mask excused.
    expect(leaksInText('A_KEY=<redacted>"B_TOKEN=hunter2')).toContain('key-value');
    expect(leaksInText('Bearer <redacted> then Bearer abc123')).toContain('bearer');
    expect(isRedacted(report([check('auth/e2e-openrouter', 'fail', `${STACK_DETAIL} OPENAI_API_KEY=hunter2`)]))).toBe(false);
  });

  test('only the exact mask is excused', () => {
    expect(leaksInText('api_key=<REDACTED>')).toContain('key-value');
    expect(leaksInText('api_key=<redacted>tail')).toContain('key-value');
    expect(leaksInText('api_key=<redacted>,B_TOKEN=hunter2')).toContain('key-value');
    expect(leaksInText('api_key=redacted')).toContain('key-value');
    expect(leaksInText('Bearer <redacted>x')).toContain('bearer');
  });

  test('the masked report renders its checks, not a refusal', () => {
    const out = board(report([check('auth/e2e-openrouter', 'fail', STACK_DETAIL)]));
    expect(out).not.toContain('report refused');
    expect(out).toContain('auth/e2e-openrouter');
  });
});

describe('the upgrade section prints the live strip only', () => {
  test('the stale V4-127 upgrade empty is gone', () => {
    // GET /api/upgrade is served and the version strip reads it; an empty saying the status is not
    // built, printed beside that strip, contradicted it.
    expect(Object.keys(EMPTIES)).not.toContain('upgrade');
    expect(board(report([]))).not.toContain('upgrade status not built');
  });
});
