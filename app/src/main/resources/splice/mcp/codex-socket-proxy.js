// The shared Codex control endpoint speaks WebSocket, not the stdio protocol's raw newline stream.
// The bridge proves the actual connected Linux peer before it sends any configuration RPC.
import { createHash, randomBytes, timingSafeEqual } from 'node:crypto';
import { lstatSync, readFileSync, realpathSync } from 'node:fs';
import { createConnection } from 'node:net';
import { dlopen, FFIType } from 'bun:ffi';

const MAX_BYTES = 8_388_608;
const MAX_HTTP_BYTES = 16_384;
const MAX_RETAINED_BYTES = MAX_BYTES * 2;
const SOL_SOCKET = 1;
const SO_PEERCRED = 17;
const UCRED_BYTES = 12;
const PROC_START_TICKS_INDEX_AFTER_COMMAND = 19;
const WEBSOCKET_GUID = '258EAFA5-E914-47DA-95CA-C5AB0DC85B11';
const [control, registeredScript, expectedPid, expectedStartTicks, expectedBootId] = process.argv.slice(2);
let transport;
let pending = Buffer.alloc(0);
let opened = false;
let fragment;
let incoming = '';
let stdoutBlocked = false;
let stdinBlocked = false;

function fail() {
  process.stderr.write('Native Codex WebSocket transport failed.\n');
  transport?.destroy();
  process.exit(1);
}

function expectedGeneration() {
  if (process.platform !== 'linux') return null;
  if (!/^[1-9]\d*$/.test(expectedPid) || !/^[1-9]\d*$/.test(expectedStartTicks) || !expectedBootId) return null;
  return { pid: Number(expectedPid), startTicks: BigInt(expectedStartTicks), bootId: expectedBootId };
}

function peerCredentials() {
  return dlopen('libc.so.6', {
    getsockopt: {
      args: [FFIType.i32, FFIType.i32, FFIType.i32, FFIType.ptr, FFIType.ptr],
      returns: FFIType.i32,
    },
  });
}

function sameGeneration(generation, library) {
  const fd = transport._handle?.fd;
  if (!Number.isInteger(fd) || generation === null || library === null) return false;
  const credentials = new Int32Array(UCRED_BYTES / Int32Array.BYTES_PER_ELEMENT);
  const size = new Uint32Array([credentials.byteLength]);
  if (library.symbols.getsockopt(fd, SOL_SOCKET, SO_PEERCRED, credentials, size) !== 0 ||
    size[0] !== credentials.byteLength || credentials[0] !== generation.pid) return false;
  return bootId() === generation.bootId && processStartTicks(generation.pid) === generation.startTicks;
}

function bootId() {
  return readFileSync('/proc/sys/kernel/random/boot_id', 'utf8').trim();
}

function processStartTicks(pid) {
  const stat = readFileSync(`/proc/${pid}/stat`, 'utf8');
  const commandEnd = stat.lastIndexOf(')');
  if (commandEnd < 0) return null;
  const tick = stat.slice(commandEnd + 1).trim().split(/\s+/)[PROC_START_TICKS_INDEX_AFTER_COMMAND];
  return /^[1-9]\d*$/.test(tick ?? '') ? BigInt(tick) : null;
}

function expectedSocket(path, account) {
  const target = lstatSync(path, { bigint: true });
  return account !== undefined && target.uid === BigInt(account) && target.isSocket();
}

function openWebSocket(endpoint, account, generation, library) {
  const key = randomBytes(16).toString('base64');
  transport = createConnection(endpoint);
  transport.on('connect', () => {
    if (!expectedSocket(control, account) || !expectedSocket(endpoint, account) ||
      (process.platform === 'linux' && !sameGeneration(generation, library))) fail();
    transport.write([
      'GET / HTTP/1.1',
      'Host: localhost',
      'Upgrade: websocket',
      'Connection: Upgrade',
      `Sec-WebSocket-Key: ${key}`,
      'Sec-WebSocket-Version: 13',
      '',
      '',
    ].join('\r\n'));
  });
  transport.on('data', chunk => received(chunk, key));
  transport.on('error', fail);
  transport.on('close', () => {
    process.stdin.destroy();
    if (!opened) process.exitCode = 1;
  });
}

function received(chunk, key) {
  if (pending.length + chunk.length + (fragment?.length ?? 0) + process.stdout.writableLength > MAX_RETAINED_BYTES) fail();
  pending = Buffer.concat([pending, chunk]);
  if (!opened && !accepted(key)) return;
  frames();
}

function accepted(key) {
  const end = pending.indexOf('\r\n\r\n');
  if (end < 0) {
    if (pending.length > MAX_HTTP_BYTES) fail();
    return false;
  }
  const lines = pending.subarray(0, end).toString('ascii').split('\r\n');
  pending = pending.subarray(end + 4);
  const headers = new Map(lines.slice(1).flatMap(line => {
    const colon = line.indexOf(':');
    return colon > 0 ? [[line.slice(0, colon).toLowerCase(), line.slice(colon + 1).trim()]] : [];
  }));
  const expected = createHash('sha1').update(key + WEBSOCKET_GUID).digest();
  const actual = Buffer.from(headers.get('sec-websocket-accept') ?? '', 'base64');
  if (lines[0] !== 'HTTP/1.1 101 Switching Protocols' || actual.length !== expected.length ||
    !timingSafeEqual(actual, expected)) fail();
  opened = true;
  process.stdin.resume();
  return true;
}

