// A minimal PNG reader, enough for what the gate needs: the pixel buffer of a Chrome screenshot.
//
// WHY HAND-ROLLED: the gate must decide whether a capture is BLANK, and a capture that failed is a
// gate a reviewer cannot pass silently. That verdict needs pixels, and this repository vendors no
// image library (`npm install` is not available to a builder row). Chrome writes 8-bit
// non-interlaced RGB or RGBA; that is the whole of what this reads, and it throws on anything else
// rather than guessing.

import { inflateSync } from 'node:zlib';

const SIGNATURE = 0x89504e47;

export function decodePng(buffer) {
  if (buffer.readUInt32BE(0) !== SIGNATURE) throw new Error('not a PNG');
  let offset = 8;
  let width = 0;
  let height = 0;
  let bitDepth = 0;
  let colorType = 0;
  const idat = [];

  while (offset + 8 <= buffer.length) {
    const length = buffer.readUInt32BE(offset);
    const type = buffer.toString('ascii', offset + 4, offset + 8);
    const data = buffer.subarray(offset + 8, offset + 8 + length);
    if (type === 'IHDR') {
      width = data.readUInt32BE(0);
      height = data.readUInt32BE(4);
      bitDepth = data[8];
      colorType = data[9];
      if (data[12] !== 0) throw new Error('interlaced PNG is not supported');
    } else if (type === 'IDAT') {
      idat.push(data);
    } else if (type === 'IEND') {
      break;
    }
    offset += 12 + length;
  }

  if (bitDepth !== 8) throw new Error(`bit depth ${bitDepth} is not supported`);
  // 2 = truecolour (RGB), 6 = truecolour with alpha. Chrome writes one of the two.
  const channels = colorType === 2 ? 3 : colorType === 6 ? 4 : 0;
  if (channels === 0) throw new Error(`colour type ${colorType} is not supported`);

  // Bun's own inflate; a Buffer view so the filter loop below indexes it as before.
  const raw = inflateSync(Buffer.concat(idat));
  const stride = width * channels;
  const pixels = Buffer.alloc(height * stride);

  let at = 0;
  for (let y = 0; y < height; y++) {
    const filter = raw[at++];
    const line = raw.subarray(at, at + stride);
    at += stride;
    const out = pixels.subarray(y * stride, (y + 1) * stride);
    const prev = y === 0 ? null : pixels.subarray((y - 1) * stride, y * stride);
    unfilter(filter, line, prev, out, channels);
  }

  return { width, height, channels, pixels };
}

function paeth(a, b, c) {
  const p = a + b - c;
  const pa = Math.abs(p - a);
  const pb = Math.abs(p - b);
  const pc = Math.abs(p - c);
  if (pa <= pb && pa <= pc) return a;
  return pb <= pc ? b : c;
}

function unfilter(filter, line, prev, out, bpp) {
  for (let i = 0; i < line.length; i++) {
    const a = i >= bpp ? out[i - bpp] : 0;
    const b = prev === null ? 0 : prev[i];
    const c = prev !== null && i >= bpp ? prev[i - bpp] : 0;
    let value = line[i];
    if (filter === 1) value += a;
    else if (filter === 2) value += b;
    else if (filter === 3) value += (a + b) >> 1;
    else if (filter === 4) value += paeth(a, b, c);
    else if (filter !== 0) throw new Error(`scanline filter ${filter} is not supported`);
    out[i] = value & 0xff;
  }
}

/**
 * The fraction of sampled pixels within `tolerance` of `[r,g,b]`, on every channel.
 *
 * Sampled on a stride rather than exhaustively: a 1536x1024 frame is 1.5 million pixels and the
 * question is "is this whole frame one colour", which a 1-in-16 sample answers exactly as well as
 * a census and about sixteen times faster.
 */
export function colorFraction(png, rgb, tolerance = 2, step = 4) {
  const { width, height, channels, pixels } = png;
  let inside = 0;
  let total = 0;
  for (let y = 0; y < height; y += step) {
    for (let x = 0; x < width; x += step) {
      const at = (y * width + x) * channels;
      total += 1;
      if (
        Math.abs(pixels[at] - rgb[0]) <= tolerance &&
        Math.abs(pixels[at + 1] - rgb[1]) <= tolerance &&
        Math.abs(pixels[at + 2] - rgb[2]) <= tolerance
      ) {
        inside += 1;
      }
    }
  }
  return total === 0 ? 0 : inside / total;
}

/** `#RRGGBB` as `[r,g,b]`. */
export function hexToRgb(hex) {
  const value = hex.replace('#', '').trim();
  return [
    parseInt(value.slice(0, 2), 16),
    parseInt(value.slice(2, 4), 16),
    parseInt(value.slice(4, 6), 16),
  ];
}
