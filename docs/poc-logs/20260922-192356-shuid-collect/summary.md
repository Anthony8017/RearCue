# RearCue PC experiment summary (back-filled at ticket #17 review close-out)

> 本 summary.md 是 #17 code review 收口时**补记**（该轮为 `-RawOnly` fixture 采集轮，当时未跑 05-collect 生成 summary）；
> 归档原始产物一律未改动。约定依据：`tools/ex/README.md` 的 session 目录布局（session.md + summary.md）。

session : docs/poc-logs/20260922-192356-shuid-collect
device  : 25098PN5AC / BP2A.250605.031.A3 (94250f9e)
task    : SH-UID fixture collection（票 #17，非判定轮）

## role

SH-UID 解析器 fixture 的真机采集轮（票 #16 `20260922-155740-wake-collect` 同款角色）：跑完整的
对照 → 加/撤 → 背屏 → register 四段探针并抓原始输出。**不作判定依据**（判定轮 =
`20260922-193237-shuid-overlay/`）。本目录无 `erratum.md`——本轮无勘误可记（findings AC③ 的
「非判定轮各留 erratum」表述已在 #17 review 收口时修正为逐轮如实）。

## fixture 出处（逐字提取，字符未改）

- `tools/ex/tests/fixtures/shuid-probe-run.txt` ← 本轮 `shuid-probe-*.txt` 探针输出
- `tools/ex/tests/fixtures/shuid-probe-registercheck.txt` ← 本轮 register 检查段输出
- `tools/ex/tests/fixtures/logcat-shuid-window-unknown-pid.txt` ← 本轮 `logcat-shuid-*-system.txt`
  的 `Unknown pid` / `attachWindowContextToDisplayArea` 行

## artifacts（原始产物，未改动）

- shuid-build.txt / shuid-overlay.txt / shuid-probe-*.txt（含 registercheck）/ shuid-screen-settings.txt
- dumpsys-window-*.txt（含 registercheck-held/after）/ logcat-shuid-*.txt
- scenario-notes.md / session.md / transcript.txt
