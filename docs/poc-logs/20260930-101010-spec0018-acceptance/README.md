# 20260930-101010 spec 0018 集成验收（票 #178）

执行：无人值守验收（spec 0018-8）。分支 spec/0018-dsh-approval-alert（#171–#177 全部实现并收口后）。
本机环境：Windows；JDK17（RearCue-tools）；adb（RearCue-tools\android-sdk\platform-tools，序列号 94250f9e）。

## 判定表

| # | 项目 | 结论 | 证据 |
| --- | --- | --- | --- |
| 1 | 全量判例回归（gradle 全模块） | **PASS** | BUILD SUCCESSFUL（109 tasks），gradle_exit=0，见 01-regression.txt |
| 2 | 全量判例回归（tools/bridge node） | **PASS** | 101/101 绿（bridge/tray/make-icons/adapters/turn-log/dsh 全套），见 01-regression.txt |
| 3 | 实机冒烟（调试旁路全链） | **BLOCKED** | 真机掉线：adb devices 初始在线（94250f9e），执行 installDebug 时已离线；4 次恢复尝试（3 次等待＋1 次 adb 服务重启）未回线，见 03-device-smoke.txt。命令清单已备（下「人工项 1」），设备回线即可 5 分钟跑完 |
| 4 | 真 DSH 版本门槛（≥0.2.0-rc.2） | **PASS** | `dsh --version` = 0.2.0-rc.2（引擎达标）；桌面壳 desktopVersion 0.1.7-rc.2 不参与门槛，见 02-dsh-probe.txt |
| 5 | 真 DSH 插件安装＋真会话冒烟 | **需人工**（红线） | 验收红线：不向运行中的 DSH 安装插件/改配置。命令清单见「人工项 2」 |
| 6 | 免解锁批准误触观察 | **需人工** | 需真手指日常携带观察（判定口径见「人工项 3」） |
| 7 | 背屏二次确认浮层实机目检 | **需人工** | 仓内既有验收口径：Rear Tap 需真手指，合成手势不可用（见 2026-09-29 #169 验收记录） |
| 8 | 整夜待机耗电实测 | **需人工** | 需过夜静置对照，口径见「人工项 5」 |

## 人工项清单

### 1. 实机冒烟（设备回线后，约 5 分钟，全程可脚本化）

```powershell
$adb = 'C:\Users\13691\AppData\Local\RearCue-tools\android-sdk\platform-tools\adb.exe'
# 构建安装（JAVA_HOME=RearCue-tools\jdk-17.0.20.1+1）
.\gradlew :app:installDebug
$adb shell am start -n com.rearcue.poc/.ui.MainActivity
$adb shell am broadcast -a com.rearcue.poc.action.AGENT_ENABLED --ez enabled true
# 注入 DSH 伪会话 → 问答流显示（背屏 Agent 页）
$adb shell am broadcast -a com.rearcue.poc.action.AGENT_STATE --es status working --ez connected true --es source dsh --es workspace spec0018 --es reply "验收冒烟输出"
# 等待提醒 → 通知应带同意/拒绝按钮
$adb shell am broadcast -a com.rearcue.poc.action.AGENT_ALERT --es kind waiting --es summary "想修改 xx 文件"
$adb shell dumpsys notification --noredact | findstr /i "rearcue 拒绝"
# 批准 → 期望 logcat 锚 agent action receipt=accepted＋伪会话状态推进
$adb logcat -c
$adb shell am broadcast -a com.rearcue.poc.action.AGENT_APPROVE --es action approve
$adb logcat -d | findstr /i "agent action receipt"
# 选择题点选（需先注入带选项的提问态；optionId 用事件里的 id）
$adb shell am broadcast -a com.rearcue.poc.action.AGENT_APPROVE --es action select --es optionId opt-1
# 失败回执 → 期望 unknown-session
$adb shell am broadcast -a com.rearcue.poc.action.AGENT_APPROVE --es action approve --es sessionId no-such-session
```

判定口径：通知 dump 出现「同意/拒绝」动作；`agent action receipt=accepted|unknown-session` 锚各命中一次；批准后等待标记消失（背屏光带灭、脉冲停）。

### 2. 真 DSH 联调（人工，勿在无人值守下做）

```powershell
# 1) 启动 PC 桥（含 DSH 只读插件）：tools\bridge\start-bridge.cmd（或 start.ps1）
# 2) 在 DSH 安装只读插件（ADR 0010：只订阅事件＋批准类应答）：
& 'C:\Users\13691\AppData\Local\Programs\DeepSeek Harness\resources\runtime\cli\bin\dsh.cmd' plugin --profile web add <本地插件目录或包名>
# 3) 开一个真 DSH 会话跑两步 → 背屏应出现「DSH」来源会话（问答流）
# 4) 让 DSH 触发一次审批请求 → 手机通知出现且可批准 → 批准后会话推进
```

判定口径：会话列表出现来源标记 DSH；等待插队＋脉冲/Approval Glow；批准经插件应答官方 waterfall 后会话推进。若官方 waterfall 应答口形状漂移，改 `tools/bridge/adapters/dsh/dsh-answers.mjs` 纯映射＋补 fixture（#176 判例护住）。

### 3. 免解锁误触观察（人工，24 小时）

日常携带，等确认弹层出现时不做刻意保护地拿放手机；记录误触次数。判定：误触 >0 → 立「批准二次解锁档」票（不回退只读红线）；误触 =0 → 收口。

### 4. 背屏浮层目检（人工，真手指）

注入 waiting 伪会话（命令见 1）→ 投送背屏 → 真手指点按正文弹「同意/拒绝」→ 再点「同意」→ 等待标记消失；重复一次点浮层外 → 取消不生效。注意：合成手势不可用（#169 验收口径），必须真手指。

### 5. 整夜耗电实测（人工，过夜）

对照两晚：A 晚 Agent Mirror 开（桥在线），B 晚关；同起点电量（约 80%）断电静置 8 小时记掉电百分比。判定：A−B ≤ 2 个百分点 → 「增幅不可感」达标。

## 结论

实现面全绿（判例 101 node＋gradle 全模块），版本门槛达标；实机三类人工项待机主执行（其中实机冒烟受设备掉线阻塞，回线即可脚本化跑完）。无实现 bug 暴露；无代码改动，本归档为纯证据提交。
