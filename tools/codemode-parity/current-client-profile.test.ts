import { afterEach, expect, test } from "bun:test";
import { mkdtemp, rm, writeFile } from "node:fs/promises";
import { tmpdir } from "node:os";
import { resolve } from "node:path";
import { llvmSourceEntries } from "./current-client-profile";
import { astRules, type CurrentInventory } from "./current-inventory";
import { sha256 } from "./qualification";

const temporary:string[]=[];
afterEach(async()=>{await Promise.all(temporary.splice(0).map(path=>rm(path,{recursive:true,force:true})));});
async function fixture(text:string){
  const root=await mkdtemp(resolve(tmpdir(),"splice-llvm-source-"));temporary.push(root);const file="fixture.rs",filename=resolve(root,file);
  await writeFile(filename,text);
  const git=(args:string[],stdin="")=>{const run=Bun.spawnSync(["git",...args],{cwd:root,stdin:Buffer.from(stdin),stdout:"pipe",stderr:"pipe"});if(run.exitCode!==0)throw Error(run.stderr.toString());return run.stdout.toString().trim();};
  git(["init","--quiet"]);const blob=git(["hash-object","-w","--stdin"],text),tree=git(["mktree"],`100644 blob ${blob}\t${file}\n`);
  const revision=git(["-c","user.name=splice-fixture","-c","user.email=fixture@example.com","commit-tree",tree,"-m","Immutable parser fixture"]);
  const scan=Bun.spawn([resolve(import.meta.dir,"../../node_modules/.bin/ast-grep"),"scan","--inline-rules",astRules(),"--json=compact",filename],{stdout:"pipe",stderr:"pipe"});
  const [out,err,exit]=await Promise.all([new Response(scan.stdout).text(),new Response(scan.stderr).text(),scan.exited]);expect(exit,err).toBe(0);
  const ast=JSON.parse(out),units=ast.filter((node:any)=>["function_item","macro_definition"].includes(node.ruleId)).map((node:any)=>({
    id:`${file}#${node.ruleId}@${node.range.byteOffset.start}`,file,kind:node.ruleId,start:node.range.byteOffset.start,end:node.range.byteOffset.end,sha256:sha256(node.text),ownership:"ported",label:"",line:node.range.start.line+1,
  }));
  const inventory={schema:2,sourceRevision:revision,sourceTree:tree,files:[{path:file,sha256:sha256(text),gitBlob:blob,discovery:"crate",units:units.length}],units}as CurrentInventory;
  const bytes=Buffer.from(text),position=(offset:number)=>{const before=bytes.subarray(0,offset),line=before.toString().split("\n").length,last=before.lastIndexOf(10);return[line,offset-last];};
  const counter=(source:string,name="generated",count=1)=>{const start=Buffer.byteLength(text.slice(0,text.indexOf(source))),end=start+Buffer.byteLength(source);
    return{name,count,filenames:[filename],regions:[[...position(start),...position(end),count,0,0,0]]};};
  const decode=(functions:any[],value=inventory)=>llvmSourceEntries({data:[{functions}]},value,root,root);
  return{inventory,units,file,filename,root,text,bytes,counter,decode};
}

const macro='macro_rules! constructors { ($variant:ident) => { pub fn $variant(value: u32) -> u32 { value } }; }';

test("an exact generated macro body is audited while the positively entered named getter keeps its witness",async()=>{
  const f=await fixture(`// λ UTF8 columns\n${macro}\npub fn getter() -> u32 { 7 }\n`);
  const result=await f.decode([f.counter("pub fn $variant(value: u32) -> u32"),f.counter("pub fn getter() -> u32 { 7 }","getter")]);
  const getter=f.units.find((unit:any)=>unit.kind==="function_item")!,definition=f.units.find((unit:any)=>unit.kind==="macro_definition")!;
  expect(result.linked).toEqual([{id:getter.id,sha256:getter.sha256}]);expect(result.mappings.map(value=>value.id)).toEqual([getter.id]);
  expect(result.generatedMacroBodies).toHaveLength(1);
  expect(result.generatedMacroBodies[0]!.macro).toEqual({id:definition.id,sha256:definition.sha256});
  expect(result.generatedMacroBodies[0]!.fileSha256).toBe(sha256(f.text));expect(result.generatedMacroBodies[0]!.behavioralCoverage).toBe(false);
  const saved=JSON.parse(JSON.stringify(result));expect(await f.decode([f.counter("pub fn $variant(value: u32) -> u32"),f.counter("pub fn getter() -> u32 { 7 }","getter")])).toEqual(saved);
});

test("a macro-only inventoried file retains its generated entry audit without a named function witness",async()=>{
  const f=await fixture(macro),result=await f.decode([f.counter("pub fn $variant(value: u32) -> u32")]);
  expect(result.linked).toEqual([]);expect(result.mappings).toEqual([]);expect(result.generatedMacroBodies).toHaveLength(1);
});

test("a generated entry inside a function-local macro cannot impersonate its enclosing named function",async()=>{
  const f=await fixture(`pub fn outer() { ${macro} }`),result=await f.decode([f.counter("pub fn $variant(value: u32) -> u32")]);
  expect(result.linked).toEqual([]);expect(result.mappings).toEqual([]);expect(result.generatedMacroBodies).toHaveLength(1);
});

test("unmatched actual named bodies still fail instead of borrowing an unrelated macro or nearest function",async()=>{
  const f=await fixture(`${macro}\npub fn getter() -> u32 { 7 }\n`),inventory=structuredClone(f.inventory);
  inventory.units=inventory.units.filter(unit=>unit.kind!=="function_item");
  await expect(f.decode([f.counter("pub fn getter() -> u32 { 7 }","getter")],inventory)).rejects.toThrow("no exact AST body mapping");
});

test("ambiguous containing macros and changed whole-file or macro bytes cannot authorize an audit",async()=>{
  const f=await fixture(macro),entry=f.counter("pub fn $variant(value: u32) -> u32"),duplicate=structuredClone(f.inventory);
  duplicate.units.push({...duplicate.units[0]!,id:"ambiguous-macro"});
  await expect(f.decode([entry],duplicate)).rejects.toThrow("ambiguous macro body mapping");
  const changed=structuredClone(f.inventory);changed.units[0]!.sha256="0".repeat(64);
  await expect(f.decode([entry],changed)).rejects.toThrow("macro definition bytes differ");
  const file=structuredClone(f.inventory);file.files[0]!.sha256="0".repeat(64);
  await expect(f.decode([entry],file)).rejects.toThrow("immutable source file hash differs");
});

test("zero counters stay uncredited and invalid source positions fail without a fallback",async()=>{
  const f=await fixture(macro),zero=await f.decode([f.counter("pub fn $variant(value: u32) -> u32","cold",0)]);
  expect(zero).toEqual({linked:[],mappings:[],generatedMacroBodies:[]});
  const entry=f.counter("pub fn $variant(value: u32) -> u32");entry.regions[0]![1]=1.5;
  await expect(f.decode([entry])).rejects.toThrow("not an exact source position");
  entry.regions[0]![1]=999999;
  await expect(f.decode([entry])).rejects.toThrow("column exceeds");
});
