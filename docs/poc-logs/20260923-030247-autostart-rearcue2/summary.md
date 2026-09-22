# RearCue 行 ground truth 轮（票 #27 Q1，收口时补记）

`ui-rows.xml`：**RearCue 行 `checked=false`**（tap 点 (1057,1904.5)）= MIUI 自启动白名单里本应用**不在册**，
与 `appops get com.rearcue.poc` 的 `MIUIOP(10053): ignore` 对上（当时 10008=allow 是票 #21 手工 `appops set` 的残留，见 findings）。
同屏其它行（JBL Portable…Steam）checked=false 均为「禁止」段原文。
