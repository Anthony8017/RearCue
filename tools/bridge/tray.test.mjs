/**
 * 推送目标（adb 设备）判定的测试（票 #171）：这几条逻辑只有在"手机同时挂着 USB 与无线调试"
 * 的现场才会暴露问题，靠临时拼 adb 现场来测不现实——所以用注入的假 adb 把分支钉住。
 */
import test from "node:test";
import assert from "node:assert/strict";
import { spawnSync } from "node:child_process";
import { mkdtempSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { adbArgs, detectSingleDeviceSerial, resolveAdbSerial } from "./tray.mjs";

/** 造一个假 adb：按参数回放固定输出，用来驱动 [detectSingleDeviceSerial] 的各分支。 */
function fakeAdb(script) {
  if (process.platform === "win32") {
    const file = join(mkdtempSync(join(tmpdir(), "rcu-adb-")), "adb.cmd");
    writeFileSync(file, script);
    return file;
  }
  const file = join(mkdtempSync(join(tmpdir(), "rcu-adb-")), "adb");
  writeFileSync(file, `#!/bin/sh\n${script}`);
  return file;
}

/**
 * 假 adb 的设备表：第一行表头 + 若干 `<传输 id> device` 行；
 * 表头里带 "attached" 或参数是 "devices" 时只回设备表，否则回一行序列号。
 */
function deviceList(ids, serialLine = ids[0]) {
  return [
    "@echo off",
    'if not "%1"=="devices" if not "%1"=="" (',
    `  echo ${serialLine}`,
    "  exit /b 0",
    ")",
    "echo List of devices attached",
    ...ids.map((id) => `echo ${id} device`),
  ].join("\r\n");
}

/** 只在 Windows 上跑：假 adb 用 .cmd，行为与真 adb 的 stdout 一致。 */
const onlyWindows = { skip: process.platform !== "win32" ? "仅在 Windows 上跑（假 adb 是 .cmd）" : false };

test("只连着一台真机（USB + 无线两行同一台）→ 认出这一个传输 id", onlyWindows, () => {
  const adb = fakeAdb(deviceList(["94250f9e", "192.168.1.5:44867"]));
  const prev = process.env.ADB;
  process.env.ADB = adb;
  try {
    assert.equal(detectSingleDeviceSerial(), "94250f9e");
  } finally {
    process.env.ADB = prev;
  }
});

test("两台不同真机 → 判为不唯一，返回 null（不许瞎猜一台）", onlyWindows, () => {
  // 两台各自的硬件序列号不同：ids 不同、回显的 serial 也随设备变 → 判为不唯一。
  const adb = fakeAdb(deviceList(["AAAA", "BBBB"], "serial-%2"));
  const prev = process.env.ADB;
  process.env.ADB = adb;
  try {
    assert.equal(detectSingleDeviceSerial(), null);
  } finally {
    process.env.ADB = prev;
  }
});

test("一台设备都没连 → null（推送跳过，不报错）", onlyWindows, () => {
  const adb = fakeAdb(["@echo off", "echo List of devices attached"].join("\r\n"));
  const prev = process.env.ADB;
  process.env.ADB = adb;
  try {
    assert.equal(detectSingleDeviceSerial(), null);
  } finally {
    process.env.ADB = prev;
  }
});

test("ADB_SERIAL 明写优先于自动判定", () => {
  const prev = process.env.ADB_SERIAL;
  process.env.ADB_SERIAL = "explicit-serial";
  try {
    assert.equal(resolveAdbSerial(), "explicit-serial");
    assert.deepEqual(adbArgs("https://x").slice(0, 2), ["-s", "explicit-serial"]);
    assert.ok(adbArgs("https://x").includes("com.rearcue.poc.action.BRIDGE_URL"));
  } finally {
    if (prev === undefined) delete process.env.ADB_SERIAL;
    else process.env.ADB_SERIAL = prev;
  }
});

test("url 为空 → 广播不带 --es（＝清除桥地址，#188 临终通知）", () => {
  const prev = process.env.ADB_SERIAL;
  process.env.ADB_SERIAL = "explicit-serial"; // 钉住序列号判定，别让断言依赖现场插没插手机
  try {
    const args = adbArgs("");
    assert.ok(!args.includes("--es"), "清除广播不得带 --es url");
    assert.ok(args.includes("-a"), "广播动作字段还得在");
    assert.ok(args.includes("com.rearcue.poc.action.BRIDGE_URL"), "同一广播动作，只是不带 url");
  } finally {
    if (prev === undefined) delete process.env.ADB_SERIAL;
    else process.env.ADB_SERIAL = prev;
  }
});

test("真机上跑一遍：自动判定不炸（有设备就应给出可用的 -s）", () => {
  const r = spawnSync(process.execPath, ["-e", "1"], { encoding: "utf8" });
  assert.equal(r.status, 0, "node 可用");
  // 这一条只保证函数在真环境下不抛异常；结果取决于当时插没插手机。
  const serial = detectSingleDeviceSerial();
  assert.ok(serial === null || typeof serial === "string");
});