function frames() {
  if (stdoutBlocked) return;
  while (pending.length >= 2) {
    const first = pending[0];
    const second = pending[1];
    const opcode = first & 0x0f;
    const final = (first & 0x80) !== 0;
    const masked = (second & 0x80) !== 0;
    let header = 2;
    let length = second & 0x7f;
    if ((first & 0x70) !== 0 || masked) fail();
    if (length === 126) {
      if (pending.length < 4) return;
      length = pending.readUInt16BE(2);
      header = 4;
    } else if (length === 127) {
      if (pending.length < 10) return;
      const wide = pending.readBigUInt64BE(2);
      if (wide > BigInt(MAX_BYTES)) fail();
      length = Number(wide);
      header = 10;
    }
    if (length > MAX_BYTES || pending.length < header + length) return;
    const payload = pending.subarray(header, header + length);
    pending = pending.subarray(header + length);
    if (!frame(opcode, final, payload)) return;
  }
}

function frame(opcode, final, payload) {
  if ((opcode & 0x08) !== 0) {
    if (!final || payload.length > 125) fail();
    if (opcode === 0x08) {
      sendFrame(0x08, payload);
      transport.end();
    } else if (opcode === 0x09) {
      sendFrame(0x0a, payload);
    } else if (opcode !== 0x0a) {
      fail();
    }
    return true;
  }
  if (opcode === 0x01) {
    if (fragment !== undefined) fail();
    if (final) return message(payload);
    fragment = payload;
    return true;
  }
  if (opcode === 0x00 && fragment !== undefined) {
    const joined = Buffer.concat([fragment, payload]);
    if (joined.length > MAX_BYTES) fail();
    if (!final) {
      fragment = joined;
      return true;
    }
    fragment = undefined;
    return message(joined);
  }
  fail();
}

function message(payload) {
  const line = Buffer.from(`${payload.toString('utf8')}\n`);
  if (pending.length + (fragment?.length ?? 0) + process.stdout.writableLength + line.length > MAX_RETAINED_BYTES) fail();
  if (process.stdout.write(line)) return true;
  stdoutBlocked = true;
  transport.pause();
  process.stdout.once('drain', () => {
    stdoutBlocked = false;
    transport.resume();
    frames();
  });
  return false;
}

function sendFrame(opcode, payload) {
  if (payload.length > MAX_BYTES) fail();
  const prefix = payload.length < 126 ? 2 : payload.length <= 0xffff ? 4 : 10;
  const size = prefix + 4 + payload.length;
  if ((transport?.writableLength ?? 0) + Buffer.byteLength(incoming) + size > MAX_RETAINED_BYTES) fail();
  const frame = Buffer.alloc(size);
  frame[0] = 0x80 | opcode;
  frame[1] = 0x80 | (payload.length < 126 ? payload.length : payload.length <= 0xffff ? 126 : 127);
  if (prefix === 4) frame.writeUInt16BE(payload.length, 2);
  if (prefix === 10) frame.writeBigUInt64BE(BigInt(payload.length), 2);
  const offset = prefix;
  const mask = randomBytes(4);
  mask.copy(frame, offset);
  for (let index = 0; index < payload.length; index++) frame[offset + 4 + index] = payload[index] ^ mask[index % 4];
  if (transport.write(frame) || stdinBlocked) return;
  stdinBlocked = true;
  process.stdin.pause();
  transport.once('drain', () => {
    stdinBlocked = false;
    input();
  });
}

function input() {
  let end;
  while (!stdinBlocked && (end = incoming.indexOf('\n')) >= 0) {
    const line = incoming.slice(0, end);
    incoming = incoming.slice(end + 1);
    if (line.trim()) sendFrame(0x01, Buffer.from(line, 'utf8'));
  }
  if (!stdinBlocked && opened) process.stdin.resume();
}

try {
  const account = process.getuid?.();
  const endpoint = realpathSync(control);
  const generation = expectedGeneration();
  const peer = process.platform === 'linux' ? peerCredentials() : null;
  if (!registeredScript || (process.platform === 'linux' && (generation === null || peer === null)) ||
    !expectedSocket(control, account) || !expectedSocket(endpoint, account) || endpoint.includes(':')) fail();
  process.stdin.pause();
  process.stdin.setEncoding('utf8');
  process.stdin.on('data', chunk => {
    if (Buffer.byteLength(incoming) + Buffer.byteLength(chunk) + (transport?.writableLength ?? 0) > MAX_RETAINED_BYTES) fail();
    incoming += chunk;
    input();
  });
  process.stdin.on('end', () => {
    if (opened) sendFrame(0x08, Buffer.alloc(0));
    transport?.end();
  });
  process.stdin.on('error', fail);
  process.stdout.on('error', fail);
  openWebSocket(endpoint, account, generation, peer);
} catch {
  fail();
}
