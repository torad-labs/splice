import assert from "node:assert/strict";
import { readFile, writeFile, mkdir } from "node:fs/promises";
import { resolve } from "node:path";
import { spawn } from "node:child_process";
import { artifact, type CaseEvidence } from "./qualification";
import { candidateCommand } from "./candidate-command";

const sourceRoot = "/home/marcos/Documents/dev/infra/coding/codex";
const revision = "17a9df60e420b58e3edc55efb1bb052e39492bbc";
export const audioManifest = `[package]
name = "splice-native-audio-oracle"
version = "0.0.0"
edition = "2024"

[[bin]]
name = "native-audio-oracle"
path = "oracle.rs"

[dependencies]
base64 = "=0.22.1"
symphonia = { version = "=0.6.0", default-features = false, features = ["isomp4", "mkv", "mp3", "ogg", "wav"] }
thiserror = "=2.0.17"
tracing = "=0.1.44"
lru = "=0.18.2"
sha1 = "=0.10.6"
tokio = { version = "1", features = ["sync", "rt", "rt-multi-thread"] }
`;
async function command(argv: string[], input?: string, timeoutMs = 60_000): Promise<any> {
  const child = spawn(argv[0]!, argv.slice(1), { stdio: "pipe" });
  let stdout = "", stderr = "", inputError: string | null = null;
  child.stdout.on("data", bytes => { stdout += bytes.toString(); });
  child.stderr.on("data", bytes => { stderr += bytes.toString(); });
  child.stdin.on("error", error => { inputError = error.message; });
  const timer = setTimeout(() => child.kill("SIGKILL"), timeoutMs);
  const finished = new Promise<any>((resolve, reject) => {
    child.once("error", reject);
    child.once("close", exitCode => resolve({ argv, stdout, stderr, exitCode, inputError }));
  });
  child.stdin.end(input);
  try { return await finished; } finally { clearTimeout(timer); }
}
export async function prepareAudioOracle(directory: string): Promise<any> {
  await mkdir(directory, { recursive: true });
  const sources: Array<[string, string]> = [
    ["native-string.rs", "codex-rs/utils/string/src/truncate.rs"],
    ["native-cache.rs", "codex-rs/utils/cache/src/lib.rs"],
    ["native-audio.rs", "codex-rs/utils/audio/src/lib.rs"],
  ];
  for (const [target, path] of sources) {
    const source = await command(["git", "-C", sourceRoot, "show", `${revision}:${path}`]); assert.equal(source.exitCode, 0, source.stderr);
    await writeFile(resolve(directory, target), source.stdout);
  }
  await writeFile(resolve(directory, "oracle.rs"), await readFile(resolve(import.meta.dir, "audio-oracle.rs")));
  await writeFile(resolve(directory, "Cargo.toml"), audioManifest);
  const build = await command(["cargo", "build", "--manifest-path", resolve(directory, "Cargo.toml")], undefined, 300_000);
  await writeFile(resolve(directory, "build.json"), JSON.stringify(build, null, 2) + "\n");
  assert.equal(build.exitCode, 0, build.stderr);
  return build;
}
type Sample = { name: string; mime: string; path: string };
export async function runAudioCases(java: string, jar: string, evidence: string, runId: string, cases: CaseEvidence[]): Promise<void> {
  const family = cases.find(item => item.id === "runtime.output-content")!;
  const directory = resolve(evidence, `${runId}.native-audio`);
  const samples: Sample[] = [];
  const observations: any[] = [];
  try {
    await prepareAudioOracle(directory);
    for (const [name, mime, codec, extension] of [
      ["wav", "audio/wav", "pcm_s16le", "wav"], ["mp3", "audio/mpeg", "libmp3lame", "mp3"],
      ["m4a", "audio/mp4", "aac", "m4a"], ["ogg", "audio/ogg", "libopus", "ogg"], ["webm", "audio/webm", "libopus", "webm"],
    ]) for (const duration of ["0.11", "1.0", "1.01"]) {
      const path = resolve(directory, `${name}-${duration}.${extension}`);
      const produced = await command(["ffmpeg", "-hide_banner", "-loglevel", "error", "-y", "-f", "lavfi", "-i", "sine=frequency=440:sample_rate=48000",
        "-t", duration!, "-ac", "1", "-c:a", codec!, path]);
      assert.equal(produced.exitCode, 0, produced.stderr);
      samples.push({ name: `${name}-${duration}`, mime: mime!, path });
    }
    const fragmented = resolve(directory, "fragmented.m4a");
    const fragment = await command(["ffmpeg", "-hide_banner", "-loglevel", "error", "-y", "-f", "lavfi", "-i", "sine=frequency=440:sample_rate=48000",
      "-t", "1.0", "-ac", "1", "-c:a", "aac", "-movflags", "frag_keyframe+empty_moov", fragmented]);
    assert.equal(fragment.exitCode, 0, fragment.stderr); samples.push({ name: "fragmented-m4a", mime: "audio/mp4", path: fragmented });
    const chained = resolve(directory, "chained.ogg");
    await writeFile(chained, Buffer.concat([await readFile(samples.find(item => item.name === "ogg-1.0")!.path), await readFile(samples.find(item => item.name === "ogg-0.11")!.path)]));
    samples.push({ name: "chained-ogg", mime: "audio/ogg", path: chained });
    const urls = await Promise.all(samples.map(async sample => `data:${sample.mime};base64,${(await readFile(sample.path)).toString("base64")}`));
    const native = await command([resolve(directory, "target/debug/native-audio-oracle")], urls.map((url, index) => `${index}\t${Buffer.from(url).toString("hex")}`).join("\n") + "\n");
    await writeFile(resolve(directory, "native-output.json"), JSON.stringify({ samples, native }, null, 2) + "\n");
    assert.equal(native.exitCode, 0, native.stderr);
    const tokens = native.stdout.trim().split("\n").map((line: string) => { const [index, cost] = line.split("\t"); return { index: Number(index), cost: Number(cost) }; });
    assert.equal(tokens.length, samples.length);
    const inputs: any[] = [];
    const kept: boolean[] = [];
    for (const { index, cost } of tokens) {
      assert.ok(Number.isSafeInteger(cost) && cost >= 0);
      observations.push({ sample: samples[index], nativeTokens: cost, sampleArtifact: await artifact(samples[index]!.path) });
      for (const budget of cost === 0 ? [0, 1] : [cost - 1, cost]) {
        kept.push(budget >= cost);
        inputs.push({ kind: "completed", wallMillis: 0, maxOutputTokens: budget,
          content: [{ type: "input_audio", audio_url: urls[index] }] });
      }
    }
    const candidate = await command(candidateCommand(java, jar, "--format-fixture", runId), inputs.map(input => JSON.stringify(input)).join("\n") + "\n");
    await writeFile(resolve(directory, "comparison.json"), JSON.stringify({ schema: 1, runId, revision, samples: observations, native, inputs, candidate }, null, 2) + "\n");
    assert.equal(candidate.exitCode, 0, candidate.stderr);
    const rows = candidate.stdout.trim().split("\n").map((line: string) => JSON.parse(line)); assert.equal(rows.length, inputs.length);
    for (let index = 0; index < rows.length; index++) {
      assert.equal(rows[index].nonce, runId); assert.deepEqual(rows[index].input, inputs[index]);
      const expected = kept[index] ? inputs[index].content : [{ type: "input_text", text: "[omitted 1 audio items ...]" }];
      assert.deepEqual(rows[index].content.slice(1), expected, `native audio duration budget mismatch ${samples[Math.floor(index / 2)]!.name} at ${inputs[index].maxOutputTokens}`);
    }
  } catch (error) { family.findings.push(`Native audio oracle: ${error instanceof Error ? error.message : String(error)}`); }
  for (const name of ["oracle.rs", "Cargo.toml", "Cargo.lock", "native-audio.rs", "native-cache.rs", "native-string.rs", "build.json", "native-output.json", "comparison.json", "target/debug/native-audio-oracle"]) {
    try { family.artifacts.push(await artifact(resolve(directory, name))); } catch { /* Failure is already a qualification finding. */ }
  }
  for (const sample of samples) family.artifacts.push(await artifact(sample.path));
}

if (import.meta.main) {
  const path = process.argv[2]; if (!path) throw new Error("usage: bun tools/codemode-parity/audio-cases.ts ORACLE_DIRECTORY");
  await prepareAudioOracle(resolve(path));
  console.log("Built untouched native audio estimator with actual Symphonia 0.6 dependencies; no product qualification claimed.");
}
