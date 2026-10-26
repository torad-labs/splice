#!/usr/bin/env bun
// A bounded scripted server. It replaces only the peer, never the official Rust client's behavior.
import * as grpc from "@grpc/grpc-js";
import * as loader from "@grpc/proto-loader";
import { connect } from "node:net";
import { appendFileSync } from "node:fs";
import { randomUUID, createHash } from "node:crypto";

const schema = new URL("../../../integrations/codemode/src/main/resources/splice/codemode/native/grpc/codex.code_mode.v1.proto", import.meta.url).pathname;
const definition: any = grpc.loadPackageDefinition(loader.loadSync(schema, { keepCase:true,longs:String,enums:Number,defaults:false,oneofs:true }));
const mode = process.env.SPLICE_GRPC_FAULT ?? "normal";
const trace = (direction:string,method:string,value:any) => {
  if(method === "CompleteToolCall" && value.succeeded?.output_json){
    const bytes=Buffer.from(value.succeeded.output_json);
    value={...value,succeeded:{output_json_bytes:bytes.length,output_json_sha256:createHash("sha256").update(bytes).digest("hex")}};
  }
  const line=JSON.stringify({direction,method,value})+"\n";if(process.env.SPLICE_GRPC_TRACE_PATH)appendFileSync(process.env.SPLICE_GRPC_TRACE_PATH,line);process.stderr.write(line);};
const server = new grpc.Server({"grpc.max_receive_message_length":64*1024*1024,"grpc.max_send_message_length":64*1024*1024});
let opens = 0, lease:any, subscription:any, executes = 0, currentSession = "", last:any, waitCount=0, lateDuplicate:any;
const leases = new Map<string,any>();
const wireError = (call:any,code:number,message:string) => { trace("error","rpc",{code,message});call.emit("error",{code,details:message}); };
const reply = (callback:any,value:any) => callback(null,value);
const write = (call:any,method:string,value:any) => { trace("sent",method,value);call.write(value); };
const content = [{text:{text:"scripted peer outcome"}}];
const outcome = (cell:string,kind="completed") => ({cell_id:cell,content_items:content,code_mode_host_duration_ns:"0",[kind]:{}});

