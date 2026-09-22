# Erratum（事后标注，2026-09-22 票 #11 实现中途补写；非本轮原始输出的回改）

本轮是脚本修复后的首轮（对比 session 125426 的 erratum：采样段单行化、`last=` 前缀
已修好，本轮产物格式正确）。判定与首轮一致：

- E10 = E10-BLOCKED-BY-E9、E11 = E11-BLOCKED-BY-E9、Activity 对照 = 1.4s 被收走。

本轮与修复后脚本的唯一差异：

1. `e10-e11-lock.txt` 的 e11 文案尾部出现 "ending System.Object[]"——`Get-ExLockSampleFacts`
   的 `Samples` 属性多包了一层数组（`,$samples.ToArray()`），`Samples[-1]` 取到整个数组、
   成员访问被枚举展开。解析事实（first non-ON +5s、never ON again）不受影响，
   只是结束态字段没渲染出来。已改为扁平数组 + 回归测试锁死；修复后**没有再跑真机**，
   本目录就是主证据——e11 verdict 行尾的渲染缺陷以本标注为准，结束态以 `e10-samples.txt`
   原始行为准（+92s `DOZE_SUSPEND/DOZE_SUSPEND`）。
