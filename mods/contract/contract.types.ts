// These calls are type-checked by mods/tsconfig.json, never executed.
import type {
  FullRequestSend, FullResponseReceive, FullStreamEvent, FullToolCall, FullTurnComplete, FullTurnStart, Labels, On, Payload,
  RequestSend, ResponseReceive, RowAction, SessionRow, StreamEvent, ToolCall, TurnComplete, TurnStart,
} from "./index.ts";

export function hookTypes(on: On<"full">, name: "ui.render" | "tool.call"): void {
  on("request.send", async (_, e, next) => {
    // @ts-expect-error The full contract does not expose request size either.
    e.bytes;
    return next({ ...e, body: e.body });
  });
  on("ui.render", { slot: "page", page: "queue" }, async (_, e, next) => {
    const answer = await next(e);
    // @ts-expect-error A render's next returns UiRenderAnswer, not ToolResult.
    answer.content;
    return answer;
  }).catch(async (_, e, next) => {
    e.error.message;
    // @ts-expect-error The error still carries a render request, not a tool call.
    e.arguments;
    return next(e);
  });
  on("tool.call", async (_, e, next) => {
    if (e.caller === "model") e.session.toUpperCase();
    // @ts-expect-error A tool call has no render slot.
    e.slot;
    return next(e);
  });
  // @ts-expect-error A union event might not accept this filter, even with a valid handler result.
  on(name, { slot: "page" }, async () => { throw new Error("unreachable"); });
  // @ts-expect-error A tool registration cannot specify a render filter.
  on("tool.call", { slot: "page" }, async (_, e, next) => next(e));
  const mixed = { tool: "resolve", slot: "page" } as const;
  // @ts-expect-error A known mismatched key is invalid even on a named filter object.
  on("tool.call", mixed, async (_, e, next) => next(e));
  const rowPage = { slot: "row", page: "queue" } as const;
  // @ts-expect-error A page name applies only to the page slot.
  on("ui.render", rowPage, async (_, e, next) => next(e));
  // @ts-expect-error A page name also cannot be used without selecting the page slot.
  on("ui.render", { page: "queue" }, async (_, e, next) => next(e));
  // @ts-expect-error A lifecycle registration has no filter.
  on("session.start", {}, async (_, e, next) => next(e));
}

export function observerTypes(on: On): void {
  on("request.send", async (_, e, next) => {
    e.round.toFixed();
    // @ts-expect-error A request observation has no serialized size.
    e.bytes;
    // @ts-expect-error A request observation has no body.
    e.body;
    return next(e);
  });
  on("tool.call", async (_, e) => {
    e.session?.toUpperCase();
    // @ts-expect-error A model observation never exposes arguments.
    e.arguments;
  });
  on("turn.complete", async (_, e) => {
    e.stopReason?.toUpperCase();
  });
}

const row = { id: "reviews:one", kind: "review", title: "Review", status: "waiting", mod: "reviews" } as const;
const action: RowAction = { label: "Approve", tool: "resolve" };
export const fourActions: SessionRow = { ...row, actions: [action, action, action, action] as const };
// @ts-expect-error A mod row requires a namespaced ID.
export const unprefixed: SessionRow = { ...row, id: "one" };
// @ts-expect-error A custom kind requires a mod owner.
export const unowned: SessionRow = { id: row.id, kind: row.kind, title: row.title, status: row.status };
// @ts-expect-error At most four actions are allowed.
export const tooManyActions: SessionRow = { ...row, actions: [action, action, action, action, action] as const };
// @ts-expect-error The v2 row wire is keyed by id, never session_id.
export const legacyId: SessionRow = { ...row, session_id: row.id };

export const appCall: FullToolCall = { caller: "app", server: "reviews", tool: "resolve", arguments: {} };
// @ts-expect-error A model call must have a session even when its arguments contain a session ID.
export const modelWithoutSession: FullToolCall = { ...appCall, caller: "model", arguments: { session: "one" } };

// The shapes an author reads in a handler today are the plain names; the wider contract's are Full*.
type Same<A, B> = (<T>() => T extends A ? 1 : 2) extends (<T>() => T extends B ? 1 : 2) ? true : false;
function pin<_ extends true>(): void {}
pin<Same<Payload<"turn.start">, TurnStart>>();
pin<Same<Payload<"request.send">, RequestSend>>();
pin<Same<Payload<"stream.event">, StreamEvent>>();
pin<Same<Payload<"tool.call">, ToolCall>>();
pin<Same<Payload<"response.receive">, ResponseReceive>>();
pin<Same<Payload<"turn.complete">, TurnComplete>>();
pin<Same<Payload<"turn.start", "full">, FullTurnStart>>();
pin<Same<Payload<"request.send", "full">, FullRequestSend>>();
pin<Same<Payload<"stream.event", "full">, FullStreamEvent>>();
pin<Same<Payload<"tool.call", "full">, FullToolCall>>();
pin<Same<Payload<"response.receive", "full">, FullResponseReceive>>();
pin<Same<Payload<"turn.complete", "full">, FullTurnComplete>>();

export function servedShapes(
  start: TurnStart, request: RequestSend, frame: StreamEvent, tool: ToolCall, response: ResponseReceive, end: TurnComplete,
): void {
  const everyTurnEvent = [start, request, frame, tool, response, end];
  for (const e of everyTurnEvent) {
    e.session?.toUpperCase();
    e.turn.toUpperCase();
    e.at.toFixed();
    const labels: Labels = e.labels;
    void labels;
  }
  start.head.toUpperCase();
  start.compact satisfies boolean;
  start.agent?.toUpperCase();
  request.round.toFixed();
  frame.seq.toFixed();
  frame.type.toUpperCase();
  frame.bytes.toFixed();
  tool.caller satisfies "model";
  tool.id.toUpperCase();
  tool.server?.toUpperCase();
  response.status.toFixed();
  response.attempt.toFixed();
  end.stopReason?.toUpperCase();
  end.outcome.toUpperCase();
  end.durationMs.toFixed();
  // Input is every token sent, cache reads and writes included, so these three are always present.
  end.usage.input + end.usage.output + end.usage.cacheRead + end.usage.cacheWrite;
  // @ts-expect-error A served turn start has no request body.
  start.body;
  // @ts-expect-error A served request has no body.
  request.body;
  // @ts-expect-error A served stream event has no frame.
  frame.event;
  // @ts-expect-error A served tool call has no arguments.
  tool.arguments;
  // @ts-expect-error A served response has no body.
  response.body;
  // @ts-expect-error A served turn end names the outcome the daemon reached, not a stop reason that is always present.
  end.stopReason.toUpperCase();
}

export function fullShapes(request: FullRequestSend, frame: FullStreamEvent, response: FullResponseReceive): void {
  request.body;
  frame.event;
  response.body;
  // @ts-expect-error A full request carries no round count.
  request.round;
}
