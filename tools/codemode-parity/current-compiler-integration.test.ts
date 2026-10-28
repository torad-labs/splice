import { afterEach, expect, test } from "bun:test";
import { mkdtemp, mkdir, rm, writeFile } from "node:fs/promises";
import { tmpdir } from "node:os";
import { dirname, relative, resolve } from "node:path";
import { foreignMemberScope, type AstMatch } from "./current-foreign-scope";
import { compilerScope, loadCompilerScopeIndex, unstagedCompilerFiles } from "./current-compiler-scope";
import type { CompilerBindingIndex } from "./current-compiler-types";
import { astRules, generateCurrentInventory, inventoryDigest, inventoryFindings, type CurrentInventory } from "./current-inventory";
import { sha256 } from "./qualification";
import { currentReceiptFindings, sealCurrentReceipt, type CurrentReceipt } from "./current-readiness";

const temporary: string[] = [], rootFile = "codex-rs/code-mode/src/lib.rs", foreign = "codex-rs/protocol/src/lib.rs", extra = "codex-rs/protocol/src/extra.rs";
afterEach(async () => { await Promise.all(temporary.splice(0).map(path => rm(path,{ recursive:true,force:true }))); });
async function fixture() {
  const root = await mkdtemp(resolve(tmpdir(),"splice-compiler-integration-")); temporary.push(root);
  const sources = new Map([[rootFile,"use codex_protocol::{A,B}; pub fn exec(a: A, b: B) { make().run(); } fn make() -> B { B }"],
    [foreign,"pub struct A; impl A { pub fn run(&self) {} } pub struct B; impl B { pub fn run(&self) {} } pub fn unused() {}"],
    [extra,"pub fn leaf() {}"]]);
  const files = [...sources].filter(([file])=>file!==extra).map(([path,source])=>({path,source}));
  for (const file of files) { const path=resolve(root,file.path); await mkdir(dirname(path),{recursive:true}); await writeFile(path,file.source); }
  const scan=Bun.spawn([resolve(import.meta.dir,"../../node_modules/.bin/ast-grep"),"scan","--inline-rules",astRules(),"--json=compact",root],{stdout:"pipe",stderr:"pipe"});
  const [output,error,exit]=await Promise.all([new Response(scan.stdout).text(),new Response(scan.stderr).text(),scan.exited]); expect(exit,error).toBe(0);
  const raw: AstMatch[]=JSON.parse(output), declarations=raw.filter(node=>["function_item","struct_item","impl_item"].includes(node.ruleId));
  const census=declarations.map(node=>({id:`${relative(root,node.file)}#${node.ruleId}@${node.range.byteOffset.start}`,file:relative(root,node.file),kind:node.ruleId,...node.range.byteOffset,sha256:sha256(node.text)}));
  const owner=declarations.find(node=>node.ruleId==="function_item"&&node.text.startsWith("pub fn exec"))!;
  const runs=declarations.filter(node=>node.ruleId==="function_item"&&node.text.startsWith("pub fn run"));
  const node=(value:AstMatch)=>census.find(node=>node.file===relative(root,value.file)&&node.start===value.range.byteOffset.start&&node.kind===value.ruleId)!;
  const range=(file:string,text:string)=>{const source=sources.get(file)!,at=source.indexOf(text);return {file,start:Buffer.byteLength(source.slice(0,at)),end:Buffer.byteLength(source.slice(0,at+text.length))};};
  const source={sourceRevision:"a".repeat(40),sourceTree:"b".repeat(40),sourceCargoLockSha256:"c".repeat(64)};
  const index:CompilerBindingIndex={schema:1,source:{revision:source.sourceRevision,tree:source.sourceTree,originalCargoLockSha256:source.sourceCargoLockSha256,buildCargoLockSha256:"e".repeat(64)},proofSha256:"d".repeat(64),behavioralCoverage:false,
    definitions:["owner","target","unused"].map((key,index)=>({key,raw:"opaque",crate:"fixture",crateNumber:0,crateHash:"f".repeat(64),index,path:"not-an-identity"})),
    declarations:[{owner:"owner",kind:"Fn",range:{file:rootFile,...owner.range.byteOffset}},{owner:"target",kind:"AssocFn",range:{file:foreign,...runs[1]!.range.byteOffset}},
      {owner:"unused",kind:"Fn",range:range(foreign,"pub fn unused() {} ".trim())}],
    references:[{owner:"owner",target:"target",namespace:"value",kind:"callee",resolution:"FnDef",range:range(rootFile,"make().run")}]};
  const immutable=new Map([...sources].map(([file,source])=>[file,{source,sha256:sha256(source)}]));
  const manifests=[{path:"codex-rs/code-mode/Cargo.toml",source:'[package]\nname="codex-code-mode"'},{path:"codex-rs/protocol/Cargo.toml",source:'[package]\nname="codex-protocol"'}];
  const run=()=>foreignMemberScope(files,raw,root,["codex-rs/code-mode"],manifests,undefined,undefined,compilerScope(index,source,immutable,census));
  return {root,source,index,immutable,census,owner:node(owner),a:node(runs[0]!),b:node(runs[1]!),range,run};
}

