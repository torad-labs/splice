// OTLP export requests as Claude Code sends them, in both encodings: JSON and protobuf. Just enough of the protobuf
// wire format to write a request with resources and records, and to read back each resource's attributes and the
// bytes of its records, so a check can see what a relay changed and what it carried through.

export type Signal = "logs" | "metrics" | "traces";

/** One resource of a request: its string attributes, and the record whose text marks it ([mark]). */
export interface Res {
  attributes: Record<string, string>;
  mark: string;
}

const ROOT: Record<Signal, string> = { logs: "resourceLogs", metrics: "resourceMetrics", traces: "resourceSpans" };
const SCOPES: Record<Signal, string> = { logs: "scopeLogs", metrics: "scopeMetrics", traces: "scopeSpans" };
const SCOPE_NAME = "com.anthropic.claude_code";

/** The JSON body of an export request of [signal] holding [resources]. */
export function jsonBody(signal: Signal, resources: Res[]): string {
  const record = (mark: string) =>
    signal === "logs"
      ? { logRecords: [{ timeUnixNano: "1700000000000000000", body: { stringValue: mark } }] }
      : signal === "metrics"
        ? { metrics: [{ name: mark, sum: { dataPoints: [{ asInt: "1", timeUnixNano: "1700000000000000000" }] } }] }
        : { spans: [{ traceId: "0af7651916cd43dd8448eb211c80319c", spanId: "b7ad6b7169203331", name: mark, kind: 1 }] };
  return JSON.stringify({
    [ROOT[signal]]: resources.map((r) => ({
      resource: { attributes: Object.entries(r.attributes).map(([key, v]) => ({ key, value: { stringValue: v } })) },
      [SCOPES[signal]]: [{ scope: { name: SCOPE_NAME }, ...record(r.mark) }],
    })),
  });
}

/** Each resource of a JSON request: its attributes (a key held twice reads as "<a>|<b>"), and its records' text. */
export function readJson(signal: Signal, body: string): { attributes: Record<string, string>; text: string }[] {
  const parsed = JSON.parse(body) as Record<string, { resource?: { attributes?: { key: string; value: { stringValue?: string } }[] } }[]>;
  return (parsed[ROOT[signal]] ?? []).map((r) => {
    const attributes: Record<string, string> = {};
    for (const a of r.resource?.attributes ?? []) {
      const v = a.value.stringValue ?? JSON.stringify(a.value);
      attributes[a.key] = a.key in attributes ? `${attributes[a.key]}|${v}` : v;
    }
    return { attributes, text: JSON.stringify(r) };
  });
}

// ── protobuf ─────────────────────────────────────────────────────────────────────────────────────────────────────

const varint = (n: number): number[] => {
  const out: number[] = [];
  let v = n;
  while (v > 0x7f) {
    out.push((v & 0x7f) | 0x80);
    v = Math.floor(v / 128);
  }
  out.push(v);
  return out;
};
const bytesField = (field: number, payload: Uint8Array): number[] => [...varint((field << 3) | 2), ...varint(payload.length), ...payload];
const strField = (field: number, s: string): number[] => bytesField(field, new TextEncoder().encode(s));
const msg = (...parts: number[][]): Uint8Array => Uint8Array.from(parts.flat());
const hex = (h: string): Uint8Array => Uint8Array.from(h.match(/../g)!.map((b) => parseInt(b, 16)));

/** The protobuf body of an export request of [signal] holding [resources]. */
export function protoBody(signal: Signal, resources: Res[]): Uint8Array {
  const anyString = (s: string) => msg(strField(1, s));
  const record = (mark: string): Uint8Array =>
    signal === "logs"
      ? msg([0x09, 0, 0, 0, 0, 0, 0, 0, 1], bytesField(5, anyString(mark)))
      : signal === "metrics"
        ? msg(strField(1, mark))
        : msg(bytesField(1, hex("0af7651916cd43dd8448eb211c80319c")), bytesField(2, hex("b7ad6b7169203331")), strField(5, mark));
  return msg(
    ...resources.map((r) => {
      const attrs = Object.entries(r.attributes).map(([k, v]) => bytesField(1, msg(strField(1, k), bytesField(2, anyString(v)))));
      const resource = msg(...attrs);
      const scope = msg(bytesField(1, msg(strField(1, SCOPE_NAME))), bytesField(2, record(r.mark)));
      return bytesField(1, msg(bytesField(1, resource), bytesField(2, scope)));
    }),
  );
}

interface Field {
  no: number;
  wire: number;
  bytes: Uint8Array;
}

/** The top-level fields of one protobuf message; a length-delimited field's bytes are its payload. */
function fields(buf: Uint8Array): Field[] {
  const out: Field[] = [];
  let i = 0;
  const readVarint = (): number => {
    let v = 0;
    let mul = 1;
    for (;;) {
      const b = buf[i++]!;
      v += (b & 0x7f) * mul;
      if ((b & 0x80) === 0) return v;
      mul *= 128;
    }
  };
  while (i < buf.length) {
    const tag = readVarint();
    const no = Math.floor(tag / 8);
    const wire = tag & 7;
    const start = i;
    if (wire === 0) readVarint();
    else if (wire === 1) i += 8;
    else if (wire === 5) i += 4;
    else if (wire === 2) {
      const n = readVarint();
      out.push({ no, wire, bytes: buf.subarray(i, i + n) });
      i += n;
      continue;
    } else throw new Error(`protobuf wire type ${wire} is not one OTLP uses`);
    out.push({ no, wire, bytes: buf.subarray(start, i) });
  }
  return out;
}

/** Each resource of a protobuf request: its string attributes (a key held twice reads as "<a>|<b>"), and the text of
 *  its records, as Latin-1 so a mark can be found in it. */
export function readProto(body: Uint8Array): { attributes: Record<string, string>; text: string }[] {
  const dec = new TextDecoder();
  return fields(body)
    .filter((f) => f.no === 1 && f.wire === 2)
    .map((rx) => {
      const attributes: Record<string, string> = {};
      let text = "";
      for (const part of fields(rx.bytes)) {
        if (part.no === 1 && part.wire === 2) {
          for (const kv of fields(part.bytes).filter((f) => f.no === 1 && f.wire === 2)) {
            const kvFields = fields(kv.bytes);
            const k = dec.decode(kvFields.find((f) => f.no === 1)?.bytes ?? new Uint8Array());
            const anyValue = kvFields.find((f) => f.no === 2)?.bytes ?? new Uint8Array();
            const s = fields(anyValue).find((f) => f.no === 1 && f.wire === 2);
            const v = s ? dec.decode(s.bytes) : `<non-string>`;
            attributes[k] = k in attributes ? `${attributes[k]}|${v}` : v;
          }
        } else if (part.no === 2) text += Buffer.from(part.bytes).toString("latin1");
      }
      return { attributes, text };
    });
}
