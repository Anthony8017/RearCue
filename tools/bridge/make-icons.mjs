#!/usr/bin/env node
/**
 * 图标资产生成器（票 #171）：一次画出「拱桥极简」标记，产出两处要用的文件——
 *   ① Windows 托盘 .ico（多尺寸：16 / 20 / 24 / 32 / 48，三态换色 + 浅色任务栏版）
 *   ② Android 启动图标（自适应图标：前景矢量 + 背景色 + 各密度回退 PNG）
 *
 * 为什么把图形写成代码而不是拷一张位图进仓：托盘图标要按 DPI 出多尺寸、要多态换色、
 * 还要和手机端同一枚标记，手绘一套位图必然走形（ADR 0006 补记）。
 *
 * 图形（归一化到 32×32 画布，与矢量路径同一组坐标，改一处两处同步）：
 *   拱   外半径 11.5 / 内半径 7.5，圆心 (16, 24.5)，半圆朝上
 *   桥面 中点 (16, 24.5)、半长 10.5、半厚 1.5 的胶囊（半圆端帽）
 *
 * 用法：
 *   node tools/bridge/make-icons.mjs              # 产出托盘 .ico（临时目录，供桥加载）
 *   node tools/bridge/make-icons.mjs --android    # 另写 app/src/main/res 的启动图标资产
 *   node tools/bridge/make-icons.mjs --check      # 只自检几何/编码，不写文件（测试用）
 */
import { mkdirSync, writeFileSync } from "node:fs";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";
import { deflateSync } from "node:zlib";

const HERE = dirname(fileURLToPath(import.meta.url));
const REPO = join(HERE, "..", "..");
const ANDROID_RES = join(REPO, "app", "src", "main", "res");

/** 图形几何：归一化 32×32 画布（ARCH 两个半径 / DECK 三段半长），与 Android 矢量路径共用。 */
export const MARK = {
  size: 32,
  archCx: 16,
  archCy: 24.5,
  archOuter: 11.5,
  archInner: 7.5,
  deckHalfLen: 10.5,
  deckHalfThick: 1.5,
};
/** 三态与主题的配色（ADR 0006 补记：就绪＝薄荷绿、隧道未就绪＝琥珀黄；手机已连＝晴空蓝）。 */
export const COLORS = {
  ready: [0x6e, 0xe7, 0xa8],
  phone: [0x6e, 0xb5, 0xff],
  pending: [0xf0, 0xb3, 0x57],
  light: [0x1a, 0x1a, 0x1a], // 浅色任务栏：白/绿都看不清，用近黑
  iconBg: [0x10, 0x18, 0x28], // 应用图标底：深蓝黑
};
/** 托盘 .ico 内嵌尺寸（Windows 按 DPI 选 16/20/24，32/48 供放大与高 DPI）。 */
export const ICO_SIZES = [16, 20, 24, 32, 48];

const SS = 4; // 每像素 4×4 超采样：抗锯齿靠覆盖率，不靠描边

function distToSegment(px, py, ax, ay, bx, by) {
  const dx = bx - ax;
  const dy = by - ay;
  const len2 = dx * dx + dy * dy;
  let t = len2 === 0 ? 0 : ((px - ax) * dx + (py - ay) * dy) / len2;
  t = Math.max(0, Math.min(1, t));
  const cx = ax + t * dx;
  const cy = ay + t * dy;
  return Math.hypot(px - cx, py - cy);
}

/** 归一化坐标处的覆盖判定：在拱环上或在桥面胶囊里。 */
function inMark(x, y, m = MARK) {
  const dArch = Math.hypot(x - m.archCx, y - m.archCy);
  if (dArch <= m.archOuter && dArch >= m.archInner && y <= m.archCy) return true;
  return (
    distToSegment(x, y, m.archCx - m.deckHalfLen, m.archCy, m.archCx + m.deckHalfLen, m.archCy) <=
    m.deckHalfThick
  );
}

/** 把一个尺寸的标记画成 RGBA 像素（返回每行 top-down 的 [r,g,b,a] 数组）。 */
export function renderMark(size, rgb, m = MARK) {
  const rows = [];
  for (let py = 0; py < size; py++) {
    const row = [];
    for (let px = 0; px < size; px++) {
      let hit = 0;
      for (let sy = 0; sy < SS; sy++) {
        for (let sx = 0; sx < SS; sx++) {
          const x = ((px + (sx + 0.5) / SS) * m.size) / size;
          const y = ((py + (sy + 0.5) / SS) * m.size) / size;
          if (inMark(x, y, m)) hit++;
        }
      }
      const a = Math.round((hit / (SS * SS)) * 255);
      row.push([rgb[0], rgb[1], rgb[2], a]);
    }
    rows.push(row);
  }
  return rows;
}

