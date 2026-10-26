import {expect,test} from "bun:test";
import {contextReason}from"./current-coverage";
import {assertClientCausality,compareClientObservations,normalizeClientObservation}from"./current-client-observable";
import {assertTokenizerCorpus}from"./current-tokenizer-fixtures";
import {jsonCorpusFixtures,assertJsonCorpus}from"./current-json-fixtures";
import {generatedProtoSymbols}from"./current-proto-scope";
import {parseLosslessJson,assertObservableJsonKeyOrder}from"./current-witness";

test("active gRPC client functions cannot be waived as transport context",()=>{
 expect(contextReason({id:"client",file:"codex-rs/code-mode/src/grpc_session/operations.rs",kind:"function_item",label:"wait",line:1,start:0,end:2,sha256:"a".repeat(64),ownership:"ported"})).toBeUndefined();
});
test("a constant final outcome cannot hide the wrong outgoing tool failure",()=>{
 const input={id:"rust-client/fault/callback-invalid-json",manual_open:true,steps:[{op:"execute"}]};
 const result={steps:[{op:"execute",result:{error:"session failed"}}],events:[]};
 const trace=(message:string)=>[{direction:"received",method:"CompleteToolCall",value:{invocation_id:"wire-id",failed:{message}}}];
 expect(()=>compareClientObservations(result,result,input,trace("expected value at line 1 column 1"),trace("generic invalid JSON"))).toThrow("completion/control protocol");
});
test("Wait cancellation acknowledgement must precede a replacement Wait",()=>{
 const input={id:"rust-client/fault/wait-slot-cancel-delay",manual_open:true,steps:[]},result={steps:[],events:[]};
 const wait={direction:"received",method:"Wait",value:{wait_id:"one"}},cancel={direction:"received",method:"CancelWait",value:{wait_id:"one"}},ack={direction:"sent",method:"CancelWait",value:{}},replacement={direction:"received",method:"Wait",value:{wait_id:"two"}};
 expect(()=>compareClientObservations(result,result,input,[wait,cancel,ack,replacement],[wait,cancel,replacement,ack])).toThrow("completion/control protocol");
});
test("equal cell numbers in separate sessions retain their own aliases and arbitrary input JSON",()=>{
 const input={manual_open:true,steps:[{op:"execute",as:"a",session:"main"},{op:"execute",as:"b",session:"other"}]};
 const raw={steps:[{op:"execute",result:{ok:{started:"0"}}},{op:"execute",result:{ok:{started:"0"}}}],events:[
  {type:"started",session:"main",alias:"a",cell_id:"0"},{type:"started",session:"other",alias:"b",cell_id:"0"},
  ...["main","other"].map(session=>({type:"tool",session,value:{cell_id:"0",runtime_tool_call_id:"opaque",input:{cell_id:"0",runtime_tool_call_id:"data",code_mode_host_duration_ns:"data"}}}))
 ]};
 const normalized=normalizeClientObservation(raw,input);
 expect(normalized.callbacks.map((event:any)=>event.value.cell_id)).toEqual(["a","b"]);
 expect(normalized.callbacks[0].value.input).toEqual((raw.events[2] as any).value.input);
});
test("lossless numeric fixtures and MCP key order cannot collapse to a false equality",()=>{
 expect(parseLosslessJson('{"n":18446744073709551615}')).not.toEqual(parseLosslessJson('{"n":18446744073709552000}'));
 expect(()=>assertObservableJsonKeyOrder({a:1,b:2},{b:2,a:1})).toThrow("key order");
});
test("the locked tokenizer denominator rejects an omitted corpus",()=>{
 expect(()=>assertTokenizerCorpus([])).toThrow("omitted, changed or shrunk");
});
test("a JSON result sample cannot replace the mandatory malformed-byte and numeric corpus",()=>{
 const rows=jsonCorpusFixtures();expect(()=>assertJsonCorpus(rows)).not.toThrow();
 expect(()=>assertJsonCorpus(rows.slice(1))).toThrow("omitted");
 const changed=rows.map(row=>row.bytes_hex==="22ff22"?{...row,bytes_hex:"226122"}:row);
 expect(()=>assertJsonCorpus(changed)).toThrow();
});
test("generated protobuf resolution requires exact include/build/schema paths and does not invent helpers",()=>{
 const files=[
  {path:"codex-rs/protocol/src/grpc/mod.rs",source:'tonic::include_proto!("fixture.v1");'},
  {path:"codex-rs/protocol/build.rs",source:'glob::glob("src/grpc/*.proto"); tonic_prost_build::configure().compile_with_config();'},
  {path:"codex-rs/protocol/src/grpc/wire.proto",source:"syntax=\"proto3\";package fixture.v1;message Request { string id=1; } enum ToolKind { TOOL_KIND_UNSPECIFIED=0; TOOL_KIND_FUNCTION=1; }"},
 ];
 const names=generatedProtoSymbols(files,()=> "fixture::grpc");
 expect(names.has("fixture::grpc::Request")).toBe(true);
 expect(names.has("fixture::grpc::ToolKind::Function")).toBe(true);
 expect(names.has("fixture::grpc::Request::invented_helper")).toBe(false);
 expect(generatedProtoSymbols(files.filter(file=>!file.path.endsWith("build.rs")),()=> "fixture::grpc").size).toBe(0);
});
test("identical control values cannot hide completion before held notification delivery",()=>{
 const input={fault_mode:"wait-missing-completed-notification"};
 const returned=(step_index:number)=>({type:"operation_returned",step_index});
 const delivery={type:"notification_delivery_finished",session:"main",cell_alias:"held"};
 expect(()=>assertClientCausality({events:[returned(1),delivery,returned(2),returned(3)]},input,[])).not.toThrow();
 expect(()=>assertClientCausality({events:[returned(1),returned(2),delivery,returned(3)]},input,[])).toThrow("before notification delivery");
 expect(()=>assertClientCausality({events:[returned(1),returned(2),returned(3)]},input,[])).toThrow("delivery was not observed");
});
test("a healthy bootstrap result cannot hide a leaked abandoned opening lease",()=>{
 const input={fault_mode:"open-dispatch-return-cancel"};
 const opened=(session_id:string)=>({direction:"sent",method:"OpenSession",value:{opened:{session_id}}});
 const cancel={direction:"cancelled",method:"OpenSession",value:{session_id:"target"}},execute={direction:"received",method:"Execute",value:{session_id:"bootstrap"}};
 expect(()=>assertClientCausality({},input,[opened("bootstrap"),opened("target"),cancel,execute])).not.toThrow();
 expect(()=>assertClientCausality({},input,[opened("bootstrap"),opened("target"),execute])).toThrow("lease remained live");
 expect(()=>assertClientCausality({},input,[opened("bootstrap"),opened("target"),execute,cancel])).toThrow("lease remained live");
});
test("constant peer errors cannot hide changed Execute source, caller id, options or tool descriptors",()=>{
 const tool={name:"plain",tool_name:{name:"plain"},description:"plain",kind:"function"};
 const input={id:"rust-client/fault/execute-status-before-headers",fault_mode:"execute-status-before-headers",manual_open:true,steps:[{op:"execute",source:"text('bound');",tool_call_id:"caller",yield_time_ms:"0",max_output_tokens:"8",enabled_tools:[tool]}]};
 const result={steps:[{op:"execute",result:{error:"scripted execution failure"}}],events:[]};
 const request={session_id:"source-session",execution_id:"source-execution",tool_call_id:"caller",source:"text('bound');",yield_time_ms:"0",max_output_tokens:"8",enabled_tools:[{...tool,kind:1}]};
 const trace=(value:any)=>[{direction:"received",method:"Execute",value}];
 expect(()=>compareClientObservations(result,result,input,trace(request),trace({...request,session_id:"target-session",execution_id:"target-execution"}))).not.toThrow();
 for(const changed of [{source:"text('wrong');"},{tool_call_id:"WRONG-CALL"},{yield_time_ms:"9999999"},{max_output_tokens:"0"},{enabled_tools:[]},{enabled_tools:[{...tool,kind:2}]}])expect(()=>compareClientObservations(result,result,input,trace(request),trace({...request,...changed}))).toThrow();
 expect(()=>compareClientObservations(result,result,input,[],[])).toThrow("omitted its actual Execute");
});

