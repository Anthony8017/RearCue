# 人工拍照点（背屏视觉验收）

小米 17 Pro 的背屏在本机**无法程序化截图**（`screencap -d 1` 报 `Display Id '1' is not valid`），
所以背屏的视觉证据只能靠人眼/拍照。`tools/ex` 的驱动脚本会在走到每个拍点时把时刻、当时该看到什么、
以及同刻的自动证据（dumpsys 归属 + 应用日志）记进对应 session 的 `photo-checkpoints.md`，
照片按下面的文件名放进本目录即可。

| # | 拍点 | 该拍什么 | 文件名 |
|---|---|---|---|
| ① | Dashboard 首次上屏 | 纯黑底 + 大号时间 + Allowlist 应用图标（票 #7 复跑时是 Shell 与 RearCue 两枚） | `photo-01-first-launch.jpg` |
| ② | 锁屏 30s/5min 之后 | 主屏已灭时背屏的画面 | `photo-02-locked-30s.jpg` |
| ③ | AOD 抢回瞬间 | 背屏从 Dashboard 切回小米原生息屏界面的那一刻 | `photo-03-aod-takeover.jpg` |

票 #7（2026-09-22）的实际情况：三个拍点都走到了，但照片未留档；用户人眼验收的结论是
「锁屏后是小米原生背屏」——与自动证据一致（锁屏后 1–5 秒 Dashboard 被系统结束，见
`docs/poc-findings.md` 票 #7 与 `poc-logs/20260922-113847-probe-visible/`）。
后补照片时沿用同一命名即可，findings 的引用不需要改。
