// V4-444: the Playground sends one prompt to several models and lays their answers side by side. Each lane is a command and a
// model on it, and the lanes live in the address (`?try=claudex:gpt-6-luna&try=claudeor`), so the Models table can open one and a
// comparison can be linked. What comes back is each provider's own reply, whole or streamed, read here for its text and token counts
// in the three shapes the daemon's dialects answer in: Anthropic messages, OpenAI chat completions and OpenAI responses.

/** One column: a command, and the model on it to run, or null for the command's pinned model. */
export interface Lane {
  head: string;
  model: string | null;
}

/** Four answers side by side is what a wide screen reads; more is a list, not a comparison. */
export const MAX_LANES = 4;

/** `head` alone, or `head:model`. A command key never holds a colon and a model id may (`meta-llama/llama-3:free`), so the first
 *  colon is the split. */
export function lanesOf(params: URLSearchParams): Lane[] {
  return params.getAll('try').flatMap((value): Lane[] => {
    const colon = value.indexOf(':');
    const head = colon < 0 ? value : value.slice(0, colon);
    const model = colon < 0 ? null : value.slice(colon + 1);
    return head === '' ? [] : [{ head, model: model === '' ? null : model }];
  }).slice(0, MAX_LANES);
}

export const laneParam = (lane: Lane): string => (lane.model === null ? lane.head : `${lane.head}:${lane.model}`);

/** The address of a set of lanes, in their order. */
export const lanesSearch = (lanes: readonly Lane[]): [string, string][] => lanes.map((lane) => ['try', laneParam(lane)]);

/** A command whose auth kind is `client` forwards the caller's own login (ClientAuthProvider), and the Playground has no caller
 *  login to forward, so the daemon refuses it. It is left out of the picker rather than offered to fail. */
export const FORWARDED_AUTH = 'client';
export const canTry = (authKind: string): boolean => authKind !== FORWARDED_AUTH;

/** With no lanes in the address, the first two commands that can be tried, each on its pinned model. */
export const defaultLanes = (commands: readonly string[]): Lane[] => commands.slice(0, 2).map((head) => ({ head, model: null }));

/** The lane "Add a model" opens: the first command no lane uses yet, else the first command again for a second model on it. */
export function nextLane(lanes: readonly Lane[], commands: readonly string[]): Lane | null {
  const head = commands.find((key) => !lanes.some((lane) => lane.head === key)) ?? commands[0];
  return lanes.length >= MAX_LANES || head === undefined ? null : { head, model: null };
}

/** A key per lane that survives removing another lane: the lane's address and how many identical lanes come before it. Changing a
 *  lane's command or model changes its key, so its last answer, which was for something else, goes with it. */
export function laneKeys(lanes: readonly Lane[]): string[] {
  const seen = new Map<string, number>();
  return lanes.map((lane) => {
    const param = laneParam(lane);
    const n = seen.get(param) ?? 0;
    seen.set(param, n + 1);
    return `${param}#${n}`;
  });
}

/** What one provider answered, read for a person: its text, its token counts and its own error sentence, each null when the reply
 *  does not carry it. The raw reply is always shown beside this, so nothing read here hides anything. */
export interface Answer {
  text: string | null;
  input: number | null;
  output: number | null;
  error: string | null;
}

type Json = Record<string, unknown>;
const record = (value: unknown): Json | null => (typeof value === 'object' && value !== null && !Array.isArray(value) ? (value as Json) : null);
const list = (value: unknown): unknown[] => (Array.isArray(value) ? value : []);
const text = (value: unknown): string | null => (typeof value === 'string' ? value : null);
const count = (value: unknown): number | null => (typeof value === 'number' && Number.isFinite(value) ? value : null);

/** The text parts of a content list: Anthropic's `{type: 'text'}`, chat's `{type: 'text'}` and responses' `{type: 'output_text'}`. */
const partsText = (parts: unknown): string[] =>
  list(parts).flatMap((part) => {
    const item = record(part);
    return item !== null && (item.type === 'text' || item.type === 'output_text') ? (text(item.text) ?? []) : [];
  });

function textOf(body: Json): string | null {
  const raw = text(body.raw);
  const pieces = [
    ...partsText(body.content),
    ...list(body.choices).flatMap((choice) => {
      const content = record(record(choice)?.message)?.content;
      return text(content) ?? partsText(content);
    }),
    ...list(body.output).flatMap((item) => (record(item)?.type === 'message' ? partsText(record(item)?.content) : [])),
    ...(raw === null ? [] : [raw]),
  ];
  return pieces.length === 0 ? null : pieces.join('\n\n');
}

function errorOf(body: Json): string | null {
  const error = body.error;
  return text(record(error)?.message) ?? text(error) ?? text(body.message);
}

/** A streamed reply reaches the page as `{raw: "event: …\ndata: {…}\n\n…"}`, since the daemon hands back whatever the provider sent.
 *  Its `data:` lines, each read as JSON; none when the text is not a server-sent event stream. */
function eventsOf(raw: string): Json[] {
  return raw.split('\n').flatMap((line) => {
    const data = line.startsWith('data:') ? line.slice(5).trim() : '';
    if (data === '' || data === '[DONE]') return [];
    try {
      const event = record(JSON.parse(data));
      return event === null ? [] : [event];
    } catch {
      return [];
    }
  });
}

/** A stream put together in each dialect: responses' `output_text` deltas with the usage on `response.completed`, Anthropic's text
 *  deltas with the input count from `message_start` and the output count from the last `message_delta`, and chat's choice deltas with
 *  the usage on the last chunk. A later count replaces an earlier one; a count a later event leaves out is kept. An error event is the
 *  answer's error whatever the status said, since the status was sent before the stream failed. */
function streamed(events: readonly Json[]): Answer {
  let said = '';
  let input: number | null = null;
  let output: number | null = null;
  let error: string | null = null;
  for (const event of events) {
    const delta = record(event.delta);
    if (event.type === 'response.output_text.delta') said += text(event.delta) ?? '';
    if (event.type === 'content_block_delta' && delta?.type === 'text_delta') said += text(delta.text) ?? '';
    for (const choice of list(event.choices)) said += text(record(record(choice)?.delta)?.content) ?? '';
    const usage = record(event.usage) ?? record(record(event.response)?.usage) ?? record(record(event.message)?.usage);
    if (usage !== null) {
      input = count(usage.input_tokens) ?? count(usage.prompt_tokens) ?? input;
      output = count(usage.output_tokens) ?? count(usage.completion_tokens) ?? output;
    }
    if (event.type === 'error') error = errorOf(event) ?? error;
    if (event.type === 'response.failed') error = errorOf(record(event.response) ?? {}) ?? error;
  }
  return { text: said === '' ? null : said, input, output, error };
}

/** A reply read for its text, tokens and error. The error is read only from a reply whose status says it failed, or a stream that
 *  reported one. */
export function answerOf(status: number, reply: unknown): Answer {
  const body = record(reply) ?? {};
  const raw = text(body.raw);
  const events = raw === null ? [] : eventsOf(raw);
  if (events.length > 0) return streamed(events);
  const usage = record(body.usage) ?? {};
  return {
    text: textOf(body),
    input: count(usage.input_tokens) ?? count(usage.prompt_tokens),
    output: count(usage.output_tokens) ?? count(usage.completion_tokens),
    error: status >= 400 ? errorOf(body) : null,
  };
}
