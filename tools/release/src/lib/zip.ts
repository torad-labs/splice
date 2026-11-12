// ONE ENTRY OUT OF A ZIP, by name — what `zipfile.ZipFile(...).read(entry)` did for
// checks/release/accept.sh:74-80, with no Python and no unzip(1) on the box.
//
// Deliberately minimal: the central directory is walked once, the wanted entries' local headers are
// read, and STORED (0) and DEFLATE (8) are the two methods a JDK-built jar uses. Anything else —
// a missing end-of-central-directory, a zip64 sentinel, an unknown method, a truncated record — is
// an Error naming the file and the entry, never a silent empty buffer: accept COMPARES these bytes
// to the staged sidecars, and two empty buffers compare equal.
import { closeSync, openSync, readSync, statSync } from "node:fs";
import { inflateRawSync } from "node:zlib";

const EOCD_SIGNATURE = 0x06054b50;
const CENTRAL_SIGNATURE = 0x02014b50;
const LOCAL_SIGNATURE = 0x04034b50;
const EOCD_MIN = 22;
/** the comment field is 16 bits, so the record starts at most this far from the end */
const EOCD_SEARCH = EOCD_MIN + 0xffff;

function read(fd: number, at: number, length: number): Buffer {
  const buffer = Buffer.alloc(length);
  const got = readSync(fd, buffer, 0, length, at);
  if (got !== length) throw new Error(`zip: short read of ${length} bytes at ${at} (got ${got})`);
  return buffer;
}

interface CentralEntry {
  readonly name: string;
  readonly method: number;
  readonly compressedSize: number;
  readonly size: number;
  readonly localOffset: number;
}

function centralDirectory(fd: number, fileSize: number, path: string): Map<string, CentralEntry> {
  const tailLength = Math.min(EOCD_SEARCH, fileSize);
  const tail = read(fd, fileSize - tailLength, tailLength);
  let eocd = -1;
  for (let i = tail.length - EOCD_MIN; i >= 0; i--) {
    if (tail.readUInt32LE(i) === EOCD_SIGNATURE) {
      eocd = i;
      break;
    }
  }
  if (eocd < 0) throw new Error(`zip: ${path} has no end-of-central-directory record`);
  const count = tail.readUInt16LE(eocd + 10);
  const size = tail.readUInt32LE(eocd + 12);
  const offset = tail.readUInt32LE(eocd + 16);
  if (count === 0xffff || size === 0xffffffff || offset === 0xffffffff) {
    throw new Error(`zip: ${path} is zip64 — this reader handles the 32-bit central directory only`);
  }
  const central = read(fd, offset, size);
  const entries = new Map<string, CentralEntry>();
  let at = 0;
  for (let i = 0; i < count; i++) {
    if (central.readUInt32LE(at) !== CENTRAL_SIGNATURE) {
      throw new Error(`zip: ${path} central directory record ${i} has a bad signature`);
    }
    const nameLength = central.readUInt16LE(at + 28);
    const extraLength = central.readUInt16LE(at + 30);
    const commentLength = central.readUInt16LE(at + 32);
    const name = central.toString("utf8", at + 46, at + 46 + nameLength);
    entries.set(name, {
      name,
      method: central.readUInt16LE(at + 10),
      compressedSize: central.readUInt32LE(at + 20),
      size: central.readUInt32LE(at + 24),
      localOffset: central.readUInt32LE(at + 42),
    });
    at += 46 + nameLength + extraLength + commentLength;
  }
  return entries;
}

/** The uncompressed bytes of `name` inside the archive at `path`. Throws if it is not there. */
export function zipEntry(path: string, name: string): Buffer {
  const fd = openSync(path, "r");
  try {
    const entries = centralDirectory(fd, statSync(path).size, path);
    const entry = entries.get(name);
    if (!entry) throw new Error(`zip: ${path} has no entry ${name}`);
    const header = read(fd, entry.localOffset, 30);
    if (header.readUInt32LE(0) !== LOCAL_SIGNATURE) {
      throw new Error(`zip: ${path} entry ${name} has a bad local header`);
    }
    const dataAt = entry.localOffset + 30 + header.readUInt16LE(26) + header.readUInt16LE(28);
    const raw = read(fd, dataAt, entry.compressedSize);
    if (entry.method === 0) return raw;
    if (entry.method === 8) {
      const inflated = inflateRawSync(raw);
      if (inflated.length !== entry.size) {
        throw new Error(`zip: ${path} entry ${name} inflated to ${inflated.length} bytes, the directory says ${entry.size}`);
      }
      return inflated;
    }
    throw new Error(`zip: ${path} entry ${name} uses compression method ${entry.method}`);
  } finally {
    closeSync(fd);
  }
}
