// OTLP protobuf field numbers pinned to opentelemetry-proto v1.5.0; see README sources.
export type OtlpSignal = 'metrics' | 'traces' | 'logs';
interface Field { number: number; wire: number; value: bigint | Uint8Array }
class Budget { fields = 0; records = 0; }
function fields(bytes: Uint8Array, budget: Budget): Field[] {
  const result: Field[] = []; let offset = 0;
  const varint = () => { let n = 0n; for (let i = 0; i < 10; i++) { const b = bytes[offset++]; if (b === undefined) throw new Error('otlp_truncated_varint'); n |= BigInt(b & 127) << BigInt(i * 7); if (!(b & 128)) return n; } throw new Error('otlp_varint_limit'); };
  while (offset < bytes.length) {
    if (++budget.fields > 100000) throw new Error('otlp_field_limit');
    const key = Number(varint()), number = Math.floor(key / 8), wire = key % 8;
    if (!Number.isSafeInteger(key) || number < 1) throw new Error('otlp_field_invalid');
    if (wire === 0) result.push({ number, wire, value: varint() });
    else {
      const size = wire === 1 ? 8 : wire === 5 ? 4 : wire === 2 ? Number(varint()) : -1;
      if (!Number.isSafeInteger(size) || size < 0 || offset + size > bytes.length) throw new Error('otlp_wire_invalid');
      result.push({ number, wire, value: bytes.subarray(offset, offset + size) }); offset += size;
    }
  }
  return result;
}
const data = (values: Field[], field: number) => values.filter(v => v.number === field && v.value instanceof Uint8Array).map(v => v.value as Uint8Array);
const one = (values: Field[], field: number) => data(values, field)[0];
const string = (bytes?: Uint8Array) => bytes ? new TextDecoder('utf-8', { fatal: true }).decode(bytes) : '';
const uint64 = (bytes?: Uint8Array) => bytes?.length === 8 ? new DataView(bytes.buffer, bytes.byteOffset, 8).getBigUint64(0, true) : null;
const double = (bytes: Uint8Array) => bytes.length === 8 ? new DataView(bytes.buffer, bytes.byteOffset, 8).getFloat64(0, true) : NaN;
const fail = (condition: unknown, code: string) => { if (!condition) throw new Error(code); };
const safeId = (bytes: Uint8Array | undefined, length: number) => bytes?.length === length && bytes.some(n => n !== 0);
function attributeKeys(values: Field[], id: number, budget: Budget): Set<string> {
  const keys = new Set<string>();
  for (const pair of data(values, id)) { const kv = fields(pair, budget); const key = string(one(kv, 1)); fail(/^[a-zA-Z][a-zA-Z0-9_.]{0,127}$/.test(key) && !keys.has(key), 'otlp_attribute_invalid'); keys.add(key); }
  return keys;
}
function resource(bytes: Uint8Array | undefined, budget: Budget) {
  fail(bytes, 'otlp_resource_missing'); const resourceFields = fields(bytes!, budget);
  let service = false;
  for (const pair of data(resourceFields, 1)) { const kv = fields(pair, budget); if (string(one(kv, 1)) === 'service.name') { const value = one(kv, 2); if (value) service = string(one(fields(value, budget), 1)) === 'splice'; } }
  fail(service, 'otlp_service_identity_missing');
}
function metric(item: Field[], budget: Budget, timestamp: (n: bigint) => void, keys: Set<string>) {
  const name = string(one(item, 1)); fail(/^splice_[a-zA-Z0-9_]{1,100}$/.test(name), 'otlp_metric_name_invalid');
  const kinds = [5, 7, 9].filter(key => one(item, key)); fail(kinds.length === 1, 'otlp_metric_type_unsupported');
  const kind = kinds[0]!; const points = data(fields(one(item, kind)!, budget), 1); fail(points.length > 0, 'otlp_metric_points_missing');
  for (const bytes of points) {
    const point = fields(bytes, budget); const at = uint64(one(point, 3)); fail(at !== null && at > 0, 'otlp_metric_timestamp_invalid'); timestamp(at!);
    for (const key of attributeKeys(point, kind === 9 ? 9 : 7, budget)) keys.add(key);
    if (kind === 9) {
      const count = uint64(one(point, 4)) ?? 0n;
      const packed = one(point, 6), bounds = one(point, 7);
      if (packed || bounds) {
        fail(packed && packed.length % 8 === 0 && (!bounds || bounds.length % 8 === 0) && packed.length / 8 === (bounds?.length ?? 0) / 8 + 1, 'otlp_histogram_shape_invalid');
        let total = 0n; for (let offset = 0; offset < packed!.length; offset += 8) total += uint64(packed!.subarray(offset, offset + 8))!;
        fail(total === count, 'otlp_histogram_count_mismatch');
        let prior = -Infinity; if (bounds) for (let offset = 0; offset < bounds.length; offset += 8) { const bound = double(bounds.subarray(offset, offset + 8)); fail(Number.isFinite(bound) && bound > prior, 'otlp_histogram_bounds_invalid'); prior = bound; }
      }
    } else { const floating = one(point, 4), integer = one(point, 6); fail(Boolean(floating) !== Boolean(integer), 'otlp_metric_value_missing'); if (floating) fail(Number.isFinite(double(floating)), 'otlp_metric_value_invalid'); }
  }
  return { name, points: points.length };
}
export interface OtlpEvidence { signal: OtlpSignal; records: number; points: number; metric_names: string[]; attribute_keys: string[]; first_epoch_ns: string | null; last_epoch_ns: string | null; protocol_revision: string }
export function decodeOtlp(bytes: Uint8Array, signal: OtlpSignal): OtlpEvidence {
  const budget = new Budget(); const root = fields(bytes, budget); const names = new Set<string>(), keys = new Set<string>(); let points = 0; let first: bigint | null = null, last: bigint | null = null;
  const timestamp = (n: bigint) => { first = first === null || n < first ? n : first; last = last === null || n > last ? n : last; };
  for (const resourceBytes of data(root, 1)) {
    const r = fields(resourceBytes, budget); resource(one(r, 1), budget);
    for (const scopeBytes of data(r, 2)) {
      const scope = fields(scopeBytes, budget); const info = one(scope, 1); fail(info && string(one(fields(info, budget), 1)) === 'splice.operator', 'otlp_scope_invalid');
      for (const record of data(scope, 2)) {
        if (++budget.records > 20000) throw new Error('otlp_record_limit');
        const item = fields(record, budget);
        if (signal === 'metrics') { const parsed = metric(item, budget, timestamp, keys); names.add(parsed.name); points += parsed.points; }
        else if (signal === 'traces') {
          fail(safeId(one(item, 1), 16) && safeId(one(item, 2), 8), 'otlp_span_identity_invalid');
          const from = uint64(one(item, 7)), until = uint64(one(item, 8)); fail(from !== null && until !== null && from > 0 && until >= from, 'otlp_span_interval_invalid');
          fail(/^splice[._][a-zA-Z0-9_.-]{1,100}$/.test(string(one(item, 5))), 'otlp_span_name_invalid');
          timestamp(from!); timestamp(until!); for (const key of attributeKeys(item, 9, budget)) keys.add(key);
        } else {
          const at = uint64(one(item, 1)); fail(at !== null && at > 0, 'otlp_log_timestamp_invalid'); timestamp(at!);
          const body = one(item, 5); fail(body && /^[A-Z_]{1,64}$/.test(string(one(fields(body, budget), 1))), 'otlp_log_body_not_lifecycle');
          for (const key of attributeKeys(item, 6, budget)) keys.add(key);
        }
      }
    }
  }
  fail(budget.records > 0, 'otlp_records_missing'); fail(names.size <= 256 && keys.size <= 128, 'otlp_cardinality_limit');
  return { signal, records: budget.records, points, metric_names: [...names].sort(), attribute_keys: [...keys].sort(), first_epoch_ns: first === null ? null : String(first), last_epoch_ns: last === null ? null : String(last), protocol_revision: 'opentelemetry-proto-v1.5.0-supported-subset' };
}
