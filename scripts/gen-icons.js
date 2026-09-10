/**
 * Lightning Capsule — one-shot extension icon generator.
 *
 * Produces icons/icon{16,32,48,128}.png: a dark (#0f172a) rounded square with a
 * yellow (#fbbf24) lightning bolt — same mark as the PWA. No image deps: it
 * hand-rolls a 24-bit RGB PNG (single IDAT, zlib deflate, CRC32 per chunk).
 *
 * Adapted from ../lightning-capsule/scripts/gen-icons.js.
 * Run: node scripts/gen-icons.js
 */
const zlib = require("zlib");
const fs = require("fs");
const path = require("path");

const CRC_TABLE = (() => {
  const t = new Uint32Array(256);
  for (let n = 0; n < 256; n++) {
    let c = n;
    for (let k = 0; k < 8; k++) c = c & 1 ? 0xedb88320 ^ (c >>> 1) : c >>> 1;
    t[n] = c >>> 0;
  }
  return t;
})();

function crc32(buf) {
  let c = 0xffffffff;
  for (let i = 0; i < buf.length; i++)
    c = CRC_TABLE[(c ^ buf[i]) & 0xff] ^ (c >>> 8);
  return (c ^ 0xffffffff) >>> 0;
}

function chunk(type, data) {
  const len = Buffer.alloc(4);
  len.writeUInt32BE(data.length, 0);
  const typeBuf = Buffer.from(type, "ascii");
  const crc = Buffer.alloc(4);
  crc.writeUInt32BE(crc32(Buffer.concat([typeBuf, data])), 0);
  return Buffer.concat([len, typeBuf, data, crc]);
}

// even-odd point-in-polygon
function inPoly(x, y, pts) {
  let inside = false;
  for (let i = 0, j = pts.length - 1; i < pts.length; j = i++) {
    const xi = pts[i][0],
      yi = pts[i][1];
    const xj = pts[j][0],
      yj = pts[j][1];
    const hit =
      yi > y !== yj > y && x < ((xj - xi) * (y - yi)) / (yj - yi) + xi;
    if (hit) inside = !inside;
  }
  return inside;
}

// lightning bolt polygon in a 0..1 unit square
const BOLT = [
  [0.58, 0.06],
  [0.24, 0.56],
  [0.44, 0.56],
  [0.34, 0.94],
  [0.78, 0.4],
  [0.54, 0.4],
  [0.7, 0.06],
];

const BG = [0x0f, 0x17, 0x2a];
const FG = [0xfb, 0xbf, 0x24];
const CORNER = [0x02, 0x06, 0x0f];

function makePng(size) {
  const radius = size * 0.18;
  const raw = Buffer.alloc(size * (size * 3 + 1));
  let o = 0;
  for (let y = 0; y < size; y++) {
    raw[o++] = 0; // filter: none
    for (let x = 0; x < size; x++) {
      const cx = Math.min(x, size - 1 - x);
      const cy = Math.min(y, size - 1 - y);
      let color = BG;
      const outsideCorner =
        cx < radius &&
        cy < radius &&
        (radius - cx) ** 2 + (radius - cy) ** 2 > radius ** 2;
      if (!outsideCorner) {
        const u = (x + 0.5) / size;
        const v = (y + 0.5) / size;
        if (inPoly(u, v, BOLT)) color = FG;
      } else {
        color = CORNER;
      }
      raw[o++] = color[0];
      raw[o++] = color[1];
      raw[o++] = color[2];
    }
  }

  const sig = Buffer.from([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a]);
  const ihdr = Buffer.alloc(13);
  ihdr.writeUInt32BE(size, 0);
  ihdr.writeUInt32BE(size, 4);
  ihdr[8] = 8; // bit depth
  ihdr[9] = 2; // color type: truecolor RGB
  const idat = zlib.deflateSync(raw, { level: 9 });
  return Buffer.concat([
    sig,
    chunk("IHDR", ihdr),
    chunk("IDAT", idat),
    chunk("IEND", Buffer.alloc(0)),
  ]);
}

const outDir = path.join(__dirname, "..", "icons");
fs.mkdirSync(outDir, { recursive: true });
for (const size of [16, 32, 48, 128]) {
  const file = path.join(outDir, `icon${size}.png`);
  fs.writeFileSync(file, makePng(size));
  console.log("wrote", file, fs.statSync(file).size, "bytes");
}
