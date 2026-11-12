const plain = {name:"plain",tool_name:{name:"plain"},description:"plain",kind:"function"};
const admission = ["admission-unavailable-twice","admission-resource-four","admission-unavailable-five","admission-invalid-argument",
  "open-route-too-long","lease-end-before-open","open-no-event","open-empty-id","open-long-id"];
const executions = ["execute-missing-event","execute-outcome-before-start","execute-wrong-owner","execute-empty-cell","execute-repeated-start","closed-before-start",
  "callback-wrong-session","callback-invalid-uuid","callback-unenabled","callback-missing-name","callback-unknown-kind","callback-invalid-json",
  "callback-repeated-id","callback-cancel-before-invoke","callback-omitted","notification-invalid-uuid","notification-wrong-call","notification-cancelled-event"];
const waits = ["wait-missing-state","wait-session-not-found","wait-route-unavailable","wait-wrong-cell","wait-no-outcome","wait-empty-content","wait-image-enum","wait-buffered-invalid"];
export const faultCases = [
  ...admission.map(fault_mode=>({id:`rust-client/fault/${fault_mode}`,fault_mode,manual_open:true,steps:[{op:"open"}]})),
  ...executions.map(fault_mode=>({id:`rust-client/fault/${fault_mode}`,fault_mode,steps:[
    {op:"execute",as:"fake",source:"scripted peer request",enabled_tools:[plain],notification_delay_ms:10},
  ]})),
  ...waits.map(fault_mode=>({id:`rust-client/fault/${fault_mode}`,fault_mode,steps:[
    {op:"execute",as:"fake",source:"scripted peer request"},{op:"wait",cell:"fake",yield_time_ms:1},
    ...(fault_mode === "wait-session-not-found" || fault_mode === "wait-route-unavailable" ? [{op:"execute",as:"reconnected",source:"scripted reconnect request"}] : []),
  ]})),
];
