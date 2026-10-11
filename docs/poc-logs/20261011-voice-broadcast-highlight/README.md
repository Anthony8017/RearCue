# 播报句高亮：合并与装机

日期：2026-10-11（Asia/Shanghai）。

- PR [#339](https://github.com/Anthony8017/RearCue/pull/339) 已合并，主分支提交 `754a84c08a24ef625f035da3d593a73466482646`。
- 从该提交执行全模块测试与 `:app:assembleDebug`：939 项通过，0 失败、0 错误、0 跳过；构建成功。
- APK SHA-256：`FC207E7450601CCC1AF023ABDC450B9C1EB4C60784BC6086B32BD2F905EB57DD`。
- 设备：小米 17 Pro，无线 ADB `192.168.50.2:40745`。`adb install -r -t` 返回 `Success`，`lastUpdateTime=2026-10-11 23:15:58`。
- 设备回读 `base.apk` SHA-256 与构建产物一致：`FC207E7450601CCC1AF023ABDC450B9C1EB4C60784BC6086B32BD2F905EB57DD`。
- 覆盖安装后复核：`POST_NOTIFICATIONS: granted=true`，Shizuku `API_V23: granted=true`，通知监听仍绑定 `RearNotificationListener`；MIUI 10008/10020/10021 与 `SYSTEM_ALERT_WINDOW` 均为 `allow`。

## 背屏冒烟

- Debug fixture 播放两句短文本；日志句序为 `sentence=0/2` → `sentence=1/2`，两次 `voice-follow anchor` 均返回 `visual=true`。
- [第一句](voice-highlight-sentence-1.png) 高亮第一句；[第二句](voice-highlight-sentence-2.png) 高亮第二句，换行续句也覆盖。
- 事件摘录见 [smoke-events.txt](smoke-events.txt)。冒烟后重启手机应用清掉伪会话，通知监听重新绑定成功。

`RearCueBridge` 计划任务全程保持 `Running`，本次没有停止、禁用或重启生产桥。媒体键暂停、人工滚动接管及四来源真实语音未做实机回归。
