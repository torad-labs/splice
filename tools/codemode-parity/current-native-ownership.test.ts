import { expect, test } from "bun:test";
import { protocolOwnershipPolicy, requiresPortedProtocolContract, requiresPortedSessionContract } from "./current-inventory";

const file="codex-rs/code-mode-protocol/src/session.rs";
const trait=(text:string,start=0,end=1000)=>({ruleId:"trait_item",text,range:{byteOffset:{start,end},start:{line:0}}});

test("every public bodyless session and delegate contract stays ported independently of method names",()=>{
  for(const text of["pub trait Session { fn arbitrary(); }","pub trait Delegate { fn different(); }","pub unsafe trait Provider { fn create(); }"])
    expect(requiresPortedSessionContract({file,kind:"function_signature_item",start:20,end:40},[trait(text)])).toBe(true);
});

test("native-only methods, private traits and unrelated protocol modules cannot inherit the shared contract rule",()=>{
  for(const value of[
    {unit:{file,kind:"function_item",start:20,end:40},owners:[trait("pub trait Session { fn default_body() {} }")]},
    {unit:{file,kind:"function_signature_item",start:20,end:40},owners:[trait("trait Private { fn internal(); }")]},
    {unit:{file,kind:"function_signature_item",start:20,end:40},owners:[trait("pub(crate) trait Private { fn internal(); }")]},
    {unit:{file,kind:"function_signature_item",start:20,end:40},owners:[trait("pub trait Elsewhere {}",100,200)]},
    {unit:{file:"codex-rs/code-mode-runtime/src/session.rs",kind:"function_signature_item",start:20,end:40},owners:[trait("pub trait NativeOnly {}")]},
    {unit:{file,kind:"function_signature_item",start:20,end:40},owners:[]},
  ])expect(requiresPortedSessionContract(value.unit,value.owners)).toBe(false);
});

test("every declaration in the shared host wire facade retains frontend serialization and validation obligations",()=>{
  for(const module of["message","payload","types"])for(const kind of["source-file","struct_item","enum_item","field_declaration","impl_item","function_item","macro_definition","type_item"])
    expect(requiresPortedProtocolContract({file:`codex-rs/code-mode-protocol/src/host/${module}.rs`,kind,start:0,end:10},[])).toBe(true);
});

test("native codec and runtime implementations cannot inherit the frontend facade selector",()=>{
  for(const file of["codex-rs/code-mode-protocol/src/host/codec.rs","codex-rs/code-mode-host/src/peer.rs","codex-rs/code-mode-runtime/src/service.rs","codex-rs/code-mode-protocol/src/host/payload.rs.generated"])
    expect(requiresPortedProtocolContract({file,kind:"impl_item",start:0,end:10},[])).toBe(false);
});

test("the ownership selector records exact facade source and Git identities independently of input order",()=>{
  const files=["message","payload","types"].map(name=>({path:`codex-rs/code-mode-protocol/src/host/${name}.rs`,source:`pub struct ${name} {}`,blob:"a".repeat(40)}));
  files.push({path:file,source:"pub trait Session {}",blob:"b".repeat(40)});
  const original=protocolOwnershipPolicy(files);expect(original.boundaryFiles).toHaveLength(4);
  expect(protocolOwnershipPolicy([...files].reverse())).toEqual(original);
  expect(protocolOwnershipPolicy(files.map((value,index)=>index?value:{...value,source:"changed shared wire source"})).selectorsSha256).not.toBe(original.selectorsSha256);
  expect(protocolOwnershipPolicy(files.map((value,index)=>index?value:{...value,blob:"c".repeat(40)})).selectorsSha256).not.toBe(original.selectorsSha256);
});
