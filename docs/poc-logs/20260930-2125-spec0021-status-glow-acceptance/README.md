# 20260930-2125 spec 0021 状态光带实机验收（票 #209）

执行：ZCode 无人值守（调试旁路注入 + 背屏 SF id `4630946949513469332` screencap）。
构建：main d397091（PR #210 全量）。环境：真机 94250f9e（小米 17 Pro，HyperOS 3.0.319）。

## 判定表

| # | 档/项 | 结论 | 证据（shots/） |
| --- | --- | --- | --- |
| 1 | 工作中＝蓝·缓慢流动 | **PASS** | f01_working_1..4（间隔 2s 四帧，边缘亮段 LEFT→BOTTOM→RIGHT→BOTTOM-LEFT 沿环缓移）；t04（真实会话流式，左缘蓝带） |
| 2 | 等待确认＝琥珀黄·呼吸·全场最亮 | **PASS** | f02_waiting_1..3 全环均匀 (120,91,47)→(233,178,92)→(145,111,57)（呼吸起伏）；f02z 峰值 (243,186,97)，亮度高于其余四档实测 |
| 3 | 空闲＝绿·静止低亮 | **PASS** | f03e_idle 全环均匀 (32,62,44)，静止 |
| 4 | 出错＝红·静止 | **PASS（旁证）／实机带待人工** | 实机带未拍成（见「环境干扰」）；JVM 判例钉死 error 色 ×STILL×0.3（AgentMirrorParamsTest 五档表），渲染层与绿/灰同一 STILL 路径仅色不同——实机正放姿毕后人工复核一眼即收 |
| 5 | 断链＝灰·压过一切 | **PASS** | f05_disc_gray1/2：注入态 error + 假桥地址（RETRYING）→ 全环 (48,49,51) 灰两帧静止＝压过 error 档；t01 桥未配置（DISABLED）无带无点 |
| 6 | 回连恢复真实档 | **PASS** | 假地址→真地址恢复后 poll1 即 `agent link CONNECTED`，蓝档回归（f06_recovered/f04f 后续帧） |
| 7 | 空态不画 | **PASS** | f07_empty（AGENT_ENABLED false）边缘零亮度 |
| 8 | 通知页不画 | **PASS** | 通知页帧边缘零亮度（idle 自动收屏后落在通知页的对照帧） |
| 9 | 充电同屏照画 | **PASS** | t04：绿水位（真充电）＋蓝带同帧共存，边缘细环与整屏水可辨 |
| 10 | 标识行状态点/3s 脉冲不动 | **PASS** | PR #210 diff 零触及（code-review 两轴确认）；t01 蓝点在位 |
| 11 | 流速体感/夜间亮度/耗电 | **需人工** | 常规携带观察 |

## 环境干扰记录（复跑必读）

1. **重装后配对丢失 + 自愈**：卸旧包（签名不一致）→ v4 鉴权 NoAnswer 循环（#202 已知）；
   本次 BRIDGE_URL 调试口重配真隧道后重连循环**数分钟内自行 CONNECTED**——#202 补数据点。
2. **MIUI USB 安装门**：`INSTALL_FAILED_USER_RESTRICTED`，开发者选项「USB 安装」需机主开。
3. **背屏被相机顶占**：背屏 input BACK/tap 可唤出相机外屏取景，`am force-stop com.android.camera` 恢复。
4. **正放姿态门翻脸**：手机正放时传感器覆盖一切姿态注入（含 Quick Tile 豁免在 app 进程重启后失效），
   背屏反复转黑——红档实机带即败于此。复跑红档：手机倒扣＋tile 重投送＋error 注入即可。
5. **调试注入语义**：`AGENT_STATE --ez connected` 走 core 布尔，不影响 `AgentFeed.link`（光带档位输入）；
   档位链路态须走 `BRIDGE_URL`（真/假地址切换 CONNECTED/RETRYING）。
6. **锁 debug 会话会被 roster 合并清除**（「锁定会话不在册即清锁」设计），注入态显示靠 WFA 插队顶位。
7. screencap -d SF id 出 raw RGBA（16B 头），PNG 转换脚本见本目录各次内联记录；`screencap -p` 在多屏机 stdout 混警告文本不可用。

## 收尾状态

SESSION_LOCK 已回 auto；桥地址＝真隧道（CONNECTED）；充电软件模拟已 `cmd battery reset`。
遗留人工项：④实机红带复核（倒扣手机）、⑪体感三项。
