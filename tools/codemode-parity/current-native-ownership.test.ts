import { expect, test } from "bun:test";
import { requiresPortedSessionContract } from "./current-inventory";

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
