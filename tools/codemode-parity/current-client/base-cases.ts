type Tool = { name: string; tool_name: { name: string; namespace?: string }; description: string; kind: string };
const tool = (name: string, kind = "function", namespace?: string): Tool => ({
  name: namespace ? namespace + name : name, tool_name: { name, ...(namespace ? { namespace } : {}) }, description: name, kind,
});

export const cases: any[] = [
  { id: "rust-client/store-session-isolation-and-shutdown", steps: [
    { op: "execute", as: "store", source: 'store("answer",{n:42});text(load("answer"));' },
    { op: "execute", as: "load", source: 'text(load("answer"));' },
    { op: "open", session: "other" },
    { op: "execute", session: "other", as: "isolated", source: 'text(load("answer") ?? "absent");' },
    { op: "shutdown" }, { op: "shutdown" },
    { op: "execute", source: 'text("after-shutdown");' },
  ] },
  { id: "rust-client/function-omission-namespace-and-freeform", steps: [
    { op: "execute", as: "calls", enabled_tools: [tool("plain"), tool("echo", "function", "mcp__svc__"), tool("raw", "freeform")],
      source: 'text(await tools.plain());text(await tools.mcp__svc__echo({n:1}));text(await tools.raw("literal"));' },
  ] },
  { id: "rust-client/parallel-callback-independent-results", steps: [
    { op: "execute", as: "parallel", enabled_tools: [tool("slow"), tool("fast"), tool("dependent")],
      callbacks: { slow: { callback_delay_ms: 60 }, fast: { callback_delay_ms: 1 }, dependent: { callback_delay_ms: 1 } },
      source: 'const a=tools.slow({branch:"slow"});const b=tools.fast({branch:"fast"}).then(async v=>{text(v);return tools.dependent({branch:"next"});});text(await Promise.all([a,b]));' },
  ] },
  { id: "rust-client/timed-yield-wait-and-missing-cell", steps: [
    { op: "execute", as: "timer", yield_time_ms: 2, source: 'text("before");await new Promise(r=>setTimeout(r,40));text("after");' },
    { op: "wait", cell: "timer", yield_time_ms: 1000 },
    { op: "wait", cell: "timer", yield_time_ms: 1 },
    { op: "terminate", cell: "timer" },
    { op: "wait", cell: "missing-cell", yield_time_ms: 1 },
    { op: "terminate", cell: "missing-cell" },
  ] },
  { id: "rust-client/cancel-wait-then-short-wait-and-terminate", steps: [
    { op: "execute", as: "timer", yield_time_ms: 1, source: 'text("before");await new Promise(r=>setTimeout(r,500));text("after");' },
    { op: "wait", cell: "timer", yield_time_ms: 2000, cancel_after_ms: 10 },
    { op: "wait", cell: "timer", yield_time_ms: 2 },
    { op: "terminate", cell: "timer" },
    { op: "terminate", cell: "timer" },
  ] },
  { id: "rust-client/preempt-execute-and-wait-observations", steps: [
    { op: "execute", as: "timer", yield_time_ms: 1000, preempt_after_ms: 5,
      source: 'text("before");await new Promise(r=>setTimeout(r,100));text("after");' },
    { op: "wait", cell: "timer", yield_time_ms: 1000, preempt_after_ms: 2 },
    { op: "wait", cell: "timer", yield_time_ms: 1000 },
  ] },
  { id: "rust-client/deferred-initial-notification-barrier", steps: [
    { op: "execute", as: "deferred", defer_initial: true, notification_delay_ms: 30,
      source: 'notify("progress");text("final");' },
    { op: "sleep", ms: 10 }, { op: "initial", cell: "deferred" },
    { op: "wait", cell: "deferred", yield_time_ms: 1 },
  ] },
  { id: "rust-client/terminate-cancels-pending-notification", steps: [
    { op: "execute", as: "notice", yield_time_ms: 2, notification_delay_ms: 5000,
      source: 'notify("progress");await new Promise(()=>{});' },
    { op: "terminate", cell: "notice" },
  ] },
  { id: "rust-client/delegate-error-rejects-native-promise", steps: [
    { op: "execute", as: "rejection", enabled_tools: [tool("fail")], callback_error: "exact delegate failure",
      source: 'try{await tools.fail({});}catch(error){text(error.message);}' },
  ] },
  { id: "rust-client/uint64-wait-and-session-yield-limit", limits: { max_yield_time_ms: 5 }, steps: [
    { op: "execute", as: "forever", yield_time_ms: 1000, source: 'text("start");await new Promise(()=>{});' },
    { op: "wait", cell: "forever", yield_time_ms: "18446744073709551615" },
    { op: "terminate", cell: "forever" },
  ] },
  { id: "rust-client/compile-error-has-no-prefix-effects", steps: [
    { op: "execute", as: "syntax", source: 'store("prefix","bad");export const = ;' },
    { op: "execute", as: "after", source: 'text(load("prefix") ?? "absent");' },
  ] },
  { id: "rust-client/output-budget-zero-and-media-codec", steps: [
    { op: "execute", as: "zero", max_output_tokens: 0, source: 'text("unpublished");' },
    { op: "execute", as: "media", source: 'text("hello");image("data:image/png;base64,AA==");audio("data:audio/wav;base64,AA==");' },
  ] },
  { id: "rust-client/host-loss-fails-live-observation", steps: [
    { op: "execute", as: "lost", yield_time_ms: 2, source: 'await new Promise(()=>{});' },
    { op: "crash_host" }, { op: "sleep", ms: 10 },
    { op: "wait", cell: "lost", yield_time_ms: 1 }, { op: "shutdown" },
  ] },
];
