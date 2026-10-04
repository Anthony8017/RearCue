# 23. Voice Broadcast Follow 与播报回屏

日期：2026-10-04
状态：已接受（机主定夺）

## 背景

Voice Broadcast 会朗读 Agent 任务完成或失败后的回复，但机主可能看不到背屏当前显示的对应内容。
机主决定让语音与 Rear Display 的 Agent Mirror 同步，并接受为此暂时改变当前 Content Page、
甚至把 Dashboard 从 Native Rear Screen 手中带回；这与原先“自动切页只有三类例外”的边界不同，
必须明确优先级、用户控制和反向回屏的口径。

## 决定

| 决策点 | 定案 |
| --- | --- |
| 适用范围 | 仅 Voice Broadcast 的任务完成/失败回复；通知提醒与 Agent Alert 不触发 |
| 显示目标 | 每条播报开始时切到来源 Agent 会话，并让正在朗读的句/小段落保持可读位置 |
| 自动切页 | Voice Broadcast Follow 是 Content Page 的第四类自动切页例外；Waiting-for-Approval 的视觉优先级仍最高 |
| 播报回屏 | 每条播报开始时把 Dashboard 强制带回背屏；播报中被系统自动接管时再次回屏，不要求解锁 |
| 用户控制 | 用户手动滚动、切换会话/页面或明确退出时立即接管并暂停视觉跟随；不与用户争夺背屏 |
| 画面同步 | 显示完整原始回复；代码、表格、链接等语音略过内容不逐字念，可见进度跳到下一段实际朗读内容 |
| 已阅状态 | 自动滚动不标已阅；只有机主明确点按正文才改变 Session Read State |
| 播放结束 | 留在最后一条播报的回复；多条队列逐条切到各自来源会话；无法定位来源时不强行跳转，语音照播 |
| 设置 | 跟随与回屏由 Voice Broadcast 总开关控制，不增加独立开关 |

## 后果

- 语音与可看内容同步的收益优先于“不打断当前页面”，因此 Voice Broadcast 可以暂时推翻手动选页并从
  Native Rear Screen 带回 Dashboard；这是 2026-10-04 机主明确接受的取舍。
- Waiting-for-Approval 仍是最高视觉优先级；等待确认出现时先显示待批准内容，处理后回到正在播报内容。
- `Takeover` 继续只指 Native Rear Screen 顶掉 Dashboard；反方向统一称 `Voice Broadcast Reclaim（播报回屏）`。
- 用户明确退出后不自动回屏，保证自动展示不演变成与机主反复争夺 Rear Display。