// These calls are type-checked by mods/tsconfig.json, never executed.
import type { On, RowAction, SessionRow, ToolCall } from "./index.ts";

export function hookTypes(on: On, name: "ui.render" | "tool.call"): void {
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
    if (e.caller === "model") {
      e.session?.toUpperCase();
      // @ts-expect-error A model observation never exposes tool arguments.
      e.arguments;
    }
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

export const appCall: ToolCall = { caller: "app", server: "reviews", tool: "resolve", arguments: {} };
// @ts-expect-error A model call must have a session even when its arguments contain a session ID.
export const modelWithoutSession: ToolCall = { ...appCall, caller: "model", arguments: { session: "one" } };
