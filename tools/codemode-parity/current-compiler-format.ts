/** The pinned rustc pretty-debug format is parsed structurally; Rust source names are never searched. */
// crateHash is rustc's shortened Debug suffix. Only a proof-scoped key is an identity across files.
export type CompilerDef = { key: string; raw: string; crate: string; crateHash: string; crateNumber: number; index: number; path: string };
export type CompilerSpan = { file: string; line: number; column: number; endLine: number; endColumn: number; context: number };
export type CompilerFrame = {
  indent: number; type: string; key?: string; close: string; owner: CompilerDef;
  fields: Map<string, string>; values: string[]; children: Map<string, string>;
};
export type CompilerVisitor = {
  open?(frame: CompilerFrame, parents: readonly CompilerFrame[]): void;
  scalar?(frame: CompilerFrame, key: string | undefined, value: string, parents: readonly CompilerFrame[]): void;
  close(frame: CompilerFrame, parents: readonly CompilerFrame[]): void;
};

export function compilerDef(text: string): CompilerDef | undefined {
  const match = /^DefId\((\d+):(\d+) ~ ([A-Za-z_][A-Za-z_0-9]*)\[([0-9a-f]+)\](?:::(.*))?\)$/.exec(text);
  if (!match) return;
  const crateNumber = Number(match[1]), index = Number(match[2]);
  if (![crateNumber, index].every(Number.isSafeInteger)) throw new Error("compiler definition index is invalid");
  const crate = match[3]!, crateHash = match[4]!, path = match[5] ?? "";
  return { key: `${crateNumber}:${crate}[${crateHash}]:${index}:${path}`, raw: text, crate, crateHash, crateNumber, index, path };
}

export function compilerSpan(text: string | undefined): CompilerSpan | undefined {
  if (!text || text.startsWith("no-location ")) return;
  const match = /^(.*):(\d+):(\d+): (\d+):(\d+) \(#(\d+)\)$/.exec(text);
  if (!match) throw new Error("unsupported compiler source span");
  const numbers = match.slice(2).map(Number);
  if (numbers.some(value => !Number.isSafeInteger(value) || value < 0) || numbers.slice(0, 4).some(value => value < 1)) {
    throw new Error("invalid compiler source span");
  }
  return { file: match[1]!, line: numbers[0]!, column: numbers[1]!, endLine: numbers[2]!, endColumn: numbers[3]!, context: numbers[4]! };
}

/** Frames are released after their closing delimiter; memory does not grow with the compiler tree. */
export async function walkCompilerDebug(lines: AsyncIterable<string>, mode: "hir" | "thir", visitor: CompilerVisitor): Promise<void> {
  const stack: CompilerFrame[] = [];
  let owner: CompilerDef | undefined, roots = 0;
  for await (const line of lines) {
    const trimmed = line.trim();
    if (!trimmed) continue;
    if (line.startsWith("DefId(")) {
      if (stack.length) throw new Error("compiler owner changed inside an unfinished tree");
      const suffix = mode === "hir" ? " => OwnerNodes {" : ":";
      if (!line.endsWith(suffix)) throw new Error("unsupported compiler owner header");
      owner = compilerDef(line.slice(0, -suffix.length));
      if (!owner) throw new Error("invalid compiler owner definition");
      roots++;
      if (mode === "thir") continue;
      const frame: CompilerFrame = { indent: 0, type: "OwnerNodes", close: "}", owner, fields: new Map(), values: [], children: new Map() };
      visitor.open?.(frame, stack); stack.push(frame); continue;
    }
    if (!owner) throw new Error("compiler output has no owner header");
    const indent = line.length - line.trimStart().length;
    if (indent % 4) throw new Error("unsupported compiler indentation");
    if (["}", "},", "]", "],", ")", "),"].includes(trimmed)) {
      const frame = stack.pop();
      if (!frame || frame.indent !== indent || frame.close !== trimmed[0]) throw new Error("unbalanced compiler tree");
      visitor.close(frame, stack);
      continue;
    }
    const field = /^([A-Za-z_][A-Za-z_0-9]*): (.*)$/.exec(trimmed);
    const key = field?.[1], value = field?.[2] ?? trimmed;
    const opening = value.at(-1);
    if (opening && ["{", "[", "("].includes(opening)) {
      if (stack.length && indent <= stack.at(-1)!.indent) throw new Error("invalid compiler child indentation");
      const type = value.slice(0, -1).trim() || (opening === "[" ? "array" : "tuple");
      const frame: CompilerFrame = { indent, type, key, close: ({ "{": "}", "[": "]", "(": ")" } as Record<string, string>)[opening]!, owner,
        fields: new Map(), values: [], children: new Map() };
      if (key) stack.at(-1)?.children.set(key, type);
      else if (!stack.at(-1)?.children.has("$first")) stack.at(-1)?.children.set("$first", type);
      visitor.open?.(frame, stack); stack.push(frame); continue;
    }
    const frame = stack.at(-1);
    if (!frame || indent <= frame.indent) throw new Error("compiler scalar is outside its tree");
    const scalar = value.endsWith(",") ? value.slice(0, -1) : value;
    visitor.scalar?.(frame, key, scalar, stack.slice(0, -1));
    if (key) {
      if (frame.fields.has(key)) throw new Error("duplicate compiler field");
      frame.fields.set(key, scalar);
    } else if (frame.values.length < 8) frame.values.push(scalar);
  }
  if (stack.length || !roots) throw new Error("incomplete compiler output");
}

export async function* compilerTextLines(text: string): AsyncIterable<string> {
  for (const line of text.split(/\r?\n/)) yield line;
}
