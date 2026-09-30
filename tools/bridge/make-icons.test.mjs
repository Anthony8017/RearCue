/**
 * 图标生成器测试（票 #171）：标记几何与两种编码的字节结构——托盘图标看不见摸不着，
 * 只能靠断言把"形状对不对、尺寸全不全"钉住（改几何时这条会先红）。
 */
import test from "node:test";
import assert from "node:assert/strict";
import { existsSync, mkdtempSync, readFileSync, rmSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { COLORS, ICO_SIZES, MARK, buildIco, buildPng, renderMark, writeTrayIcons } from "./make-icons.mjs";

/** 取一行里不透明像素的起止列（判断某一行画了什么）。 */
function spanOf(row) {
  const cols = row.map((p, i) => (p[3] > 128 ? i : -1)).filter((i) => i >= 0);
  return cols.length ? [cols[0], cols[cols.length - 1]] : null;
}

test("标记几何：拱在桥面之上，两个端点落在桥面两端", () => {
  const size = 32;
  const rows = renderMark(size, COLORS.mint);
  const deckRow = Math.round((MARK.archCy / MARK.size) * size);
  const archTopRow = Math.round(((MARK.archCy - MARK.archOuter + 1) / MARK.size) * size);

  // 拱顶那一行只在中间一小段有像素（拱顶窄），桥面那一行横跨整幅（桥面宽）。
  const archSpan = spanOf(rows[archTopRow]);
  const deckSpan = spanOf(rows[deckRow]);
  assert.ok(archSpan, "拱顶行应有像素");
  assert.ok(deckSpan, "桥面行应有像素");
  assert.ok(
    archSpan[1] - archSpan[0] < deckSpan[1] - deckSpan[0],
    `拱顶跨度（${archSpan}）应窄于桥面跨度（${deckSpan}）`,
  );
  // 桥面跨度 = 2×半长（含半圆端帽）：端帽不许被画布裁掉，也不许多出尾巴。
  const expectedHalf = MARK.deckHalfLen / MARK.size * size;
  const left = size / 2 - expectedHalf;
  const right = size / 2 + expectedHalf;
  assert.ok(deckSpan[0] >= Math.floor(left) - 1 && deckSpan[0] <= Math.ceil(left), `左端 ${deckSpan[0]} 应贴 5.5`);
  assert.ok(deckSpan[1] <= Math.ceil(right) && deckSpan[1] >= Math.floor(right) - 1, `右端 ${deckSpan[1]} 应贴 26.5`);
  assert.ok(deckSpan[0] > 0 && deckSpan[1] < size - 1, "桥面两端不贴画布边（留白）");
});

test("标记几何：拱内部是空的（拱环中空，不是实心半圆）", () => {
  const rows = renderMark(32, COLORS.mint);
  // 取样点要在拱的**内空腔**里：既不在拱环上、也在桥面之上。
  // y=20 → 距圆心 4.5（拱内半径 7.5，故不在环上；桥面半厚 1.5，故不在桥面上）。
  const hollowRow = 20;
  const center = rows[hollowRow][16];
  assert.equal(center[3], 0, `拱内空腔应变透明，实测 alpha=${center[3]}`);
  // 同一行的拱环两侧必须有像素（列 8 与 23 是环带的实心处；列 9/22 是环带边缘的半覆盖）。
  for (const x of [8, 23]) {
    assert.ok(rows[hollowRow][x][3] > 200, `x=${x} 的拱环应实心，实测 alpha=${rows[hollowRow][x][3]}`);
  }
  assert.ok(rows[hollowRow][9][3] > 0 && rows[hollowRow][9][3] < 255, "环带边缘应是部分覆盖（抗锯齿）");
});

test("标记配色：色值按传入的 RGB 上色，覆盖率抗锯齿落在 0/255 之间", () => {
  const rows = renderMark(48, COLORS.pending);
  const flat = rows.flat();
  const opaque = flat.filter((p) => p[3] > 128);
  assert.ok(opaque.length > 0, "应有不透明像素");
  assert.deepEqual([opaque[0][0], opaque[0][1], opaque[0][2]], COLORS.pending);
  // 边缘像素必然是"部分覆盖"（抗锯齿），否则就是硬边（超采样没起作用）。
  assert.ok(
    flat.some((p) => p[3] > 0 && p[3] < 255),
    "应有部分覆盖的边缘像素（抗锯齿）",
  );
});

test("ICO 编码：目录项尺寸/位深/长度自洽（Windows 按尺寸挑，错一个就整枚不显示）", () => {
  const ico = buildIco(ICO_SIZES, COLORS.mint);
  assert.equal(ico.readUInt16LE(0), 0, "保留字段");
  assert.equal(ico.readUInt16LE(2), 1, "类型 1 = ICO");
  assert.equal(ico.readUInt16LE(4), ICO_SIZES.length, "图像数");
  ICO_SIZES.forEach((size, i) => {
    const o = 6 + i * 16;
    assert.equal(ico.readUInt8(o), size, `第 ${i} 项宽`);
    assert.equal(ico.readUInt8(o + 1), size, `第 ${i} 项高`);
    assert.equal(ico.readUInt16LE(o + 6), 32, `第 ${i} 项位深`);
    // 单幅 = 40 字节头 + BGRA 像素 + AND 掩码（每行 4 字节对齐）
    const maskRow = Math.ceil(Math.ceil(size / 8) / 4) * 4;
    assert.equal(ico.readUInt32LE(o + 8), 40 + size * size * 4 + maskRow * size, `第 ${i} 项字节数`);
  });
  // 目录偏移必须首尾相接，最后一幅不越界。
  let last = null;
  ICO_SIZES.forEach((_, i) => {
    const o = 6 + i * 16;
    const offset = ico.readUInt32LE(o + 12);
    const length = ico.readUInt32LE(o + 8);
    if (i === 0) assert.equal(offset, 6 + 16 * ICO_SIZES.length, "首幅紧跟目录");
    if (last != null) assert.equal(offset, last, `第 ${i} 幅应紧接上一幅`);
    last = offset + length;
  });
  assert.equal(last, ico.length, "总长度 = 最后一幅的结束位置");
});

test("托盘三态图标：writeTrayIcons 产出四枚，对调后 phone＝mint、ready＝sky", () => {
  const dir = mkdtempSync(join(tmpdir(), "rearcue-icons-"));
  try {
    const files = writeTrayIcons(dir);
    assert.deepEqual(Object.keys(files).sort(), ["light", "pending", "phone", "ready"], "四枚一个不多一个不少");
    for (const f of Object.values(files)) {
      assert.ok(existsSync(f), `${f} 应已写出`);
      const ico = readFileSync(f);
      assert.equal(ico.readUInt16LE(2), 1, "ICO 类型");
      assert.equal(ico.readUInt16LE(4), ICO_SIZES.length, "多尺寸目录条目数");
    }
    // 状态→色映射（2026-09-30 机主定夺对调）：手机已连＝薄荷绿、就绪未连＝晴空蓝；
    // 几何与任意色完全同源，只换色。
    assert.deepEqual(readFileSync(files.phone), buildIco(ICO_SIZES, COLORS.mint));
    assert.deepEqual(readFileSync(files.ready), buildIco(ICO_SIZES, COLORS.sky));
  } finally {
    rmSync(dir, { recursive: true, force: true });
  }
});

test("PNG 编码：签名/IHDR/尺寸正确（Android 回退位图靠它）", () => {
  const png = buildPng(renderMark(24, COLORS.mint));
  assert.equal(png.subarray(0, 8).toString("hex"), "89504e470d0a1a0a", "PNG 签名");
  assert.equal(png.readUInt32BE(8), 13, "第一个 chunk 长度 = IHDR");
  assert.equal(png.subarray(12, 16).toString("ascii"), "IHDR");
  assert.equal(png.readUInt32BE(16), 24, "宽");
  assert.equal(png.readUInt32BE(20), 24, "高");
  assert.equal(png.readUInt8(24), 8, "位深");
  assert.equal(png.readUInt8(25), 6, "色彩类型 RGBA");
  assert.equal(png.subarray(png.length - 8, png.length - 4).toString("ascii"), "IEND");
});
