import assert from "node:assert/strict";
import { sha256 } from "./qualification";
import { parseLosslessJson, assertObservableJsonKeyOrder } from "./current-witness";

const rawNumber=(value:any)=>(JSON as typeof JSON & {isRawJSON(value:unknown):boolean}).isRawJSON(value);
const integer=(value:any):string=>rawNumber(value)?value.rawJSON:String(value);
type TraceRow={direction:string;method:string;value:any};
export function readPeerTrace(text:string):TraceRow[]{return text.trim()?text.trimEnd().split("\n").map(line=>parseLosslessJson(line)):[];}
function wireBytes(value:any):Buffer{
  const data=value?.data??value;if(Buffer.isBuffer(data)||data instanceof Uint8Array)return Buffer.from(data);
  assert.ok(Array.isArray(data),"protobuf JSON bytes are missing");
  return Buffer.from(data.map(value=>{const byte=Number(integer(value));assert.ok(Number.isInteger(byte)&&byte>=0&&byte<=255,"protobuf JSON byte is invalid");return byte;}));
}
/** IDs are learned exclusively from typed peer metadata, never arbitrary tool input/output. */
export function peerIdentifiers(rows:TraceRow[]):Map<string,string>{
  const result=new Map<string,string>(),counts=new Map<string,number>();
  const put=(kind:string,value:any)=>{if(typeof value!=="string"||!value.length||result.has(value))return;const next=(counts.get(kind)??0)+1;counts.set(kind,next);result.set(value,kind+"-"+next);};
  const fields=(value:any)=>{if(!value||typeof value!=="object")return;for(const [key,kind]of Object.entries({session_id:"session",execution_id:"execution",invocation_id:"invocation",notification_id:"notification",wait_id:"wait"}))if(Object.hasOwn(value,key))put(kind,value[key]);};
  for(const row of rows){fields(row.value);for(const key of ["opened","started","cell_closed","notification","notification_cancelled","tool_call_cancelled"])fields(row.value?.[key]);}
  return result;
}
function metadataError(message:any,ids:Map<string,string>):any{
  if(typeof message!=="string")return message;
  for(const [wire,alias]of [...ids].sort((a,b)=>b[0].length-a[0].length))message=message.split(wire).join(alias);
  return message;
}
function timing(value:any):boolean{
  if(value===null||value===undefined)return false;
  const nanos=typeof value==="object"&&!rawNumber(value)?BigInt(integer(value.secs))*1_000_000_000n+BigInt(integer(value.nanos)):BigInt(integer(value));
  assert.ok(nanos>=0n&&nanos<=18446744073709551615n,"client timing exceeded native u64");return true;
}
/** Only known session/response metadata changes; runtime content and tool arguments are retained verbatim. */
export function normalizeClientObservation(raw:any,input:any,ids=new Map<string,string>()):any{
  assert.ok(Array.isArray(raw.steps)&&Array.isArray(raw.events)&&!raw.harness_error,"client fixture failed before producing observations");
  const cells=new Map<string,string>(),owners=new Map<string,string>(),calls=new Map<string,string>();
  const key=(session:string,id:any)=>session+"\0"+integer(id);
  for(const event of raw.events)if(event.type==="started"){
    assert.equal(typeof event.session,"string","client witness omitted actual session binding");
    const id=key(event.session,event.cell_id);assert.ok(!cells.has(id),"duplicate started identity within one session");cells.set(id,event.alias);owners.set(event.alias,event.session);
  }
  const cell=(value:any,session:string)=>cells.get(key(session,value))??value;
  const runtime=(value:any,session:string):any=>{
    const variant=["Yielded","Result","Terminated"].find(key=>Object.hasOwn(value??{},key));assert.ok(variant,"unknown runtime observation");
    const body=value[variant],result:any={...body,cell_id:cell(body.cell_id,session)};
    result.host_timing_present=timing(body.code_mode_host_duration??body.code_mode_host_duration_ns);
    // Buffered-result metadata is not in the public Rust RuntimeResponse type.
    // Its presence/value is separately mandatory in current.native-protocol.
    timing(body.result_buffered_for??body.result_buffered_for_ns);
    for(const name of ["code_mode_host_duration","code_mode_host_duration_ns","result_buffered_for","result_buffered_for_ns"])delete result[name];
    return {[variant]:result};
  };
  const outcome=(value:any,session:string):any=>{
    if(value===null||typeof value!=="object"||rawNumber(value))return value;
    if(Object.hasOwn(value,"LiveCell"))return{LiveCell:runtime(value.LiveCell,session)};
    if(Object.hasOwn(value,"MissingCell"))return{MissingCell:value.MissingCell!==null&&typeof value.MissingCell==="object"?runtime(value.MissingCell,session):cell(value.MissingCell,session)};
    if(["Yielded","Result","Terminated"].some(key=>Object.hasOwn(value,key)))return runtime(value,session);
    if(Object.hasOwn(value,"started"))return{started:cell(value.started,session)};
    if(Object.hasOwn(value,"dropped_initial"))return{dropped_initial:cell(value.dropped_initial,session)};
    return value;
  };
  const typed=(value:any,session:string,operation:string):any=>{
    if(Object.hasOwn(value,"error"))return{...value,error:metadataError(value.error,ids)};
    if(!Object.hasOwn(value,"ok"))return value;
    if(operation==="parallel_wait")return{ok:Object.fromEntries(["overlapping","queued_overlap","replacement"].map(key=>[key,typed(value.ok[key],session,"wait")]))};
    if(operation==="shutdown_queued")return{ok:{shutdown:typed(value.ok.shutdown,session,"shutdown"),queued:value.ok.queued.map((value:any)=>typed(value,session,"execute"))}};
    return{ok:outcome(value.ok,session)};
  };
  const requests=[...(input.manual_open?[]:[{op:"open",session:"main"}]),...input.steps];
  assert.equal(raw.steps.length,requests.length,"client omitted or added an operation result");
  const steps=raw.steps.map((step:any,index:number)=>{const request=requests[index];assert.equal(step.op,request.op);const session=request.session??"main";return{op:step.op,result:typed(step.result,session,step.op)};});
  const callbacks=raw.events.filter((event:any)=>event.type==="tool").map((event:any)=>{
    assert.equal(typeof event.session,"string");const value=event.value,alias=event.cell_alias??cells.get(key(event.session,value.cell_id))??"";
    const owner=event.session+"\0"+alias+"\0"+value.runtime_tool_call_id;if(!calls.has(owner))calls.set(owner,"call-"+calls.size);
    return{...event,value:{...value,cell_id:cell(value.cell_id,event.session),runtime_tool_call_id:calls.get(owner)}};
  });
  const notifications=raw.events.filter((event:any)=>event.type==="notification").map((event:any)=>({...event,cell_id:cell(event.cell_id,event.session)}));
  return{steps,callbacks,notifications};
}
export function normalizedPeerTrace(rows:TraceRow[],_input:any={adapter:"connection"}):any{
  const ids=peerIdentifiers(rows);
  const meta=(value:any):any=>{
    if(!value||typeof value!=="object")return value;const result={...value};
    for(const key of ["session_id","execution_id","invocation_id","notification_id","wait_id"])if(typeof result[key]==="string")result[key]=ids.get(result[key])??result[key];
    return result;
  };
  const payload=(value:any):any=>{
    if(value===undefined)return undefined;
    if(value.output_json_sha256)return value; // Size/hash-only trace of the deterministic 64 MiB boundary.
    const encoded=value.output_json;
    if(encoded===undefined)return value;
    const bytes=wireBytes(encoded),text=bytes.toString("utf8"),decoded=parseLosslessJson(text);
    return{...value,output_json:decoded,output_json_key_order:keyOrder(decoded),output_json_bytes:bytes.length,output_json_sha256:sha256(bytes)};
  };
  const completions=rows.filter(row=>row.direction==="received"&&row.method==="CompleteToolCall").map(row=>{const value=meta(row.value);if(value.succeeded)value.succeeded=payload(value.succeeded);return value;});
  completions.sort((a,b)=>JSON.stringify(a).localeCompare(JSON.stringify(b)));
  const acknowledgements=rows.filter(row=>row.direction==="received"&&row.method==="AcknowledgeNotification").map(row=>meta(row.value));
  const controls=rows.filter(row=>["Wait","CancelWait","Terminate","YieldObservation"].includes(row.method)&&["received","sent"].includes(row.direction)).map(row=>({direction:row.direction,method:row.method,value:meta(row.value)}));
  const executions=rows.filter(row=>row.direction==="received"&&row.method==="Execute").map(row=>meta(row.value));
  return{executions,completions,acknowledgements,controls};
}
/** Check actual peer requests against the bound fixture, as well as the other side. */
export function assertExecuteInputs(rows:TraceRow[],input:any):void{
  const attempts=(input.steps??[]).filter((step:any)=>step.op==="execute");let next=0;
  if(attempts.length&&/^(?:execute-|callback-|notification-|wait-)/.test(input.fault_mode??""))assert.ok(rows.some(row=>row.direction==="received"&&row.method==="Execute"),"scripted execution scenario omitted its actual Execute request");
  const unsigned=(value:any)=>value===undefined||value===null?undefined:integer(value);
  const encoded=(value:any)=>JSON.stringify(value);
  for(const row of rows.filter(row=>row.direction==="received"&&row.method==="Execute")){
    const request=row.value,index=attempts.findIndex((step:any,index:number)=>index>=next&&step.source===request.source);
    assert.ok(index>=next, "outgoing Execute source is not a bound execution attempt");const step=attempts[index];next=index+1;
    assert.equal(request.tool_call_id,step.tool_call_id??"witness-call","outgoing Execute changed caller tool_call_id");
    for(const field of ["yield_time_ms","max_output_tokens"]){const expected=unsigned(step[field])??(input.adapter&&input.adapter!=="connection"?"10000":undefined);assert.equal(unsigned(request[field]),expected,`outgoing Execute changed ${field} or its presence`);}
    const enabled=request.enabled_tools??[],expected=step.enabled_tools??[];assert.equal(enabled.length,expected.length,"outgoing Execute changed enabled tools");
    for(const [index,tool]of expected.entries()){
      const actual=enabled[index];assert.equal(actual.name??"",tool.name??"");assert.equal(actual.description??"",tool.description??"");assert.equal(integer(actual.kind??0),tool.kind==="freeform"?"2":"1");
      assert.equal(actual.tool_name?.name??"",tool.tool_name?.name??"");
      const namespace=(value:any)=>value?.namespace===null?undefined:value?.namespace;assert.equal(namespace(actual.tool_name),namespace(tool.tool_name),"outgoing Execute changed namespace identity/presence");
      for(const field of ["input_schema","output_schema"]){const key=field+"_json";assert.equal(Object.hasOwn(actual,key),Object.hasOwn(tool,field),`outgoing Execute changed ${field} presence`);if(Object.hasOwn(actual,key)){const bytes=wireBytes(actual[key]);assert.equal(encoded(parseLosslessJson(bytes.toString("utf8"))),encoded(tool[field]),`outgoing Execute changed ${field}`);}}
    }
  }
}
function keyOrder(value:any):any{
  if(!value||typeof value!=="object"||rawNumber(value))return null;
  if(Array.isArray(value))return value.map(keyOrder);
  return{keys:Object.keys(value),nested:Object.fromEntries(Object.keys(value).map(key=>[key,keyOrder(value[key])]))};
}
/** These witnesses observe an actual delivery/return order, not elapsed-time tolerances. */
export function assertClientCausality(raw:any,input:any,trace:TraceRow[]):void{
  if(input.fault_mode==="wait-missing-completed-notification"){
    const deliveries=raw.events.flatMap((event:any,index:number)=>event.type==="notification_delivery_finished"&&event.session==="main"&&event.cell_alias==="held"?[index]:[]);
    assert.equal(deliveries.length,1,"held notification delivery was not observed exactly once");
    const returned=(step:number)=>{const indices=raw.events.flatMap((event:any,index:number)=>event.type==="operation_returned"&&integer(event.step_index)===String(step)?[index]:[]);assert.equal(indices.length,1,"missing or duplicate actual operation return witness");return indices[0]!;};
    assert.ok(returned(1)<deliveries[0],"canceled Wait unexpectedly awaited notification delivery");
    assert.ok(deliveries[0]<returned(2)&&deliveries[0]<returned(3),"completed MissingCell control returned before notification delivery finished");
  }
  if(input.fault_mode==="wait-terminate-yielded-g2"){
    const calls=trace.filter(row=>row.direction==="received"&&["Wait","Terminate"].includes(row.method));
    assert.deepEqual(calls.map(row=>row.method),["Wait","Terminate","Wait","Terminate","Wait"],"yielded termination failed to return before the subsequent control");
    const terminals=raw.steps.filter((step:any)=>step.op==="terminate");
    assert.equal(terminals.length,2);
    for(const row of terminals)assert.match(row.result?.ok?.LiveCell?.Yielded?.cell_id??"",/^g2:/,"yielded control omitted its public generation identity");
  }
  if(input.fault_mode==="open-dispatch-return-cancel"){
    const opened=trace.filter(row=>row.direction==="sent"&&row.method==="OpenSession"&&row.value?.opened);
    assert.equal(opened.length,2,"opening cancellation must retain the independent bootstrap owner");
    const [bootstrap,target]=opened.map(row=>row.value.opened.session_id);
    assert.ok(bootstrap&&target&&bootstrap!==target,"opening fixture lost distinct session ownership");
    const executed=trace.findIndex(row=>row.direction==="received"&&row.method==="Execute");
    assert.ok(executed>=0,"bootstrap did not execute after the abandoned opening");
    assert.equal(trace[executed]!.value.session_id,bootstrap,"abandoned target executed instead of bootstrap");
    assert.ok(!trace.some(row=>row.direction==="received"&&row.method==="Execute"&&row.value.session_id===target),"abandoned opening issued an execution");
    const canceled=(session:string)=>trace.findIndex(row=>row.direction==="cancelled"&&row.method==="OpenSession"&&row.value.session_id===session);
    assert.ok(canceled(target)>=0&&canceled(target)<executed,"abandoned target lease remained live before bootstrap reuse");
    assert.ok(canceled(bootstrap)<0||canceled(bootstrap)>executed,"target cancellation closed the independent bootstrap lease");
  }
}
export function compareClientObservations(reference:any,candidate:any,input:any,referenceTrace:TraceRow[]=[],candidateTrace:TraceRow[]=[]):string[]{
  assertExecuteInputs(referenceTrace,input);assertExecuteInputs(candidateTrace,input);
  assertClientCausality(reference,input,referenceTrace);assertClientCausality(candidate,input,candidateTrace);
  const expected=normalizeClientObservation(reference,input,peerIdentifiers(referenceTrace)),actual=normalizeClientObservation(candidate,input,peerIdentifiers(candidateTrace)),soft:string[]=[];
  if(input.id==="rust-client/process-provider/accepted-initial-host-loss"){
    for(let index=0;index<expected.steps.length;index++)if(expected.steps[index].op==="initial"&&actual.steps[index].op==="initial"){
      const source=expected.steps[index].result.error,target=actual.steps[index].result.error;
      if(source!==target&&typeof source==="string"&&/^gRPC code-mode session lease failed:/.test(source)&&source.includes("hyper::Error")&&target==="code mode session closed"){
        soft.push("Accepted initial response lost with the host: both sides return a transport failure before the exact replacement warning/fresh-module/stale-cell outcomes. Rust reports Hyper lease internals; JVM reports code mode session closed. Raw per-side diagnostics and phase remain in the evidence; no universal transport-library error-text equality is claimed.");
        actual.steps[index].result={error:source};
      }
    }
  }
  assert.deepEqual(actual,expected,"actual Rust-client/production public adapter observations differ");
  assertObservableJsonKeyOrder(actual.callbacks,expected.callbacks);
  assert.deepEqual(normalizedPeerTrace(candidateTrace,input),normalizedPeerTrace(referenceTrace,input),"typed outgoing execution/completion/control protocol differs");
  return soft;
}

