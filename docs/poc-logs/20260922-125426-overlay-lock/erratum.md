# Erratum（事后标注，2026-09-22 票 #11 实现中途补写；非本轮原始输出的回改）

本目录是 `overlay-lock` 场景的首轮观察（脚本修复前）。判定本身成立且被下一轮复现：

- E10 = E10-BLOCKED-BY-E9、E11 = E11-BLOCKED-BY-E9、Activity 对照 = 1.3s 被收走，
  与修复后脚本（下一 session）一致。

本轮产物与修复后脚本的差异（原始输出不动，以修复后 session 为准）：

1. `e10-e11-lock.txt` 的 `# samples` / `# system-side lines` / `# app log` 三段被压成了
   单行（数组字面量里嵌 pipeline 的坑：`@('a', ($x | ForEach-Object ...))` 不展平，
   `[string[]]` 强转把整段 join 成一行）。`e10-samples.txt`（独立产物）不受影响。
   同一坑也在 `e9-overlay.txt`（票 #10 的 session 122608/122827/123134/123244/124030），
   两处脚本都已改为 List.Add 逐行写。
2. E11 文案里 "followed the main screen" 是脚本的机制猜测，与本轮采样矛盾：
   背屏 +5s 离开 ON、+10s 因重投回到 ON、此后在原生持有下一直 ON 到观察结束。
   修复后文案只报事实（何时离开 ON / 是否回 ON / 结束态）。
3. 采样行 `last=` 字段没剥掉 logcat 前缀（pid/tid 是双空格，`\S+ \S+ \d+ \d+ ` 单空格锚
   匹配不上）；修复后锚为 `\s+` 系列。
