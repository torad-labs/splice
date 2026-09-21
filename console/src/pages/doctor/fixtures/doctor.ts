// Design-capture fixture. Loaded ONLY when import.meta.env.DEV is true and the address carries
// `?fixture=demo` (CONTRACTS.md section 4); the shipped dist contains none of these bytes.
//
// It exists because GET /api/doctor is still V4-127: without it the doctor page's capture is one
// honest empty and nothing of the design, which is a true picture of today but a useless one to
// review. The sample exercises the branches the page must render: a failing check with a remedy, a
// warning with one, a passing check, an informational check with none, two sections so the
// grouping shows, and — deliberately — a check whose detail carries a masked-shape string that is
// NOT a credential, so the redaction gate is exercised without being tripped.
import type { DoctorPayload } from '@entities/doctor';

const SEP = ` ${String.fromCharCode(0x2014)} fix: `;

const FIXTURE_NOW = '2026-09-18T07:45:00Z';

const DEMO_REPORT: DoctorPayload = {
  schema_version: 1,
  generated_at: FIXTURE_NOW,
  splice: { version: '0.4.0' },
  claude_code: { version: '2.1.257' },
  os: { name: 'Linux', version: '6.17.0-41-generic', arch: 'amd64' },
  jvm: { version: '21.0.11', vendor: 'Eclipse Adoptium' },
  topology: { stale: false },
  checks: [
    { id: 'daemon/port', status: 'ok', detail: 'control plane is listening on 3096' },
    { id: 'daemon/topology', status: 'ok', detail: 'the booted splice.toml matches the file on disk' },
    { id: 'heads/running', status: 'warn', detail: `2 of 7 heads are stopped${SEP}splice heads --start` },
    { id: 'heads/version', status: 'ok', detail: 'every running head is on 0.4.0' },
    { id: 'auth/codex', status: 'ok', detail: 'the Codex credential refreshes normally' },
    { id: 'auth/grok', status: 'fail', detail: `the Grok refresh latch is set${SEP}splice login claude-grok` },
    { id: 'mcp/hosted', status: 'info', detail: '3 of 10 servers are hosted; the rest declare a transport splice cannot share' },
    { id: 'perf/perf-files', status: 'warn', detail: `claudex-perf.jsonl.1 is a rolled generation at 67 MB${SEP}splice doctor --json` },
    { id: 'env/path', status: 'info', detail: 'claude on PATH resolves to the versioned binary under ~/.local/share/claude' },
    { id: 'logs/tail', status: 'ok', detail: 'the daemon log tail holds 1200 lines' },
  ],
  accounts: { pooled: true },
  perf: { window: '24h' },
};

/** The report for a fixture name, or null. DEV-guarded HERE, in the module that holds the data, so
 *  a production build drops the whole payload rather than shipping rows nothing can reach. */
export function fixtureDoctor(name: string | null): DoctorPayload | null {
  if (!import.meta.env.DEV || name === null) return null;
  // The fixture's own FILE name (CONTRACTS.md section 4): one vocabulary for every page, so a
  // driver's table is the directory listing and nothing else.
  return name === 'doctor' ? DEMO_REPORT : null;
}
