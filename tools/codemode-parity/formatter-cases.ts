import assert from "node:assert/strict";
import { readFile, writeFile, mkdir } from "node:fs/promises";
import { resolve } from "node:path";
import { artifact, sha256, type CaseEvidence } from "./qualification";

const sourceRoot = "/home/marcos/Documents/dev/infra/coding/codex";
const revision = "17a9df60e420b58e3edc55efb1bb052e39492bbc";
type Item = { type: string; text?: string; image_url?: string; detail?: string; audio_url?: string };
const text = (value: string): Item => ({ type: "input_text", text: value });
const image = (detail = "high"): Item => ({ type: "input_image", image_url: "data:image/png;base64,aGVsbG8=", detail });
const vectors = [
  { content: [], maxOutputTokens: 20 },
  { content: [text("short")], maxOutputTokens: 20 },
  { content: [text("x".repeat(20_000))], maxOutputTokens: 20 },
  { content: [text("🙂é中λ\n".repeat(100))], maxOutputTokens: 23 },
  { content: [text("retained?"), text("second")], maxOutputTokens: 0 },
  { content: [text("first\n".repeat(30)), text("second\n".repeat(30))], maxOutputTokens: 10 },
  { content: [image(), text("middle".repeat(100)), image("original"), text("last")], maxOutputTokens: 8 },
  { content: [text(""), image(), text("tail")], maxOutputTokens: 0 },
  { content: [text("audio-before"), { type: "input_audio", audio_url: "invalid-audio-fixture" }, text("audio-after")], maxOutputTokens: 6 },
  { content: [image("original"), text("original-support")], maxOutputTokens: 100 },
] as Array<{ content: Item[]; maxOutputTokens: number }>;
function fromHex(value = ""): string { return Buffer.from(value, "hex").toString("utf8"); }
function encode(items: Item[]): string[] {
  const hex = (value = "") => Buffer.from(value).toString("hex");
  return items.map(item => item.type === "input_text" ? `t:${hex(item.text)}` : item.type === "input_image" ?
    `i:${hex(item.image_url)}:${hex(item.detail)}` : `a:${hex(item.audio_url)}`);
}
function decode(output: string): Item[][] {
  const results = vectors.map(() => [] as Item[]);
  for (const line of output.trim().split("\n")) {
    const [index, kind, bytes, detail] = line.split("\t");
    const items = results[Number(index)]; assert.ok(items, "oracle returned an unknown vector");
    if (kind === "truncated") continue;
    if (kind === "t") items.push(text(fromHex(bytes)));
    else if (kind === "i") items.push({ type: "input_image", image_url: fromHex(bytes), ...(detail ? { detail: fromHex(detail) } : {}) });
    else if (kind === "a") items.push({ type: "input_audio", audio_url: fromHex(bytes) });
    else throw new Error(`unknown Rust oracle item ${kind}`);
  }
  return results;
}
async function command(argv: string[], input?: string): Promise<{ argv: string[]; stdout: string; stderr: string; exitCode: number }> {
  const child = Bun.spawn(argv, { stdin: input === undefined ? "ignore" : "pipe", stdout: "pipe", stderr: "pipe" });
  if (input !== undefined && child.stdin && typeof child.stdin !== "number") { child.stdin.write(input); child.stdin.end(); }
  const timer = setTimeout(() => child.kill("SIGKILL"), 20_000);
  try {
    const [stdout, stderr, exitCode] = await Promise.all([new Response(child.stdout).text(), new Response(child.stderr).text(), child.exited]);
    return { argv, stdout, stderr, exitCode };
  } finally { clearTimeout(timer); }
}
async function upstream(path: string): Promise<string> {
  const result = await command(["git", "-C", sourceRoot, "show", `${revision}:${path}`]);
  assert.equal(result.exitCode, 0, result.stderr);
  return result.stdout;
}
function policy(source: string): string {
  const enumeration = source.slice(source.indexOf("pub enum TruncationPolicy {"));
  const methods = source.slice(source.indexOf("impl TruncationPolicy {"), source.indexOf("impl Mul<f64> for TruncationPolicy {"));
  const multiply = source.slice(source.indexOf("impl Mul<f64> for TruncationPolicy {"));
  assert.ok(enumeration.startsWith("pub enum TruncationPolicy {")); assert.ok(methods.startsWith("impl TruncationPolicy {"));
  return "use std::ops::Mul;\n#[derive(Clone, Copy)]\n" + enumeration.slice(0, enumeration.indexOf("\n}") + 2) +
    "\n" + methods + multiply.slice(0, multiply.indexOf("\n}") + 2) + "\n";
}
export async function runFormatterCases(java: string, jar: string, evidence: string, runId: string, cases: CaseEvidence[]): Promise<void> {
  const family = cases.find(item => item.id === "runtime.output-content")!;
  const nativeHostFindings = [...family.findings];
  const directory = resolve(evidence, `${runId}.native-core-formatter`);
  await mkdir(directory, { recursive: true });
  const findings: string[] = [];
  try {
    const files = [
      ["native-string.rs", await upstream("codex-rs/utils/string/src/truncate.rs")],
      ["native-output.rs", await upstream("codex-rs/utils/output-truncation/src/lib.rs")],
      ["native-policy.rs", policy(await upstream("codex-rs/protocol/src/protocol.rs"))],
      ["oracle.rs", await readFile(resolve(import.meta.dir, "rust-oracle.rs"), "utf8")],
    ];
    for (const [name, source] of files) {
      const path = resolve(directory, name!); await writeFile(path, source!); family.artifacts.push(await artifact(path));
    }
    const binary = resolve(directory, "native-oracle");
    const compilation = await command(["rustc", "--edition=2021", "--crate-name", "parity_native_oracle", resolve(directory, "oracle.rs"), "-o", binary]);
    await writeFile(resolve(directory, "compilation.json"), JSON.stringify(compilation, null, 2) + "\n");
    family.artifacts.push(await artifact(resolve(directory, "compilation.json")));
    assert.equal(compilation.exitCode, 0, compilation.stderr);
    family.artifacts.push(await artifact(binary));
    const input = vectors.map((vector, index) => [index, vector.maxOutputTokens, ...encode(vector.content)].join("\t")).join("\n") + "\n";
    const native = await command([binary], input); assert.equal(native.exitCode, 0, native.stderr);
    const expected = decode(native.stdout);
    const candidateInputs = vectors.map(vector => ({ kind: "completed", wallMillis: 0, supportsOriginal: true, ...vector }));
    const candidateInput = candidateInputs.map(value => JSON.stringify(value)).join("\n") + "\n";
    const candidate = await command([java, "-cp", jar, "splice.app.diagnostics.CodeModeParityProbe", "--format-fixture", runId], candidateInput);
    const path = resolve(directory, "comparison.json");
    await writeFile(path, JSON.stringify({ schema: 1, runId, sourceRevision: revision, binaryToStandaloneLinkage: "unproven",
      sourcePolicy: "Exact upstream algorithms and policy methods; protocol data types only are shimmed. Audio fixture uses invalid-URL byte-count fallback.",
      sourceHashes: files.map(([name, source]) => ({ name, sha256: sha256(source!) })), input, native, candidateInput, candidate, expected }, null, 2) + "\n");
    family.artifacts.push(await artifact(path));
    assert.equal(candidate.exitCode, 0, candidate.stderr);
    const rows = candidate.stdout.trim().split("\n").map(line => JSON.parse(line));
    assert.equal(rows.length, vectors.length);
    for (let index = 0; index < rows.length; index++) {
      assert.equal(rows[index].nonce, runId); assert.deepEqual(rows[index].input, candidateInputs[index]);
      assert.deepEqual(rows[index].content, [text("Script completed\nWall time 0.0 seconds\nOutput:\n"), ...expected[index]!], `native-core formatter vector ${index}`);
    }
    findings.push("Native host media and executed upstream formatter agree; provider persistence of typed content remains unexecuted.");
  } catch (error) { findings.push(error instanceof Error ? error.message : String(error)); }
  family.findings = [...nativeHostFindings.filter(value => !value.startsWith("Typed media runs")), ...findings];
  family.state = family.findings.length ? "failed" : "executed";
  console.log(`UNQUALIFIED ${family.id}: ${family.findings[0]?.split("\n")[0] ?? "See raw comparison."}`);
}
