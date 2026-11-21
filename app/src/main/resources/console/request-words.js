// How a request's figures and endings read, in words: a time as a person says it, how a reply ended in each dialect's
// own field, and a Java exception from a send that failed inside splice. One copy, loaded by every page that draws a
// request (Activity's full request in deck-request.js, Providers' Compare models answers), so the same failure reads
// the same everywhere.
"use strict";

window.REQUEST_WORDS = (() => {
  // a time as a person says it: tenths of a second under 10 s, whole seconds to a minute, then minutes and seconds. The
  // seconds are rounded before they are split, so 119.6 s reads "2 min" and 59.6 s "1 min", never "1 min 60 s" or "60 s"
  const secs = (ms) => {
    if (ms < 1000) return `${ms} ms`;
    if (ms < 9950) return `${+(ms / 1000).toFixed(1)} s`;
    const s = Math.round(ms / 1000);
    return s < 60 ? `${s} s` : `${Math.floor(s / 60)} min${s % 60 ? ` ${s % 60} s` : ""}`;
  };
  // HOW A REPLY ENDED, IN WORDS: each dialect names it in its own field (Anthropic's stop_reason, a chat completion's
  // finish_reason, a Responses status), and "end_turn" is a value, not a sentence. The value stays under the pointer.
  const STOP_WORD = { end_turn: "Ended its turn", tool_use: "Called a tool", max_tokens: "Reached its output limit", stop_sequence: "Hit a stop sequence",
    pause_turn: "Paused its turn", refusal: "Refused", model_context_window_exceeded: "Ran out of context", stop: "Ended its turn", length: "Reached its output limit",
    tool_calls: "Called a tool", function_call: "Called a tool", content_filter: "Filtered by the provider", completed: "Completed", incomplete: "Incomplete",
    failed: "Failed", cancelled: "Cancelled", in_progress: "Still running" };
  // A SEND THAT FAILED INSIDE splice NAMES A JAVA EXCEPTION, which nobody reading the request should have to translate.
  // The common network failures read as what happened, any other exception keeps its own message without its class
  // name, and the raw text stays under the pointer (the attempt's title).
  const EXCEPTION_WORD = { ConnectException: "Could not connect", SocketTimeoutException: "Timed out", HttpTimeoutException: "Timed out", TimeoutException: "Timed out",
    HttpConnectTimeoutException: "Timed out connecting", UnknownHostException: "Unknown host", SSLException: "Secure connection failed",
    SSLHandshakeException: "Secure connection failed", EOFException: "The connection closed early", ClosedChannelException: "The connection closed",
    SocketException: "The connection broke", NoRouteToHostException: "No route to the host" };
  const failureWords = (text) => {
    const m = /(?:^|\s)(?:[a-z_$][\w$]*\.)*([A-Z][\w$]*(?:Exception|Error))(?::\s*([\s\S]*))?$/.exec(String(text ?? ""));
    if (!m) return text;
    const said = m[2]?.trim(), word = EXCEPTION_WORD[m[1]], told = said && !/^null$/i.test(said) ? said : "";
    if (word) return told ? `${word} · ${told}` : word;
    return told || m[1].replace(/(Exception|Error)$/, "").replace(/([a-z])([A-Z])/g, (_, a, b) => `${a} ${b.toLowerCase()}`);
  };
  return { secs, STOP_WORD, EXCEPTION_WORD, failureWords };
})();
