# RearCue PC experiment summary (back-filled at ticket #17 review close-out)

> 本 summary.md 是 #17 code review 收口时**补记**（该轮为 `-RawOnly` bring-up 采集轮，当时未跑 05-collect 生成 summary）；
> 归档原始产物一律未改动。约定依据：`tools/ex/README.md` 的 session 目录布局（session.md + summary.md）。

session : docs/poc-logs/20260922-191525-shuid-collect
device  : 25098PN5AC / BP2A.250605.031.A3 (94250f9e)
task    : SH-UID probe bring-up collection（票 #17，非判定轮）

## role

shell uid（app_process）加窗探针 bring-up 采集第三轮：本轮起 register 检查段（`ActivityThread.attach` →
`attachApplication`）实测到**对未注册进程是 SIGKILL 而非注册**（三段探针全灭于 `probe: context ok` 之后）。
**不作判定依据**（判定轮 = `20260922-193237-shuid-overlay/`）。现象与踩坑见同目录 `scenario-notes.md`、
`erratum.md`，结论口径见 `docs/poc-findings.md`「票 #17 验收」。

## artifacts（原始产物，未改动）

- shuid-build.txt / shuid-overlay.txt / shuid-probe-*.txt / shuid-screen-settings.txt
- dumpsys-window-*.txt / logcat-shuid-*.txt
- scenario-notes.md / erratum.md / session.md / transcript.txt
