# Spec 0019 实机验收（#190）：托盘 ⇔ 桥互盯生命周期，4+1 场景

日期：2026-09-30 15:45–15:47（本机 Windows + 真手机 adb 94250f9e + 真隧道 cloudflared + 真计划任务）

## 形态

不碰生产实例：从 PR worktree 起**隔离全栈**——独立任务名 `RCU-Spec0019-Accept`（注册形状
与新版 enable-autostart.ps1 一致：AtLogOn、ExecutionTimeLimit Zero、IgnoreNew、**无任务级失败
重启**——重试在 start-bridge.cmd 里），独立端口 18790/18791、独立 state/seq/url 文件、
真 cloudflared 隧道、真 adb 推送到真手机。验收后手机地址已还原生产值并确认 connected。

## 判定（4+1）

| # | 场景 | 判定 | 证据 |
| --- | --- | --- | --- |
| ④ | （登录）任务拉起：图标出现、地址自动推手机、手机连上 | **PASS** | 手机 logcat `bridge status connecting → connected`（15:45:24.632）；桥日志「已自动推送隧道 URL 到手机」 |
| ① | 杀托盘 → 图标 ~10s 回来、手机镜像不断 | **PASS** | 桥日志锚 `07:45:43.807Z 托盘消失（code=1）→ 补拉 1/3，10s 后重拉`；新托盘进程 ~10.9s 出现（10s 定时＋进程创建，spec「间隔约 10 秒」口径）；窗口内无新增断链（唯一 down 在杀前 19s，属隧道冷启动瞬时，且手机在窗口内完成重连 15:45:52.789——服务面无恙） |
| ③ | 杀桥 → 图标 3s 内消失；任务 ~30s 自动重来 | **PASS** | 托盘 1.5s 消失（进程实测）；手机 15:46:09.686 down（kill 即感知）→ 任务重来新桥 → **新地址自动推送**（15:46:39.5 connecting）→ 手机自动重连（15:46:46.139 connected）——US11/US12 全链活证据；重来间隔 ≈30s（logcat 时间差，与 start-bridge.cmd 30s 重试一致） |
| ② | 注入补拉失败 → 桥自关 + 手机尽快「已停用」 | **PASS** | b2 实例 36s 自关（exit 75＝可重来；日志见 04：补拉 3/3 → 监护耗尽 → 清除桥地址广播真发 → 留痕 reason=self-shutdown）；手机 logcat 即时 `bridge stop`（临终通知即达，不等 2–3 分钟超时判停） |
| ⑤ | 全程任意时刻「图标在 ⇔ 桥在」 | **PASS** | 时间线采样审计（05）：最长「桥在无托盘」8s（允许 ≤45s＝补拉窗 3×10s＋余量）、最长「桥无托盘在」0s（允许 ≤3s） |

## 证据文件

- `02-03-staged-bridge-log.log`：④①③ 桥侧日志（补拉锚、监听、推送、留痕）
- `04-selfshutdown-bridge.log`：② 注入补拉失败实例全日志
- `05-invariant-audit.json`：⑤ 时间线采样与判定
- `06-phone-status-timeline.log`：手机侧 bridge 状态全序列（connecting/connected/down/retrying/stop，带时间戳）
- `07-harness-final.log`：PR 分支最终 harness（七项全过）

## 过程说明（如实）

- 首轮驱动把 start-bridge.cmd 的 powershell 包装进程误当「桥」（其命令行同样含 bridge.mjs
  路径），③ 假失败；修正为只认 node.exe 并用进程 CreationDate/日志时间戳计口径后复测通过
  （06 的时间线独立证实了 30s 重来与自动重连，不依赖驱动计时）。
- 杀桥后托盘 1.5s 消失快于 3 拍自退的 ~3s——该拓扑下托盘随桥进程同殁（Windows
  作业/控制台拓扑连坐），无 bridge-gone 留痕；留痕在慢拓扑（手动拉起、启动器中间环节死）
  下由隔离实测另行验证（t1b/t1d，见 #185 证据）。图标消失这一对外行为不受影响。
- Task Scheduler 拒绝 PT30S（实测报「值超出范围」，最小 PT1M）→ 30 秒节奏由
  start-bridge.cmd 重试实现、任务级失败重启关闭（ADR 0011 口径）。
- 右键「退出桥」的真 UI 点击未自动化：代码路径（停任务→杀桥→自退＋manual-exit 留痕）
  已由 #189/#185 落地并经 repo-check/单测，真点击属人工目视项，遗留机主复核。
- 生产任务 RearCueBridge 仍是旧注册参数（RestartCount 5×PT1M）：需机主跑一次
  `enable-autostart.ps1` 重注册才切到新口径（属运维动作，spec 明示不在本票内）。

## 回归

- harness 七项全过（07）；node --test 90/90；repo-check 6 脚本合格
- `./gradlew test --rerun-tasks` BUILD SUCCESSFUL，31 份结果 XML 全 failures=0
