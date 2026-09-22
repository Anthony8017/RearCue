# RearCue PC experiment summary (back-filled at ticket #17 review close-out)

> 本 summary.md 是 #17 code review 收口时**补记**（该轮为 `-RawOnly` bring-up 采集轮，当时未跑 05-collect 生成 summary）；
> 归档原始产物一律未改动。约定依据：`tools/ex/README.md` 的 session 目录布局（session.md + summary.md）。

session : docs/poc-logs/20260922-185749-shuid-collect
device  : 25098PN5AC / BP2A.250605.031.A3 (94250f9e)
task    : SH-UID probe bring-up collection（票 #17，非判定轮）

## role

shell uid（app_process）加窗探针的早期 bring-up 采集：构建设备侧探针 dex、以三种窗口胶水策略试加
`TYPE_APPLICATION_OVERLAY`、抓 `dumpsys window windows` 与系统侧 logcat。**不作 E14/SH-UID 判定依据**
（判定轮 = `20260922-193237-shuid-overlay/`）。本轮的现象与踩坑见同目录 `scenario-notes.md`、
`erratum.md`（如有），结论口径见 `docs/poc-findings.md`「票 #17 验收」。

## artifacts（原始产物，未改动）

- shuid-build.txt / shuid-overlay.txt / shuid-probe-*.txt / shuid-screen-settings.txt
- dumpsys-window-*.txt / logcat-shuid-*.txt
- scenario-notes.md / erratum.md / session.md / transcript.txt
