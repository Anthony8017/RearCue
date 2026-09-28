# 2c 交叉淡入观感取证（无黑底闪烁）——帧采样记录

判定项：spec 0013 story 24 / 验收项 2c「切页用短交叉淡入淡出、不响不震」。

## 方法

设备侧并发抓帧 + 中途点按，帧序按抓取顺序落盘（`screencap` 单帧约 200ms，故一次过渡只可能
吃到 0–1 帧中态）：

```powershell
adb shell "(for i in 1 2 ... 12; do screencap -p -d 4630946949513469332 /data/local/tmp/m\${i}.png; done) & sleep 0.4; input -d 1 tap 350 470; wait"
```

对每帧做 16px 网格采样，算平均亮度与「近黑像素占比」（亮度 < 10）：

| 帧 | 平均亮度 | 近黑占比 | 屏上内容 |
| --- | --- | --- | --- |
| m1 | 100.0 | 0.0% | 通知页（切页前） |
| m2 | 100.1 | 0.0% | 通知页（切页前，本目录 `25-crossfade-frame-before.png`） |
| m3 | 98.8 | 0.0% | Agent 页（切页后，`26-crossfade-frame-after.png`） |
| m4–m12 | 98.6–98.7 | 0.0% | Agent 页（稳态） |

同窗日志锚：`content page toggle agent`（23:49:11.730）→ `content page crossfade start show=agent`
（11.760）→ `content page crossfade done show=agent durationMs=180`（11.940）。

## 读法

- 采样帧里**没有任何一帧出现黑底或亮度塌陷**：切页前后平均亮度只差 1.4（100.1 → 98.6），
  近黑像素占比全程 0.0% ⇒ 「无黑底闪烁」在帧采样粒度上成立。
- 实现面证据：切页是 `AnimatedContent` 的 `fadeIn/fadeOut(tween(CONTENT_PAGE_CROSSFADE_MS))`
  纯 alpha 混合（`rear/.../RearDashboardActivity.kt`），两层叠在同一背景层上，不存在中间黑帧的构造；
  充电水面与水位数字在该 `AnimatedContent` **之外**（同文件 329/355 行注释锁定）⇒ 背景层不参与淡入。
- 不响不震：`:rear` 全模块 grep `Vibrat|AudioManager|MediaPlayer|SoundPool|Ringtone|performHaptic`
  零命中 ⇒ 代码里没有任何发声/振动调用面。
- 剩余主观项：180ms 淡入淡出的手感与「无重影观感」仍属肉眼项，机主可在实机上直接复看
  （判定表 2c 记 INCONCLUSIVE，不以本记录替代人工结论）。
