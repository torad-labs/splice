import type { ObjectValue } from "./types";

/** Explicit protocol failures sent by the fixed peer; these are never CallToolResult payloads. */
export const mcpRpcErrorVectors: ReadonlyArray<{ id: string; error: ObjectValue }> = [
  { id: "object", error: { code: -32001, message: "Fixed fixture RPC failure", data: { fixture: true } } },
  { id: "absent", error: { code: -32603, message: "Absent data" } },
  { id: "null", error: { code: -32603, message: "Null data", data: null } },
  { id: "order", error: { code: -32602, message: "Ordered data", data: { zeta: 1, alpha: { beta: false, alpha: "λ" } } } },
  { id: "array", error: { code: -32600, message: "Array data", data: [false, 0, "", null, { z: 2, a: 1 }] } },
  { id: "string", error: { code: -32700, message: "String data", data: "quote\" newline\n snow雪" } },
  { id: "multiline", error: { code: 0, message: "first\nsecond\r\n\nlast\n", data: { line: "x\ny" } } },
  { id: "empty", error: { code: 1, message: "", data: {} } },
  { id: "false", error: { code: -1, message: "False data", data: false } },
  { id: "zero", error: { code: 2147483647, message: "Maximum code", data: 0 } },
  { id: "minimum", error: { code: -2147483648, message: "Minimum code", data: [] } },
  { id: "unicode", error: { code: 42, message: "雪🙂\t\"\\", data: { separator: "\u2028\u2029", control: "\b\f\r\t", value: 1.5e20 } } },
];

export function mcpRpcError(name: unknown, argumentsValue: unknown): ObjectValue | undefined {
  if (name !== "echo" || !argumentsValue || typeof argumentsValue !== "object" || Array.isArray(argumentsValue)) return undefined;
  const value = (argumentsValue as ObjectValue).value;
  const vector = mcpRpcErrorVectors.find(vector => value === `rpc-error-probe/${vector.id}` || value === "rpc-error-probe" && vector.id === "object");
  return vector ? structuredClone(vector.error) : undefined;
}
