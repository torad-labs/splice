// POST /api/playground: one prompt through one head, the exchange handed back and never recorded (PlaygroundRoute).
export interface PlaygroundBody {
  head: string;
  prompt: string;
  /** The model to run; absent runs the head's pinned model. */
  model?: string;
}

/** The request the daemon sent upstream, every auth header redacted before it left the process. */
export interface PlaygroundSent {
  url: string;
  method: string;
  headers: Record<string, string>;
  body: unknown;
}

/** What the upstream answered: its status, and its body as JSON or `{"raw": text}`. An upstream error is a 200 here with the vendor's status. */
export interface PlaygroundReceived {
  status: number;
  body: unknown;
}

export interface PlaygroundWire {
  request: PlaygroundSent;
  response: PlaygroundReceived;
}
