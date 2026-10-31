import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";
import { currentCoreFixtures } from "./current-core";
import type { ExecutedVerifier } from "./current-coverage";
import { assertTokenizerCorpus } from "./current-tokenizer-fixtures";
import { assertJsonCorpus } from "./current-json-fixtures";
import { coreCaseIdentity, parseLosslessJson, assertObservableJsonKeyOrder } from "./current-witness";
import { sha256, type Artifact } from "./qualification";
import { assertMcpSearchRank } from "./current-cli/mcp-search-cases";

/** Validate every mandatory raw row, including cases with no project function counter. */
export async function coreCorpusFindings(execution: ExecutedVerifier | undefined, runId: string): Promise<string[]> {
  try {
    assert.ok(execution && execution.id === "current.tools-and-description", "mandatory current core verifier is absent");
    const witness = execution.cases?.find(item => item.producer === undefined || item.producer === "core-source");
    assert.ok(witness, "mandatory current core raw corpus is absent");
    const read = async (proof:Artifact) => { const bytes=await readFile(proof.path);assert.equal(bytes.length,proof.bytes);assert.equal(sha256(bytes),proof.sha256);return bytes.toString(); };
    const [inputText,referenceText,candidateText]=await Promise.all([read(witness.input),read(witness.reference),read(witness.candidate)]);
    const inputs=inputText.trimEnd().split("\n").map(parseLosslessJson), referenceCommand=JSON.parse(referenceText), candidateCommand=JSON.parse(candidateText);
    for(const command of [referenceCommand,candidateCommand]){assert.equal(command.exitCode,0);assert.ok(Number.isSafeInteger(command.pid)&&command.pid>0);}
    const reference=referenceCommand.stdout.trimEnd().split("\n").map(parseLosslessJson),candidate=candidateCommand.stdout.trimEnd().split("\n").map(parseLosslessJson);
    assert.equal(reference.length,inputs.length);assert.equal(candidate.length,inputs.length);
    const required=new Set(currentCoreFixtures().map(value=>value.id)), seen=new Set<string>();
    for(let index=0;index<inputs.length;index++){
      const input=inputs[index],identity=coreCaseIdentity(input);
      assert.equal(input.id,identity.caseId);assert.ok(!seen.has(input.id),"duplicated mandatory core input");seen.add(input.id);
      if(input.operation!=="tokenize"&&!input.corpus?.startsWith("json-"))assert.ok(required.delete(input.id),"unrecognized current core input");
      assert.equal(reference[index].id,input.id);assert.equal(candidate[index].id,input.id);assert.equal(candidate[index].nonce,runId);
      assert.deepEqual(candidate[index].result,reference[index].result,"mandatory source/production result differs");
      assertMcpSearchRank(input,reference[index].result);
      if(input.operation==="tool_output")assertObservableJsonKeyOrder(candidate[index].result.value,reference[index].result.value);
      if(input.operation==="json_value"&&Object.hasOwn(reference[index].result,"value"))assertObservableJsonKeyOrder(candidate[index].result.value,reference[index].result.value);
    }
    assert.equal(required.size,0,"mandatory current core fixtures were omitted");
    assertTokenizerCorpus(inputs.filter(input=>input.operation==="tokenize"));
    assertJsonCorpus(inputs.filter(input=>input.corpus?.startsWith("json-")));
    return [];
  }catch(error){return ["current core corpus is incomplete or changed: "+(error instanceof Error?error.message:String(error))];}
}