server.addService(definition.codex.code_mode.v1.CodeModeHost.service,{
  openSession(call:any) {
    trace("received","OpenSession",call.request);opens++;
    if(mode === "open-timeout-after-headers"){call.on("cancelled",()=>trace("cancelled","OpenSession",{}));call.sendMetadata(new grpc.Metadata());return;}
    if(mode === "open-timeout-before-headers"){call.on("cancelled",()=>trace("cancelled","OpenSession",{}));return;}
    if(mode === "wait-reopen-fails-once" && opens === 2){wireError(call,grpc.status.INVALID_ARGUMENT,"scripted replacement admission failure");return;}
    if(mode === "wait-shutdown-blocked-reopen" && opens >= 2){call.sendMetadata(new grpc.Metadata());return;}
    const failures = mode === "admission-unavailable-twice" ? 2 : mode === "admission-resource-four" ? 4 : mode === "admission-unavailable-five" ? 9 : 0;
    if(opens<=failures) { wireError(call,mode === "admission-resource-four" ? grpc.status.RESOURCE_EXHAUSTED : grpc.status.UNAVAILABLE,"scripted admission failure");return; }
    if(mode === "admission-invalid-argument") { wireError(call,grpc.status.INVALID_ARGUMENT,"scripted invalid admission");return; }
    lease=call;const metadata=new grpc.Metadata();
    if(mode === "open-route-too-long") metadata.set("x-code-mode-route","r".repeat(257));
    if(mode === "wait-route-unavailable") metadata.set("x-code-mode-route","route-token");
    call.sendMetadata(metadata);
    if(mode === "lease-end-before-open") {call.end();return;}
    if(mode === "open-no-event") {write(call,"OpenSession",{});return;}
    currentSession=mode === "open-empty-id" ? "" : mode === "open-long-id" ? "s".repeat(257) : `session-${opens}`;
    leases.set(currentSession,call);write(call,"OpenSession",{opened:{session_id:currentSession}});
    call.on("cancelled",()=>trace("cancelled","OpenSession",{session_id:currentSession}));
  },
  closeSession(call:any,callback:any) {
    trace("received","CloseSession",call.request);reply(callback,{});
    setTimeout(()=>{leases.get(call.request.session_id)?.end();subscription?.end();},1);
  },
  subscribeToToolCalls(call:any) {
    trace("received","SubscribeToToolCalls",call.request);subscription=call;call.sendMetadata(new grpc.Metadata());
    if(mode === "subscription-end") {setTimeout(()=>call.end(),5);return;}
    if(mode === "subscription-unavailable") {setTimeout(()=>wireError(call,grpc.status.UNAVAILABLE,"scripted subscription failure"),5);return;}
    if(mode === "open-duplicate") setTimeout(()=>write(lease,"OpenSession",{opened:{session_id:currentSession}}),5);
    if(mode === "lease-end") setTimeout(()=>lease.end(),5);
    call.on("cancelled",()=>trace("cancelled","SubscribeToToolCalls",call.request));
  },
  execute(call:any) {
    const request=call.request;trace("received","Execute",request);executes++;
    if(mode === "execute-status-before-headers"){wireError(call,grpc.status.INVALID_ARGUMENT,"scripted execution failure");return;}
    if(mode === "execute-status-after-headers"){call.sendMetadata(new grpc.Metadata());setTimeout(()=>wireError(call,grpc.status.INVALID_ARGUMENT,"scripted execution failure"),5);return;}
    if(mode === "execute-long-ascii"){wireError(call,grpc.status.INVALID_ARGUMENT,"a".repeat(900));return;}
    if(mode === "execute-long-multibyte"){wireError(call,grpc.status.INVALID_ARGUMENT,"á🙂".repeat(200));return;}
    const cell=`cell-${executes}`,execution=request.execution_id;last={request,cell,call};
    const started={started:{execution_id:mode === "execute-wrong-owner" ? randomUUID() : execution,cell_id:mode === "execute-empty-cell" ? "" : cell}};
    if(executes>1&&!mode.startsWith("wait-")){
      write(call,"Execute",{started:{execution_id:execution,cell_id:cell}});
      write(call,"Execute",{outcome:outcome(cell)});call.end();
      write(lease,"OpenSession",{cell_closed:{execution_id:execution,cell_id:cell,final_tool_call_sequence:"0"}});return;
    }
    if(mode === "execute-missing-event") {write(call,"Execute",{});call.end();return;}
    if(mode === "execute-outcome-before-start") {write(call,"Execute",{outcome:outcome(cell)});call.end();return;}
    if(mode === "closed-before-start") write(lease,"OpenSession",{cell_closed:{execution_id:execution,cell_id:cell,final_tool_call_sequence:"0"}});
    write(call,"Execute",started);
    if(mode === "execute-repeated-start") write(call,"Execute",started);
    if(mode.startsWith("callback-")) {
      const invocation: string=randomUUID();
      const tool={session_id:request.session_id,execution_id:execution,cell_id:cell,invocation_id:invocation,runtime_tool_call_id:"runtime-call-1",
        tool_name:{name:"plain"},tool_kind:1,input_json:Buffer.from("{}"),sequence:"1"};
      if(mode === "callback-wrong-session") tool.session_id="wrong-session";
      if(mode === "callback-invalid-uuid") tool.invocation_id="not-a-uuid";
      if(mode === "callback-unenabled") tool.tool_name={name:"disabled"};
      if(mode === "callback-missing-name") delete (tool as any).tool_name;
      if(mode === "callback-unknown-kind") tool.tool_kind=99;
      if(mode === "callback-invalid-json") tool.input_json=Buffer.from("{");
      if(mode === "callback-invalid-utf8-string") tool.input_json=Buffer.from([34,255,34]);
      if(mode === "callback-invalid-utf8-trailing") tool.input_json=Buffer.from([123,125,255]);
      if(mode === "callback-invalid-utf8-escape") tool.input_json=Buffer.from([34,92,255,34]);
      if(mode === "callback-invalid-utf8-whitespace") tool.input_json=Buffer.from([32,255]);
      if(mode === "callback-repeated-after-completion") lateDuplicate=tool;
      if(mode === "callback-null") tool.input_json=Buffer.from("null");
      if(mode === "callback-omitted") delete (tool as any).input_json;
      if(mode === "callback-empty-runtime-id") tool.runtime_tool_call_id="";
      if(mode === "callback-cancel-before-invoke") write(lease,"OpenSession",{tool_call_cancelled:{invocation_id:invocation}});
      setTimeout(()=>{
        write(subscription,"SubscribeToToolCalls",tool);
        if(mode === "callback-repeated-id") write(subscription,"SubscribeToToolCalls",tool);
      },5);
    }
    if(mode.startsWith("notification-")) {
      const notification_id=mode === "notification-invalid-uuid" ? "bad-uuid" : randomUUID();
      write(lease,"OpenSession",{notification:{notification_id,execution_id:execution,cell_id:cell,
        call_id:mode === "notification-wrong-call" ? "wrong-call" : request.tool_call_id,text:"scripted notification"}});
      if(mode === "notification-duplicate-id") write(lease,"OpenSession",{notification:{notification_id,execution_id:execution,cell_id:cell,call_id:request.tool_call_id,text:"scripted notification"}});
      if(mode === "notification-cancelled-event") write(lease,"OpenSession",{notification_cancelled:{notification_id}});
    }
    if(mode === "callback-completion-boundary") return;
    setTimeout(()=>{
      write(call,"Execute",{outcome:outcome(cell,mode.startsWith("wait-") ? "yielded" : "completed")});call.end();
      if(!mode.startsWith("wait-") && mode !== "closed-before-start") write(lease,"OpenSession",{cell_closed:{execution_id:execution,cell_id:cell,final_tool_call_sequence:"1"}});
    },25);
  },
  wait(call:any,callback:any) {
    trace("received","Wait",call.request);
    if(mode === "wait-missing-state") {reply(callback,{});return;}
    if((mode === "wait-reopen-fails-once" || mode === "wait-shutdown-blocked-reopen") && opens === 1) {callback({code:grpc.status.NOT_FOUND,details:"scripted missing session"});return;}
    if(mode === "wait-session-not-found") {callback({code:grpc.status.NOT_FOUND,details:"scripted missing session"});return;}
    if(mode === "wait-route-unavailable") {callback({code:grpc.status.UNAVAILABLE,details:"scripted missing route"});return;}
    if(mode === "wait-slot-cancel-delay" && ++waitCount === 1) return;
    const value:any=outcome(mode === "wait-wrong-cell" ? "wrong-cell" : call.request.cell_id);
    if(mode === "wait-no-outcome") delete value.completed;
    if(mode === "wait-empty-content") value.content_items=[{}];
    if(mode === "wait-image-enum") value.content_items=[{image:{image_url:"data:image/png;base64,AA==",detail:99}}];
    const metadata=new grpc.Metadata();metadata.set("codex-code-mode-result-buffered-for-ns",mode === "wait-buffered-invalid" ? "invalid" : "0");call.sendMetadata(metadata);
    reply(callback,{live_cell:value});
    if(last) setTimeout(()=>write(lease,"OpenSession",{cell_closed:{execution_id:last.request.execution_id,cell_id:last.cell,final_tool_call_sequence:"0"}}),1);
  },
  terminate(call:any,callback:any) {trace("received","Terminate",call.request);reply(callback,{live_cell:outcome(call.request.cell_id,"terminated")});},
  completeToolCall(call:any,callback:any) {
    trace("received","CompleteToolCall",call.request);
    if(mode === "callback-complete-not-found"){callback({code:grpc.status.NOT_FOUND,details:"scripted uncancelled invocation missing"});return;}
    if(mode === "callback-cancel-during-completion"){
      call.on("cancelled",()=>trace("cancelled","CompleteToolCall",{}));
      write(lease,"OpenSession",{tool_call_cancelled:{invocation_id:call.request.invocation_id}});return;
    }
    reply(callback,{});
    if(mode === "callback-repeated-after-completion") write(subscription,"SubscribeToToolCalls",lateDuplicate);
    if(mode === "callback-completion-boundary"){
      write(last.call,"Execute",{outcome:outcome(last.cell)});last.call.end();
      write(lease,"OpenSession",{cell_closed:{execution_id:last.request.execution_id,cell_id:last.cell,final_tool_call_sequence:"1"}});
    }
  },
  acknowledgeNotification(call:any,callback:any) {trace("received","AcknowledgeNotification",call.request);if(mode === "notification-ack-unavailable"){callback({code:grpc.status.UNAVAILABLE,details:"scripted irrelevant acknowledgement failure"});return;}reply(callback,{});},
  cancelWait(call:any,callback:any) {trace("received","CancelWait",call.request);if(mode === "wait-slot-cancel-delay"){setTimeout(()=>{trace("sent","CancelWait",{});reply(callback,{});},180);return;}reply(callback,{});},
  yieldObservation(call:any,callback:any) {trace("received","YieldObservation",call.request);reply(callback,{});},
});

const port=await new Promise<number>((accept,reject)=>server.bindAsync("127.0.0.1:0",grpc.ServerCredentials.createInsecure(),(error,port)=>error?reject(error):accept(port)));
const socket=connect(port,"127.0.0.1");
process.stdin.pipe(socket);socket.pipe(process.stdout);socket.on("error",()=>process.exit(0));
setTimeout(()=>process.exit(124),120_000).unref();
