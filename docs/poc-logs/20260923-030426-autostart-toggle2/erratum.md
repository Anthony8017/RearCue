# erratum — 锁屏/他代理干预（票 #27）

本轮两次启动：第一次在 `WaitOne()` 撞上**被放弃的互斥锁**（AbandonedMutexException，env.md 旧收尾写法所致，
父代理已修）——未做任何设备操作；第二次进入后发现屏幕已被**其它代理的锁屏实验**换成 SystemUI/锁屏
（`ui-findrc1-*.xml` 是锁屏/通知层的原文快照），ChatGPT/RearCue 行都不可见 ⇒ 本轮**没有点任何开关**、无状态变化。
按纪律记为环境轮，不进结论。
