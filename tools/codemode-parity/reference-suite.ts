import { mkdir, writeFile } from "node:fs/promises";
import { resolve } from "node:path";
import { createHash } from "node:crypto";
import { NativeReference } from "./native-reference";
import { runtimeCases, runRuntimeCase } from "./runtime-cases";

const [binary, destination] = process.argv.slice(2);
if (!binary || !destination) throw new Error("usage: bun tools/codemode-parity/reference-suite.ts NATIVE_HOST ARTIFACT_DIRECTORY");
await mkdir(destination, { recursive: true });
const executableSha256 = createHash("sha256").update(new Uint8Array(await Bun.file(binary).arrayBuffer())).digest("hex");
let failures = 0;
for (const test of runtimeCases) {
  const host = new NativeReference(resolve(binary));
  let failure: string | null = null;
  try { await runRuntimeCase(host, test); }
  catch (error) { failure = error instanceof Error ? error.stack ?? error.message : String(error); failures++; }
  finally { await host.stop(); }
  await writeFile(resolve(destination, `${test.id}.json`), JSON.stringify({
    schema: 1, scope: "native-runtime-observation-only", caseId: test.id,
    executableSha256, sourceRevisionLinkage: "unproven", failure, transcript: host.transcript, stderr: host.stderr,
  }, null, 2) + "\n");
  console.log(`${failure ? "FAIL" : "OBSERVED"} ${test.id}${failure ? `: ${failure.split("\n")[0]}` : ""}`);
}
console.log("Native runtime observations alone do not qualify the gateway, history, packaging, or interactive client.");
process.exitCode = failures ? 1 : 0;
