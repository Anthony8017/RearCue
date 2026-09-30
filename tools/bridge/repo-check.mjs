#!/usr/bin/env node
/**
 * 仓库守卫（票 #171）：把两个"改完不跑就看不出来"的坑钉成可跑的检查。
 *
 *  ① `.ps1` 编码：Windows PowerShell 5.1 对**无 BOM** 的文件按 ANSI(GBK) 解码——
 *     含中文的脚本会被解成乱码、引号配对错位，整脚本语法失败。
 *     2026-09-29 实测踩过两次（tray.ps1 一开始就没 BOM；status.ps1 被编辑工具吃掉 BOM），
 *     症状都是"体检/托盘恒报不通"，而且**在 PowerShell 7 上完全看不出来**。
 *     规则：本目录的 .ps1 一律带 UTF-8 BOM，并且必须能被 PowerShell 解析器解析（0 语法错）。
 *
 *  ② `.cmd` 编码：cmd.exe 用 OEM 代码页(GBK) 解码批处理，UTF-8 中文注释会被拆成
 *     乱码命令行，启动器直接失败（2026-09-29 实测："'...' is not recognized"）。
 *     规则：.cmd 一律纯 ASCII。
 *
 * 用法：`node tools/bridge/repo-check.mjs`（失败 exit 1；已并入 README 的测试命令）。
 */
import { execFileSync } from "node:child_process";
import { readFileSync, readdirSync } from "node:fs";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";

const HERE = dirname(fileURLToPath(import.meta.url));
const BOM = [0xef, 0xbb, 0xbf];

const failures = [];
const notes = [];

function hasBom(buf) {
  return buf.length >= 3 && buf[0] === BOM[0] && buf[1] === BOM[1] && buf[2] === BOM[2];
}

function isAscii(buf) {
  return buf.every((b) => b < 0x80);
}

/** 用 PowerShell 自己的解析器验语法（只解析、不执行，任何脚本都能安全验）。 */
function parseErrors(file) {
  const script = [
    "$errors = $null",
    `[void][System.Management.Automation.Language.Parser]::ParseFile('${file}', [ref]$null, [ref]$errors)`,
    "if ($errors) { $errors | ForEach-Object { $_.Message }; exit 1 } else { exit 0 }",
  ].join("; ");
  try {
    execFileSync("powershell.exe", ["-NoProfile", "-NonInteractive", "-Command", script], {
      stdio: "pipe",
    });
    return [];
  } catch (e) {
    const out = `${e.stdout || ""}${e.stderr || ""}`.trim();
    return out ? out.split(/\r?\n/).filter(Boolean) : ["解析失败（无输出）"];
  }
}

const files = readdirSync(HERE).filter((f) => /\.(ps1|cmd)$/i.test(f));
for (const name of files) {
  const file = join(HERE, name);
  const buf = readFileSync(file);
  if (/\.ps1$/i.test(name)) {
    if (!hasBom(buf)) {
      failures.push(`${name}: 缺 UTF-8 BOM（PowerShell 5.1 会按 GBK 解码 → 中文脚本语法失败）`);
      continue;
    }
    const errors = parseErrors(file);
    if (errors.length) failures.push(`${name}: PowerShell 解析报错 → ${errors[0]}`);
    else notes.push(`${name}: BOM ✓ 解析 ✓`);
  } else {
    if (!isAscii(buf)) {
      const bad = buf.findIndex((b) => b >= 0x80);
      failures.push(`${name}: 含非 ASCII 字节（偏移 ${bad}）——cmd.exe 用 GBK 解码会把注释拆成乱码命令`);
      continue;
    }
    notes.push(`${name}: 纯 ASCII ✓`);
  }
}

for (const n of notes) console.log(`[repo-check] ${n}`);
if (failures.length) {
  for (const f of failures) console.error(`[repo-check] ✖ ${f}`);
  console.error(`[repo-check] 结论：${failures.length} 项不合格`);
  process.exit(1);
}
console.log(`[repo-check] 结论：${files.length} 个脚本全部合格`);
