import assert from "node:assert/strict";

type ModelRequest = { nativeInput: any[] };

function toolOutputs(requests: ModelRequest[]): any[] {
  const latest = new Map<string, any>();
  for (const request of requests) for (const item of request.nativeInput) {
    if (["custom_tool_call_output", "function_call_output"].includes(item.type)) latest.set(`${item.type}:${item.call_id}`, item);
  }
  return [...latest.values()];
}

/** Only protocol tool-result content arrays carry typed media. Text and metadata never do. */
export function assertNativeTypedMedia(requests: ModelRequest[]): void {
  const typed = toolOutputs(requests).flatMap(item => Array.isArray(item.output) ? item.output : []);
  assert.ok(typed.some(item => item?.type === "input_image" && item.image_url === "data:image/png;base64,aGVsbG8=" && item.detail === "original"),
    "original image absent from protocol output content arrays");
  assert.ok(typed.some(item => item?.type === "input_audio" && item.audio_url === "data:audio/wav;base64,aGVsbG8="),
    "audio absent from protocol output content arrays");
  assert.ok(typed.some(item => item?.type === "input_image" && item.image_url === "data:image/png;base64,d29ybGQ=" && item.detail === "high"),
    "generated image absent from protocol output content arrays");
  assert.ok(typed.some(item => item?.type === "input_text" && item.text === "native-generated"),
    "generated image hint absent from protocol output content arrays");
}

/** The callback's non-JSON stdout must stay text across Claude, the gateway, and native V8. */
export function assertAcceptedCallbackText(requests: ModelRequest[]): void {
  const completed = toolOutputs(requests).filter(item =>
    (item.call_id === "call_probe" || /^wait_call_/.test(item.call_id ?? "")) && Array.isArray(item.output) &&
    item.output[0]?.type === "input_text" && /^Script completed\n/.test(item.output[0].text ?? ""));
  assert.ok(completed.length > 0, "first callback source never produced a completed exec/wait output");
  for (const item of completed) assert.equal(item.output.filter((part: any) =>
    part?.type === "input_text" && part.text === "accepted").length, 1,
  "completed callback output lost or changed the literal accepted stdout");
}

/** The restart receipt preserves accepted client text without claiming the destroyed continuation completed. */
export function assertInterruptedCallbackText(requests: ModelRequest[]): void {
  const output = toolOutputs(requests).find(item => item.type === "custom_tool_call_output" && item.call_id === "call_probe")?.output;
  assert.equal(typeof output, "string", "restart must preserve the first source's explicit interruption receipt");
  assert.match(output, /^Script terminated\n/);
  const delimiter = "\nOutput:\n";
  assert.ok(output.includes(delimiter));
  const interrupted = JSON.parse(output.slice(output.indexOf(delimiter) + delimiter.length));
  assert.equal(interrupted.status, "interrupted");
  assert.equal(interrupted.sourceRerun, false);
  assert.equal(interrupted.results?.length, 1);
  assert.equal(interrupted.results[0].output, "accepted", "restart changed the accepted callback's plain text");
  assert.equal(interrupted.results[0].isError, false);
}