test("an exact compiler receiver target replaces conservative same-member AST alternatives",async()=>{
  const f=await fixture(),result=f.run();
  expect(result.selections.get(f.a.id)?.state).toBe("foreign-context"); expect(result.selections.get(f.b.id)?.state).toBe("required");
  expect(result.compiler.some(trace=>trace.resolution.status==="bound"&&trace.resolution.target.id===f.b.id)).toBe(true);
  expect(result.unresolved).toEqual([]);
});
test("an absent subset fact retains conservative project targets and explicit fallback evidence",async()=>{
  const f=await fixture();f.index.references=[];const result=f.run();
  expect(result.selections.get(f.a.id)?.state).toBe("required");expect(result.selections.get(f.b.id)?.state).toBe("required");
  expect(result.compiler).toEqual([]);expect(result.compilerFallbacks.some(value=>value.astTargets.includes(f.a.id))).toBe(true);
});
test("a nearby compiler span cannot suppress an unbound receiver guess",async()=>{
  const f=await fixture();f.index.references[0]!.range!.end--;const result=f.run();
  expect(result.selections.get(f.a.id)?.state).toBe("required");expect(result.selections.get(f.b.id)?.state).toBe("required");
  expect(result.compilerFallbacks.some(value=>value.expression==="make().run")).toBe(true);
});
test("unreached compiler owners do not seed roots or staging obligations",async()=>{
  const f=await fixture();f.index.references.push({...f.index.references[0]!,owner:"unused",range:f.range(foreign,"unused")});
  const result=f.run(),unused=`${foreign}#function_item@${f.index.declarations[2]!.range!.start}`;
  expect(result.selections.get(unused)?.state).toBe("foreign-context");expect(result.compiler.some(value=>value.query.owner===unused)).toBe(false);
  expect(unstagedCompilerFiles(result.compiler,f.immutable)).toEqual([]);
});
test("an exact unstaged target names only its immutable file and remains unbound until extraction",async()=>{
  const f=await fixture();f.index.declarations[1]!.kind="Fn";f.index.declarations[1]!.range=f.range(extra,"pub fn leaf() {}");
  const result=f.run(),targets=unstagedCompilerFiles(result.compiler,f.immutable);
  expect(targets).toEqual([{file:extra,sha256:f.immutable.get(extra)!.sha256,requiredBy:[rootFile]}]);
  expect(result.compiler[0]!.resolution.status).toBe("unstaged");
  const changed=new Map(f.immutable);changed.set(extra,{source:"pub fn wrong() {}",sha256:sha256("pub fn wrong() {}")});
  expect(()=>unstagedCompilerFiles(result.compiler,changed)).toThrow("immutable Git source");
});
test("a constructor kind cannot bind a method body or hide known project source debt",async()=>{
  const f=await fixture();f.index.declarations[1]!.kind="Ctor(Struct, Fn)";const result=f.run();
  expect(result.unresolved.some(value=>value.expression.startsWith("rustc value/callee:"))).toBe(true);
  expect(result.selections.get(f.a.id)?.state).toBe("required");
});
test("an unknown compiler namespace retains its known project source as unresolved debt",async()=>{
  const f=await fixture();f.index.references[0]!.namespace="unknown";const result=f.run();
  expect(result.unresolved.some(value=>value.expression.startsWith("rustc unknown/callee:"))).toBe(true);
  expect(result.selections.get(f.a.id)?.state).toBe("required");expect(result.selections.get(f.b.id)?.state).toBe("required");
});
test("a recorded proof requires an explicit locator and changed or missing manifests fail closed",async()=>{
  const f=await fixture();
  await expect(generateCurrentInventory("/absent",f.source.sourceRevision,undefined,undefined,{expectedCompilerProofSha256:"d".repeat(64)})).rejects.toThrow("explicit --compiler-bindings");
  await expect(loadCompilerScopeIndex(resolve(f.root,"missing.json"),f.source,f.immutable)).rejects.toThrow();
  const path=resolve(f.root,"proof.json");await writeFile(path,"{}");
  await expect(loadCompilerScopeIndex(path,f.source,f.immutable,"0".repeat(64))).rejects.toThrow("proof changed");
});
test("compiler proof identities and fallback decisions participate in inventory reproduction",async()=>{
  const f=await fixture(),base={schema:2,sourceRevision:f.source.sourceRevision,sourceTree:f.source.sourceTree,files:[],units:[],grpcMethods:[],globalHelpers:[],
    compilerBindings:{algorithm:"rustc-exact-source-scope-v1",proofSha256:"d".repeat(64),semanticDigest:"e".repeat(64)},compilerScopeResolutions:[],compilerScopeFallbacks:[],
    astGrepVersion:"fixed",generator:"fixed",sourceCargoLockSha256:f.source.sourceCargoLockSha256,discoveredCrates:[]} as unknown as CurrentInventory;
  base.denominatorSha256=inventoryDigest(base);const changed=structuredClone(base);changed.compilerBindings!.proofSha256="0".repeat(64);
  expect(inventoryDigest(changed)).not.toBe(base.denominatorSha256);expect(inventoryFindings(base,changed)).toContain("explicit compiler binding proof or semantic source decisions changed");
  const fallback=structuredClone(base);fallback.compilerScopeFallbacks=f.run().compilerFallbacks;
  expect(inventoryDigest(fallback)).not.toBe(base.denominatorSha256);
});
test("a sealed current receipt cannot substitute or omit its inventory compiler proof",()=>{
  const digest="a".repeat(64),now=Date.now(),compilerBindings={algorithm:"rustc-exact-source-scope-v1" as const,proofSha256:"b".repeat(64),semanticDigest:"c".repeat(64)};
  const expected={sourceRevision:"d".repeat(40),inventorySha256:digest,dispositionsSha256:digest,referenceLockSha256:digest,harnessSha256:digest,
    referenceRelease:"0.165.0",referenceCliSha256:digest,nativeHostSha256:digest,provenanceSha256:digest,requireInstalled:false,now,compilerBindings};
  const receipt={schema:2,claim:"current-complete-CodeMode",runId:"e".repeat(32),startedAt:new Date(now-1000).toISOString(),finishedAt:new Date(now).toISOString(),
    sourceRevision:expected.sourceRevision,inventorySha256:digest,dispositionsSha256:digest,referenceLockSha256:digest,harnessSha256:digest,
    reference:{release:expected.referenceRelease,cli:{sha256:digest},nativeHost:{sha256:digest}},candidateNativeHost:{sha256:digest},nativeProvenance:{sha256:digest},
    candidateConformant:true,qualified:false,compilerBindings,seal:""} as unknown as CurrentReceipt;
  const key=Buffer.alloc(32);receipt.seal=sealCurrentReceipt(receipt,key);expect(currentReceiptFindings(receipt,expected,key)).toEqual([]);
  for(const replacement of[undefined,{...compilerBindings,proofSha256:"0".repeat(64)},{...compilerBindings,semanticDigest:"0".repeat(64)}]){
    const changed={...receipt,compilerBindings:replacement};changed.seal=sealCurrentReceipt(changed,key);
    expect(currentReceiptFindings(changed,expected,key)).toContain("current explicit compiler binding proof or semantic source decisions changed");
  }
});
