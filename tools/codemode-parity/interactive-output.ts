import assert from "node:assert/strict";

type ModelRequest = { nativeInput: any[] };

/** Only protocol tool-result content arrays carry typed media. Text and metadata never do. */
export function assertNativeTypedMedia(requests: ModelRequest[]): void {
  const latest = new Map<string, any>();
  for (const request of requests) for (const item of request.nativeInput) {
    if (["custom_tool_call_output", "function_call_output"].includes(item.type)) latest.set(`${item.type}:${item.call_id}`, item);
  }
  const typed = [...latest.values()].flatMap(item => Array.isArray(item.output) ? item.output : []);
  assert.ok(typed.some(item => item?.type === "input_image" && item.image_url === "data:image/png;base64,aGVsbG8=" && item.detail === "original"),
    "original image absent from protocol output content arrays");
  assert.ok(typed.some(item => item?.type === "input_audio" && item.audio_url === "data:audio/wav;base64,aGVsbG8="),
    "audio absent from protocol output content arrays");
  assert.ok(typed.some(item => item?.type === "input_image" && item.image_url === "data:image/png;base64,d29ybGQ=" && item.detail === "high"),
    "generated image absent from protocol output content arrays");
  assert.ok(typed.some(item => item?.type === "input_text" && item.text === "native-generated"),
    "generated image hint absent from protocol output content arrays");
}
