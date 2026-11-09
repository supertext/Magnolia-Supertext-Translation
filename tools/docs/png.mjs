/**
 * Tells whether two PNG screenshots show the same thing. Chromium's software rendering can differ
 * by a shade or a stray pixel from run to run (anti-aliasing and dithering on buttons), which is no UI change:
 * screenshots.mjs keeps the existing image then, so docs/images/ only changes when the UI does.
 * Decodes 8-bit, non-interlaced greyscale/RGB/RGBA PNGs (what Playwright writes); anything else
 * counts as different.
 */
import { inflateSync } from 'node:zlib';

function decode(buf) {
  if (buf.length < 8 || buf.readUInt32BE(0) !== 0x89504e47) return null;
  let width = 0;
  let height = 0;
  let channels = 0;
  const idat = [];
  for (let i = 8; i + 8 <= buf.length;) {
    const len = buf.readUInt32BE(i);
    const type = buf.toString('latin1', i + 4, i + 8);
    const data = buf.subarray(i + 8, i + 8 + len);
    if (type === 'IHDR') {
      width = data.readUInt32BE(0);
      height = data.readUInt32BE(4);
      const [depth, color, , , interlace] = data.subarray(8, 13);
      channels = { 0: 1, 2: 3, 4: 2, 6: 4 }[color] ?? 0;
      if (depth !== 8 || interlace !== 0 || !channels) return null;
    } else if (type === 'IDAT') {
      idat.push(data);
    } else if (type === 'IEND') {
      break;
    }
    i += 12 + len;
  }
  const raw = inflateSync(Buffer.concat(idat));
  const stride = width * channels;
  const out = Buffer.alloc(stride * height);
  for (let y = 0; y < height; y++) {
    const filter = raw[y * (stride + 1)];
    const line = raw.subarray(y * (stride + 1) + 1, (y + 1) * (stride + 1));
    for (let x = 0; x < stride; x++) {
      const a = x >= channels ? out[y * stride + x - channels] : 0;
      const b = y > 0 ? out[(y - 1) * stride + x] : 0;
      const c = x >= channels && y > 0 ? out[(y - 1) * stride + x - channels] : 0;
      let v = line[x];
      if (filter === 1) v += a;
      else if (filter === 2) v += b;
      else if (filter === 3) v += (a + b) >> 1;
      else if (filter === 4) {
        const p = a + b - c;
        const pa = Math.abs(p - a);
        const pb = Math.abs(p - b);
        const pc = Math.abs(p - c);
        v += pa <= pb && pa <= pc ? a : pb <= pc ? b : c;
      }
      out[y * stride + x] = v & 0xff;
    }
  }
  return { width, height, channels, pixels: out };
}

/**
 * True when both are the same size and no colour channel differs by more than `tolerance`, except
 * for at most `outliers` values (a stray anti-aliased pixel; changing one letter touches far more).
 */
export function samePicture(a, b, tolerance = 2, outliers = 16) {
  if (a.equals(b)) return true;
  let x;
  let y;
  try {
    x = decode(a);
    y = decode(b);
  } catch {
    return false;
  }
  if (!x || !y || x.width !== y.width || x.height !== y.height || x.channels !== y.channels) return false;
  let off = 0;
  for (let i = 0; i < x.pixels.length; i++) {
    if (Math.abs(x.pixels[i] - y.pixels[i]) > tolerance && ++off > outliers) return false;
  }
  return true;
}
