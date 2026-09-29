# 票 #154 实机冒烟证据

- 时间：2026-09-29 12:46（Asia/Shanghai）
- 设备：小米 17 Pro（adb serial `94250f9e`），背屏 logical displayId=1
- 分支：`codex/154-merged-roster`（集成分支 tip `6544220` 切出）
- APK：`:app:assembleDebug`，SHA-256 `CAD071169381F016A900DFCD0D6055F7D26519AD70A38B734276FA5DDDEFBFB8`
- 桥：`powershell -File tools/bridge/start.ps1 -NoTunnel`
- 手机：`adb reverse tcp:18787 tcp:18787`；debug API `http://127.0.0.1:18787`

## 结果

1. `main-list-before-lock.xml` 为主屏 Agent 区 uiautomator dump：列表首行「自动」，其后有
   `packyapi_inspector · b447`、`RearCue` 两条当前 Codex 会话，来源标记均为 `Codex`。
   `RearCue` 行对应本票所在 Codex 会话 `bridge:01a0eb15-0848-7d71-aa03-a2eeacccdde0`。
2. 点击 `RearCue` 行后，`state-after-lock.logcat` 中可见
   `sessionLock=Locked(sessionId=bridge:01a0eb15-0848-7d71-aa03-a2eeacccdde0)`。
3. `bridge-snapshot.json` 保留桥 `/snapshot` 读数，当前会话 `source=codex`、状态 `working`。
4. `rear-locked.png` 是锁定瞬间背屏（仍在通知页）；点背屏空白切到 Agent 页后，
   `rear-locked-agent.png` 显示该锁定 Codex 会话的最新输出（文案含 `#153`）。

## 取证限制

- 背屏截图时手机处于插电状态，截图叠加了充电动画绿色水面与 `100%` 数字；Agent 输出仍可读。
- 本机姿态门默认关闭，未触发姿态门；投送本身按应用内/Shizuku 兜底成功。
- 该目录是 debug adb reverse 冒烟，不替代 #157 的 cloudflared 真隧道验收。