/** 32 位 BMP 位（BITMAPINFOHEADER + bottom-up BGRA + AND 掩码）——ICO 内嵌格式。 */
function toDib(rows) {
  const h = rows.length;
  const w = rows[0].length;
  const header = Buffer.alloc(40);
  header.writeUInt32LE(40, 0);
  header.writeInt32LE(w, 4);
  header.writeInt32LE(h * 2, 8); // XOR + AND 两层，高度写两倍
  header.writeUInt16LE(1, 12);
  header.writeUInt16LE(32, 14);
  header.writeUInt32LE(0, 16); // BI_RGB
  header.writeUInt32LE(w * h * 4, 20);
  const pixels = Buffer.alloc(w * h * 4);
  for (let y = 0; y < h; y++) {
    const src = rows[h - 1 - y]; // BMP 自下而上
    for (let x = 0; x < w; x++) {
      const [r, g, b, a] = src[x];
      const o = (y * w + x) * 4;
      pixels[o] = b;
      pixels[o + 1] = g;
      pixels[o + 2] = r;
      pixels[o + 3] = a;
    }
  }
  const rowBytes = Math.ceil(w / 8);
  const rowSize = Math.ceil(rowBytes / 4) * 4; // AND 掩码每行 4 字节对齐
  const mask = Buffer.alloc(rowSize * h, 0);
  for (let y = 0; y < h; y++) {
    const src = rows[h - 1 - y];
    for (let x = 0; x < w; x++) {
      if (src[x][3] === 0) mask[y * rowSize + (x >> 3)] |= 0x80 >> (x & 7);
    }
  }
  return Buffer.concat([header, pixels, mask]);
}

/**
 * 多尺寸 ICO：一个文件里塞 16/20/24/32/48，Windows 按当前 DPI 自己挑。
 * 尺寸字段 0 表示 256（本函数不产 256），故按实际值写。
 */
export function buildIco(sizes, rgb) {
  const images = sizes.map((s) => ({ size: s, dib: toDib(renderMark(s, rgb)) }));
  const header = Buffer.alloc(6);
  header.writeUInt16LE(0, 0);
  header.writeUInt16LE(1, 2); // 1 = ICO
  header.writeUInt16LE(images.length, 4);
  const entries = Buffer.alloc(16 * images.length);
  let offset = 6 + 16 * images.length;
  images.forEach((img, i) => {
    const o = i * 16;
    entries.writeUInt8(img.size >= 256 ? 0 : img.size, o);
    entries.writeUInt8(img.size >= 256 ? 0 : img.size, o + 1);
    entries.writeUInt8(0, o + 2); // 调色板色数：真彩写 0
    entries.writeUInt8(0, o + 3);
    entries.writeUInt16LE(1, o + 4); // 色彩平面
    entries.writeUInt16LE(32, o + 6); // 位深
    entries.writeUInt32LE(img.dib.length, o + 8);
    entries.writeUInt32LE(offset, o + 12);
    offset += img.dib.length;
  });
  return Buffer.concat([header, entries, ...images.map((i) => i.dib)]);
}

const CRC_TABLE = (() => {
  const t = new Int32Array(256);
  for (let n = 0; n < 256; n++) {
    let c = n;
    for (let k = 0; k < 8; k++) c = c & 1 ? 0xedb88320 ^ (c >>> 1) : c >>> 1;
    t[n] = c;
  }
  return t;
})();

function crc32(buf) {
  let c = -1;
  for (const b of buf) c = CRC_TABLE[(c ^ b) & 0xff] ^ (c >>> 8);
  return (c ^ -1) >>> 0;
}

function pngChunk(type, data) {
  const len = Buffer.alloc(4);
  len.writeUInt32BE(data.length, 0);
  const body = Buffer.concat([Buffer.from(type, "ascii"), data]);
  const crc = Buffer.alloc(4);
  crc.writeUInt32BE(crc32(body), 0);
  return Buffer.concat([len, body, crc]);
}

/** 最小 PNG 编码器（RGBA 真彩 + 过滤器 0）：零依赖，够画一枚图标。 */
export function buildPng(rows) {
  const h = rows.length;
  const w = rows[0].length;
  const raw = Buffer.alloc(h * (1 + w * 4));
  for (let y = 0; y < h; y++) {
    const o = y * (1 + w * 4);
    raw[o] = 0;
    for (let x = 0; x < w; x++) {
      const [r, g, b, a] = rows[y][x];
      const p = o + 1 + x * 4;
      raw[p] = r;
      raw[p + 1] = g;
      raw[p + 2] = b;
      raw[p + 3] = a;
    }
  }
  const ihdr = Buffer.alloc(13);
  ihdr.writeUInt32BE(w, 0);
  ihdr.writeUInt32BE(h, 4);
  ihdr[8] = 8; // 位深
  ihdr[9] = 6; // RGBA
  return Buffer.concat([
    Buffer.from([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a]),
    pngChunk("IHDR", ihdr),
    pngChunk("IDAT", deflateSync(raw, { level: 9 })),
    pngChunk("IEND", Buffer.alloc(0)),
  ]);
}

