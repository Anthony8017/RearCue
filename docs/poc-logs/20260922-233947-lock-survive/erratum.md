# erratum — 20260922-233947 lock-survive（-AppKeepAlive 回归轮第一次尝试）

## 结论

E13-NO-BASELINE，**这轮不进 findings**：投送基线根本没起来，与保活无关。根因是环境（23:39 签名变更重装把逐应用授权全清了），不是实现缺陷。

## 事实链

- 23:39:29 `signature/version mismatch: uninstalling first`（01-install 记录）→ 卸载重装清空 Shizuku 授权与通知监听绑定。
- 应用日志（`logcat-rearcue.txt`）：`refresh server=true granted=false userService=false`（Shizuku 运行时授权没了）、`debug cancel pkg=com.android.shell cancelled=-1（监听服务未连接）`（通知监听没绑上）。
- `iconSet [] -> [] tracked=0` 全程：POST_TEST 的测试通知没有被监听服务登记，核心不触发投送 → 基线起不来。
- 判定链其余部分工作正常（`lock-press-lag` 0.51、PowerGroup group-1 power-off 齐全、pollution 无命中），纯环境问题。

## 处置

- `appops set com.rearcue.poc 10008 allow`（MIUI 自启动）+ `cmd notification allow_listener` 重授 + 重启应用 → 监听恢复。
- `ACTION_SHIZUKU_REQUEST`（新增 debug 动作）弹授权框 → `granted=true` + `UserService 已连接`，重启应用后仍持久。
- 另发现 MIUI「亮屏距离传感器防误触」引导窗（`ScreenOnProximitySensorGuide`）会盖住主屏吞掉上滑解锁；`settings put global enable_screen_on_proximity_sensor 0` 可关（本机默认开着）。

## 判读边界

本轮的 `e13-exit-residual.txt`（keep-alive-stopped=False）同理无意义：保活从未启动，无「停」可证。
