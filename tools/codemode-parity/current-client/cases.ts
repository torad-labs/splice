import { cases as originalCases } from "./base-cases";
import { faultCases as originalFaults } from "./base-fault-cases";
import focusedCases from "./focused-cases.json";
const plain={name:"plain",tool_name:{name:"plain"},description:"plain",kind:"function"};
export const cases=[...originalCases,
  {id:"rust-client/explicit-tonic-channel-provider",provider:"channel",steps:[{op:"execute",as:"channel",source:'text("channel");'},{op:"wait",cell:"missing-cell",yield_time_ms:1}]},
  {id:"rust-client/release-serde-object-key-order",steps:[{op:"execute",as:"keys",enabled_tools:[plain],source:'text(await tools.plain({z:1,a:2}));text(Object.keys(await tools.plain({z:1,a:2})));'}]},
];
const recoverable=new Set(["execute-missing-event","execute-outcome-before-start","execute-repeated-start","wait-missing-state","wait-no-outcome","wait-empty-content","wait-image-enum"]);
export const faultCases=[...originalFaults.map(input=>recoverable.has(input.fault_mode)?{...input,steps:[...input.steps,{op:"execute",as:"after-fault",source:"scripted recovery request"}]}:input),
  ...["callback-complete-not-found","notification-ack-unavailable","callback-empty-runtime-id","notification-duplicate-id"].map(fault_mode=>({id:`rust-client/fault/${fault_mode}`,fault_mode,steps:[{op:"execute",as:"fake",source:"scripted peer request",enabled_tools:[plain],notification_delay_ms:50}]})),
];
export const currentClientCases:any[]=[...cases.map(input=>input.id==="rust-client/store-session-isolation-and-shutdown"||input.id==="rust-client/host-loss-fails-live-observation"?{...input,provider:"process",adapter:"logical"}:input),...faultCases,...focusedCases];
export function expandedClientInput(case_:any,nonce:string):any{
  const input={...case_,nonce,steps:case_.steps.map((step:any)=>({...step}))};
  if(case_.callback_result_bytes!==undefined){if(!Number.isSafeInteger(case_.callback_result_bytes)||case_.callback_result_bytes<0||case_.callback_result_bytes>64*1024*1024)throw Error("invalid deterministic completion boundary size");input.steps[0].callback_result="x".repeat(case_.callback_result_bytes);}
  return input;
}