/** 托盘图标目录（临时目录，桥进程自己写自己加载；仓里不放资产文件）。 */
export function trayIconDir() {
  return process.env.RCU_TRAY_ICON_DIR || join(process.env.TEMP || "/tmp", "rearcue-bridge");
}

/**
 * 写托盘图标（默认位置：临时目录）：手机已连/就绪未连/隧道未就绪三态 + 浅色任务栏的深色版。
 * 返回四个 .ico 的绝对路径。
 */
export function writeTrayIcons(dir = trayIconDir()) {
  mkdirSync(dir, { recursive: true });
  const files = {
    phone: join(dir, "phone.ico"),
    ready: join(dir, "ready.ico"),
    pending: join(dir, "pending.ico"),
    light: join(dir, "light.ico"),
  };
  writeFileSync(files.phone, buildIco(ICO_SIZES, COLORS.phone));
  writeFileSync(files.ready, buildIco(ICO_SIZES, COLORS.ready));
  writeFileSync(files.pending, buildIco(ICO_SIZES, COLORS.pending));
  writeFileSync(files.light, buildIco(ICO_SIZES, COLORS.light));
  return files;
}

/**
 * 应用图标（旧版回退位图，API < 26）：圆角方底 + 居中标记。
 *
 * 为什么单独算偏移而不是直接居中缩放：标记的视觉重心偏下（拱在桥面之上、图形底部就是桥面），
 * 直接按外框居中会让它看起来"沉在底下"。这里按标记**实际像素的包围盒**居中，
 * 并留 6% 边距——2026-09-29 目检：原实现（标记贴底、占 60%）看上去像压在图标下沿。
 */
function appIconRows(canvas, markRatio, bg, fg) {
  const markSize = Math.round(canvas * markRatio);
  const mark = renderMark(markSize, fg);
  let minX = markSize;
  let maxX = -1;
  let minY = markSize;
  let maxY = -1;
  for (let y = 0; y < markSize; y++) {
    for (let x = 0; x < markSize; x++) {
      if (mark[y][x][3] <= 8) continue;
      if (x < minX) minX = x;
      if (x > maxX) maxX = x;
      if (y < minY) minY = y;
      if (y > maxY) maxY = y;
    }
  }
  const offX = Math.round((canvas - (maxX - minX + 1)) / 2) - minX;
  const offY = Math.round((canvas - (maxY - minY + 1)) / 2) - minY;
  const rows = [];
  for (let y = 0; y < canvas; y++) {
    const row = [];
    for (let x = 0; x < canvas; x++) row.push([...bg, 255]);
    rows.push(row);
  }
  for (let y = 0; y < markSize; y++) {
    for (let x = 0; x < markSize; x++) {
      const [r, g, b, a] = mark[y][x];
      if (a === 0) continue;
      const dy = offY + y;
      const dx = offX + x;
      if (dy < 0 || dy >= canvas || dx < 0 || dx >= canvas) continue;
      rows[dy][dx] = [r, g, b, 255];
    }
  }
  return rows;
}

