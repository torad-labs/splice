// A splice server for the checks: one fake self-hosted upstream, one head on it served as `claude-mock`, and a
// `[server]` table listening on a port of its own. A check adds what it needs inside [server] and beside it.

import { freePort, json, mockHead, Splice, turn, type Ran, type Turn, type TurnOptions } from "./harness.ts";
import { Upstream, type Behaviour } from "./upstream.ts";

export const COMMAND = "claude-mock";
export const MODEL = "mock-model";

export interface Minted {
  key: string;
  prefix: string;
  person: string;
  name?: string | null;
  expires_at: string | null;
  source?: string;
}

export interface ServerOptions {
  /** Lines inside [server], after listen and commands. */
  server?: string;
  /** Tables after [server] and the head. */
  toml?: string;
  upstream?: Partial<Behaviour>;
  /** Listen with TLS from these PEM files. */
  tls?: { cert: string; key: string };
  env?: Record<string, string>;
  /** Write the config but don't start the daemon. */
  noStart?: boolean;
  /** The provider and head tables in place of the one-upstream default, given the default upstream's URL and a
   *  free port for the head. They must serve COMMAND. */
  head?: (url: string, port: number) => string;
  /** The commands [server] serves, COMMAND alone by default. Every one must be some head's command. */
  commands?: string[];
}

/** The whole splice.toml of a server: [server], then the head tables, then the options' own tables. */
function compose(o: ServerOptions, url: string, port: number, headPort: number): string {
  const tls = o.tls ? `tls_cert = "${o.tls.cert}"\ntls_key = "${o.tls.key}"\n` : "";
  const commands = (o.commands ?? [COMMAND]).map((c) => `"${c}"`).join(", ");
  return (
    `[server]\nlisten = "127.0.0.1:${port}"\ncommands = [${commands}]\n${tls}${o.server ?? ""}\n` +
    (o.head ? o.head(url, headPort) : mockHead({ key: "mock", command: COMMAND, port: headPort, url, model: MODEL })) +
    (o.toml ?? "")
  );
}

export class Server {
  readonly s: Splice;
  readonly up: Upstream;
  readonly port: number;
  readonly scheme: "http" | "https";

  private readonly headPort: number;

  private constructor(s: Splice, up: Upstream, port: number, scheme: "http" | "https", headPort: number) {
    this.s = s;
    this.up = up;
    this.port = port;
    this.scheme = scheme;
    this.headPort = headPort;
  }

  static async boot(o: ServerOptions = {}): Promise<Server> {
    const up = new Upstream("a", MODEL, o.upstream).start();
    const port = await freePort();
    const headPort = await freePort();
    const s = await Splice.create(compose(o, up.url, port, headPort), o.env);
    const srv = new Server(s, up, port, o.tls ? "https" : "http", headPort);
    if (o.noStart) return srv;
    try {
      await s.start();
    } catch (e) {
      await srv.dispose();
      throw e;
    }
    return srv;
  }

  /** Write [o] as this server's whole config, on the same ports and upstream, and restart it. */
  async reconfigure(o: ServerOptions): Promise<void> {
    this.s.writeConfig(compose(o, this.up.url, this.port, this.headPort));
    await this.s.restart();
  }

  get base(): string {
    return `${this.scheme}://127.0.0.1:${this.port}`;
  }

  /** Where a turn on the served command goes. */
  get turnUrl(): string {
    return `${this.base}/${COMMAND}/v1/messages`;
  }

  turn(key: string | undefined, o: TurnOptions = {}): Promise<Turn> {
    return turn(this.turnUrl, { key, model: MODEL, insecure: this.scheme === "https", ...o });
  }

  /** `splice server key create --person <person> --json <more>`. */
  mint(person: string, more: string[] = []): Minted {
    const r = this.s.cli(["server", "key", "create", "--person", person, "--json", ...more]);
    if (r.code !== 0) throw new Error(`server key create exited ${r.code}:\n${r.out}\n${r.err}`);
    return json<Minted>(r);
  }

  cli(args: string[], input?: string): Ran {
    return this.s.cli(args, input);
  }

  async dispose(): Promise<void> {
    await this.s.dispose();
    this.up.stop();
  }
}
