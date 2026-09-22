# erratum — 本判定轮作废：脚本工具性误判（票 #17）

本 session（20260922-192941-shuid-overlay）的 `shuid-overlay.txt` 判定为
`SH-UID-RUN-INVALID (external pollution ... 1 fingerprint/power-button hit(s) ...)`，
其下方自动生成的 `# erratum -- external pollution (ticket #17)` 抬头同样声称外部污染。
**两者都是工具性误判，污染并不存在**——本节为勘误，原产物一字不动。

事实：

- 逐条检索本 session 的 `logcat-shuid-*-all.txt`，`android\.policy:FINGERPRINT` /
  `PowerGroup: Powering off display group due to power_button` / `reason=WAKE_REASON_POWER_BUTTON`
  三个污染锚 **0 命中**（本探针全程未按 KEYCODE_POWER，也无人碰手机）。
- 误判来源是票 #16 已记录过的 `,$arr` + `@()` 包装陷阱：`Get-ExWakePollution` 返回
  `,$hits.ToArray()`，`@(...)` 把**空结果**包成「含空数组的单元素数组」，`.Count` 于是读成
  1 个命中，随后 erratum 生成处 `$hit.Raw.Trim()` 对空数组报
  `You cannot call a method on a null-valued expression`（statement-terminating，脚本继续跑完，
  留下这份空抬头的 erratum）。
- 同轮还暴露另一处工具性报错（不影响判定）：`Get-ExRearCueSummary` 在应用日志为空时
  `@(Get-ExLogcat)` 经 if 表达式的管道边界被展平成 $null，触发
  `Get-RearCueEvent : Cannot bind argument to parameter 'Logcat' because it is null`。

修复（修完后重跑判定轮，以其 session 为准）：

1. `tools/ex/10-shuid-overlay.ps1`：`$pollution = Get-ExWakePollution ...` 不再加 `@()`，
   并在代码处写明该陷阱。
2. `tools/ex/ExCommon.psm1`：`Get-ExRearCueSummary` 改经 List 收集，空捕获不再绑定成 null。

本 session 的设备事实产物（探针输出、dumpsys、logcat、build/screen-settings）与修复后的
判定轮同形态、同结论素材：control/rear 的 `add` 均被
`java.lang.IllegalStateException: Unknown pid=<pid> uid=2000` 拒绝（三策略皆然），
register 段进程死于 `probe: register attempt method=attachApplication`——判定语义见
findings「票 #17 验收」。