/** Android 启动图标：前景矢量 + 背景色 + 各密度回退 PNG（自适应图标 108dp 画布）。 */
export function writeAndroidIcons() {
  const res = ANDROID_RES;
  mkdirSync(join(res, "drawable"), { recursive: true });
  mkdirSync(join(res, "values"), { recursive: true });
  mkdirSync(join(res, "mipmap-anydpi-v26"), { recursive: true });
  const m = MARK;
  // 矢量路径：M 8.5 24.5 A 7.5 7.5 0 0 1 23.5 24.5（内弧）再 A 11.5 ... 反向（外弧），Z 闭合；
  // 桥面用等粗圆头 stroke。与位图光栅器同一组几何（改这里就同时改了托盘）。
  const foreground = `<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="108dp" android:height="108dp"
    android:viewportWidth="32" android:viewportHeight="32">
    <path
        android:pathData="M8.5,24.5 A7.5,7.5 0 0 1 23.5,24.5 L25.5,24.5 A9.5,9.5 0 0 0 6.5,24.5 Z"
        android:fillColor="#6EE7A8" />
    <path
        android:pathData="M${m.archCx - m.deckHalfLen},${m.archCy} L${m.archCx + m.deckHalfLen},${m.archCy}"
        android:strokeColor="#6EE7A8"
        android:strokeWidth="${m.deckHalfThick * 2}"
        android:strokeLineCap="round" />
</vector>
`;
  writeFileSync(join(res, "drawable", "ic_launcher_foreground.xml"), foreground);
  writeFileSync(
    join(res, "values", "ic_launcher_background.xml"),
    `<?xml version="1.0" encoding="utf-8"?>
<resources>
    <color name="ic_launcher_background">#101828</color>
</resources>
`,
  );
  writeFileSync(
    join(res, "mipmap-anydpi-v26", "ic_launcher.xml"),
    `<?xml version="1.0" encoding="utf-8"?>
<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">
    <background android:drawable="@color/ic_launcher_background" />
    <foreground android:drawable="@drawable/ic_launcher_foreground" />
</adaptive-icon>
`,
  );
  writeFileSync(
    join(res, "mipmap-anydpi-v26", "ic_launcher_round.xml"),
    `<?xml version="1.0" encoding="utf-8"?>
<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">
    <background android:drawable="@color/ic_launcher_background" />
    <foreground android:drawable="@drawable/ic_launcher_foreground" />
</adaptive-icon>
`,
  );
  // 回退位图（API < 26）：圆角方底，标记按包围盒居中、占 88%（6% 边距）。
  const densities = { mdpi: 108, hdpi: 162, xhdpi: 216, xxhdpi: 324, xxxhdpi: 432 };
  const written = [];
  for (const [name, px] of Object.entries(densities)) {
    const dir = join(res, `mipmap-${name}`);
    mkdirSync(dir, { recursive: true });
    const png = buildPng(appIconRows(px, 0.88, COLORS.iconBg, COLORS.ready));
    const file = join(dir, "ic_launcher.png");
    writeFileSync(file, png);
    written.push(file);
  }
  return {
    written,
    files: [
      join(res, "drawable", "ic_launcher_foreground.xml"),
      join(res, "values", "ic_launcher_background.xml"),
      join(res, "mipmap-anydpi-v26", "ic_launcher.xml"),
      join(res, "mipmap-anydpi-v26", "ic_launcher_round.xml"),
      ...written,
    ],
  };
}

/** 自检：几何覆盖与两种编码的字节结构（`node --test` 里调）。 */
export function selfCheck() {
  const mark = renderMark(32, COLORS.ready);
  const opaque = mark.flat().filter((p) => p[3] > 240).length;
  const transparent = mark.flat().filter((p) => p[3] === 0).length;
  const ico = buildIco(ICO_SIZES, COLORS.ready);
  const count = ico.readUInt16LE(4);
  let ok = count === ICO_SIZES.length;
  for (let i = 0; i < count; i++) {
    const o = 6 + i * 16;
    const w = ico.readUInt8(o);
    const len = ico.readUInt32LE(o + 8);
    if (w !== ICO_SIZES[i]) ok = false;
    if (len !== 40 + w * w * 4 + Math.ceil(Math.ceil(w / 8) / 4) * 4 * w) ok = false;
  }
  const png = buildPng(renderMark(16, COLORS.ready));
  return {
    ok,
    opaquePixels: opaque,
    transparentPixels: transparent,
    icoBytes: ico.length,
    pngMagic: png.subarray(0, 8).toString("hex"),
    pngBytes: png.length,
    glyph: renderMark(24, COLORS.ready)
      .map((row) => row.map((p) => (p[3] > 128 ? "#" : ".")).join(""))
      .join("\n"),
  };
}

const isMain = process.argv[1] && fileURLToPath(import.meta.url) === process.argv[1];
if (isMain) {
  if (process.argv.includes("--check")) {
    const r = selfCheck();
    console.log(r.glyph);
    console.log(
      `自检 ok=${r.ok} 不透明像素=${r.opaquePixels} 透明像素=${r.transparentPixels} ` +
        `ico=${r.icoBytes}B png=${r.pngBytes}B magic=${r.pngMagic}`,
    );
    process.exit(r.ok ? 0 : 1);
  }
  const files = writeTrayIcons();
  console.log(`托盘图标已写入 ${dirname(files.ready)}：phone/ready/pending/light .ico（尺寸 ${ICO_SIZES.join("/")}）`);
  if (process.argv.includes("--android")) {
    const a = writeAndroidIcons();
    console.log(`Android 启动图标已写入 ${a.files.length} 个文件（自适应图标 + 5 档密度回退位图）`);
  }
}
