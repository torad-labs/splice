import type { Manifest } from './manifest';
import type { Sample } from './observe';

export type WireFingerprintScheme = 'raw-wire-v1' | 'anthropic-sse-message-id-v1' | 'anthropic-json-message-id-v1';

export function wireFingerprintScheme(m: Pick<Manifest, 'protocol' | 'streaming' | 'transport' | 'target'>): WireFingerprintScheme {
  if (m.target.kind === 'proxy' && m.protocol === 'anthropic' && m.streaming && m.transport === 'sse') return 'anthropic-sse-message-id-v1';
  if (m.target.kind === 'proxy' && m.protocol === 'anthropic' && !m.streaming && m.transport === 'http') return 'anthropic-json-message-id-v1';
  return 'raw-wire-v1';
}

const sha256 = /^[0-9a-f]{64}$/;
const generatedMessageId = /^msg_[0-9]{13}_[1-9][0-9]*$/;

type FingerprintRow = Pick<Sample, 'phase' | 'outcome' | 'wire_sha256' | 'wire_fingerprint_sha256' |
  'wire_fingerprint_scheme' | 'wire_fingerprint_valid' | 'wire_message_id' | 'content_sha256' | 'tokens'>;

/** Compare stable wire evidence without hiding any field except Splice's documented per-turn Anthropic message ID. */
export function compareWireFingerprints(
  baselineManifest: Pick<Manifest, 'protocol' | 'streaming' | 'transport' | 'target'>,
  baselineRows: readonly FingerprintRow[],
  candidateManifest: Pick<Manifest, 'protocol' | 'streaming' | 'transport' | 'target'>,
  candidateRows: readonly FingerprintRow[],
): 'equal' | 'changed' | 'invalid' {
  const baselineScheme = wireFingerprintScheme(baselineManifest);
  const candidateScheme = wireFingerprintScheme(candidateManifest);
  if (baselineScheme !== candidateScheme) return 'invalid';
  const project = (rows: readonly FingerprintRow[], scheme: WireFingerprintScheme) => {
    const selected = rows.filter(row => row.phase === 'steady' && row.outcome === 'completed');
    if (selected.length === 0) return null;
    const ids = new Set<string>();
    const signatures: string[] = [];
    for (const row of selected) {
      if (row.wire_fingerprint_valid !== true || row.wire_fingerprint_scheme !== scheme ||
          typeof row.wire_sha256 !== 'string' || !sha256.test(row.wire_sha256) ||
          typeof row.wire_fingerprint_sha256 !== 'string' || !sha256.test(row.wire_fingerprint_sha256) ||
          typeof row.content_sha256 !== 'string' || !sha256.test(row.content_sha256) ||
          typeof row.tokens !== 'number' || !Number.isSafeInteger(row.tokens) || row.tokens < 0) return null;
      if (scheme === 'raw-wire-v1') {
        if (row.wire_fingerprint_sha256 !== row.wire_sha256 || row.wire_message_id !== null) return null;
      } else {
        if (typeof row.wire_message_id !== 'string' || !generatedMessageId.test(row.wire_message_id) || ids.has(row.wire_message_id)) return null;
        ids.add(row.wire_message_id);
      }
      signatures.push(row.wire_fingerprint_sha256 + ':' + row.content_sha256 + ':' + row.tokens);
    }
    return [...new Set(signatures)].sort();
  };
  const baseline = project(baselineRows, baselineScheme);
  const candidate = project(candidateRows, candidateScheme);
  if (baseline === null || candidate === null) return 'invalid';
  return JSON.stringify(baseline) === JSON.stringify(candidate) ? 'equal' : 'changed';
}
