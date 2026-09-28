package com.rearcue.poc

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.rearcue.poc.notify.cancelTestNotification
import com.rearcue.poc.notify.postTestNotification
import com.rearcue.poc.rear.WakeKeepAlive

/**
 * PC 实验控制入口（票 #6）：`adb shell am broadcast -n com.rearcue.poc/.DebugCommandReceiver -a <action>`
 * 驱动的手动旁路，让 E3/E4/E6/E8 这类「应用在后台」的实验不用人点手机屏幕。
 *
 * 只在 debug 构建注册（声明在 `app/src/debug/AndroidManifest.xml`），且用
 * `android.permission.DUMP` 保护：只有 shell/system（`adb shell`）能发。
 * 投送/退出/状态三个动作走 [AppContainer] 的调试旁路（等价于主屏调试页上的按钮）；
 * 通知类的三个动作更薄——`POST_TEST`/`CANCEL_TEST` 直接调 `notify` 包里的发/撤函数，
 * `CANCEL_PACKAGE` 走 [AppContainer.cancelNotificationsOf]（撤销他人通知的权限只属于监听服务）。
 * 两类都不改动自动流转的决策。
 *
 * 票 #7 起，PC 脚本（`tools/ex`）用它驱动「发/撤测试通知」，这样整条链路
 * （通知 → Icon Set → 上/下屏）都能一键复跑，不必人碰手机。
 */
class DebugCommandReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context?, intent: Intent?) {
        val container = (context?.applicationContext as? RearCueApp)?.container
        if (container == null) {
            Log.w(LOG_TAG, "调试动作 ${intent?.action} 到达时进程还没建好容器，忽略")
            return
        }
        when (intent?.action) {
            ACTION_PROJECT_REAR -> container.projectToRear()
            ACTION_EXIT_REAR -> container.exitRear()
            ACTION_POST_TEST -> {
                Log.i(LOG_TAG, "debug post test notification")
                context?.let(::postTestNotification)
            }
            ACTION_CANCEL_TEST -> {
                Log.i(LOG_TAG, "debug cancel test notification")
                context?.let(::cancelTestNotification)
            }
            // 撤销任意 Allowlist App 的通知（含 `cmd notification post` 的 shell 通知）：
            // 监听服务是唯一有权限撤销他人通知的角色，能力由它登记进容器。
            ACTION_CANCEL_PACKAGE -> {
                val pkg = intent.getStringExtra(EXTRA_PACKAGE)
                if (pkg.isNullOrEmpty()) {
                    Log.w(LOG_TAG, "调试动作 $ACTION_CANCEL_PACKAGE 缺 --es $EXTRA_PACKAGE")
                } else {
                    // 返回值只进日志：脚本侧判定看的是随后的通知事件，不是这里的结果码。
                    Log.i(LOG_TAG, "debug cancel pkg=$pkg -> ${container.cancelNotificationsOf(pkg)}")
                }
            }
            // E9 覆盖窗口准入探针（票 #10）：加/撤一块最小覆盖窗口，displayId 缺省 = 运行时识别的背屏。
            // 判定在 PC 侧（dumpsys window + 系统窗口日志），这里的日志只报失败原因。
            ACTION_OVERLAY_ADD -> {
                val display = intent.getIntExtra(EXTRA_DISPLAY_ID, -1).takeIf { it >= 0 }
                Log.i(LOG_TAG, "debug overlay add display=${display ?: "rear"}")
                context?.let { OverlayProbe.add(it, display) }
            }
            ACTION_OVERLAY_REMOVE -> {
                Log.i(LOG_TAG, "debug overlay remove")
                OverlayProbe.remove()
            }
            ACTION_STATE -> Log.i(
                LOG_TAG,
                "state ${container.state.value} rear=${container.rearBackend.state}",
            )
            // Wake Keep-alive 强度调节（票 #21）：`--el ms <间隔>` 运行中改注入间隔；
            // 不带参数只回读当前强度与是否在跑。只动 WakeKeepAlive.current，不进自动流转。
            ACTION_WAKE_INTERVAL -> {
                // int/long 都收（票 #24 实测）：`getLongExtra` 对 `--ei` 的 int extra 类型不匹配、
                // 静默返回默认值——tools/ex 全程用 `--ei`，这个动作曾因此一直是 no-op（调不动间隔）。
                val rawMs = intent?.extras?.get(EXTRA_MS)
                val ms = (rawMs as? Number)?.toLong() ?: -1L
                val keepAlive = WakeKeepAlive.current
                when {
                    keepAlive == null -> Log.w(LOG_TAG, "调试动作 $ACTION_WAKE_INTERVAL：保活循环未初始化，忽略")
                    ms > 0 -> {
                        keepAlive.intervalMs = ms.coerceAtLeast(WakeKeepAlive.MIN_INTERVAL_MS)
                        Log.i(LOG_TAG, "debug wake-interval ms=${keepAlive.intervalMs} running=${keepAlive.isRunning}")
                    }
                    else -> Log.i(LOG_TAG, "debug wake-interval ms=${keepAlive.intervalMs} running=${keepAlive.isRunning}")
                }
            }
            // Shizuku 运行时授权申请（等价于调试页按钮的授权分支）：重装清掉授权后，
            // tools/ex 用它把授权框弹出来（弹窗本身仍要人在手机上点一次）。
            ACTION_SHIZUKU_REQUEST -> {
                Log.i(LOG_TAG, "debug shizuku request")
                container.rearBackend.requestPermission()
            }
            // 充电动画总开关（spec 0008 / 票 #67 验收链）：`--ez enabled <bool>` 等价于设置页
            // 充电区的开关拨动——走 [AppContainer.setChargingAnimationEnabled] 同一事件入口
            // （ChargingAnimation 事件进 core + 写盘），PC 脚本免去设置页 UI 自动化的拨动竞态。
            ACTION_CHARGING_ENABLED -> {
                val enabled = intent.getBooleanExtra(EXTRA_ENABLED, true)
                Log.i(LOG_TAG, "debug charging-anim set enabled=$enabled")
                container.setChargingAnimationEnabled(enabled)
            }
            // Agent Mirror 伪状态注入（spec 0010 / 票 #84 验收链）：无电脑 ZCode 会话也能
            // 演示/验收各状态。`--es status working|waiting|idle`（必填）、`--es action <摘要>`、
            // `--es reply <原文>`、`--es workspace <名>`、`--ez connected <bool>`（断连回落演示）。
            // 走 core 同一事件入口（AgentSessionUpdated/AgentConnectionChanged），决策照旧在 DashboardCore。
            ACTION_AGENT_STATE -> {
                val status = intent.getStringExtra(EXTRA_STATUS)
                val connected = if (intent.hasExtra(EXTRA_CONNECTED)) {
                    intent.getBooleanExtra(EXTRA_CONNECTED, true)
                } else {
                    null
                }
                if (connected != null) {
                    Log.i(LOG_TAG, "debug agent connected=$connected")
                    container.debugInjectAgentConnection(connected)
                }
                when (status) {
                    "working", "waiting", "idle" -> {
                        val action = intent.getStringExtra(EXTRA_ACTION)
                        val reply = intent.getStringExtra(EXTRA_REPLY)
                        val workspace = intent.getStringExtra(EXTRA_WORKSPACE)
                        Log.i(LOG_TAG, "debug agent state status=$status action=${action?.length ?: 0}B reply=${reply?.length ?: 0}B")
                        container.debugInjectAgentState(status, workspace, action, reply)
                    }
                    null -> if (connected == null) {
                        Log.w(LOG_TAG, "调试动作 $ACTION_AGENT_STATE 缺 --es $EXTRA_STATUS 或 --ez $EXTRA_CONNECTED")
                    }
                    else -> Log.w(LOG_TAG, "debug agent state 未知 status=$status（working|waiting|idle）")
                }
            }
            // Agent 配对（spec 0010 / 票 #86 验收链）：`--es link <二维码链接>` 等价于设置页
            // 粘贴配对——PC 脚本免去手机小键盘粘长链接的输入竞态。走 [AppContainer.pairAgent]
            // 同一入口（解析→落盘→起链路），非法链接与 UI 同样拒绝。
            ACTION_AGENT_PAIR -> {
                val link = intent.getStringExtra(EXTRA_LINK)
                if (link.isNullOrEmpty()) {
                    Log.w(LOG_TAG, "调试动作 $ACTION_AGENT_PAIR 缺 --es $EXTRA_LINK")
                } else {
                    Log.i(LOG_TAG, "debug agent pair attempt link=${link.length}B")
                    val ok = container.pairAgent(link)
                    Log.i(LOG_TAG, "debug agent pair ok=$ok")
                }
            }
            // Agent Mirror 总开关（spec 0010 / 票 #88 验收链）：`--ez enabled <bool>` 等价设置页
            // Agent 区开关拨动——走 [AppContainer.setAgentMirrorEnabled] 同一入口（起/停链路＋写盘），
            // 无 UI 自动化竞态；验收用完可原样拨回。
            ACTION_AGENT_ENABLED -> {
                val enabled = intent.getBooleanExtra(EXTRA_ENABLED, true)
                Log.i(LOG_TAG, "debug agent enabled set=$enabled")
                container.setAgentMirrorEnabled(enabled)
            }
            // 姿态注入（自动化验收）：`--ez faceDown <bool>` 等价于接近传感器的防抖提交；
            // 真实传感器提交仍会覆盖（手机翻正即回真实读数）。
            ACTION_POSTURE -> {
                val faceDown = intent.getBooleanExtra(EXTRA_FACE_DOWN, true)
                Log.i(LOG_TAG, "debug posture set faceDown=$faceDown")
                container.debugInjectPosture(faceDown)
            }
            // 控制面探针（spec 0010 / 票 #86 phase B）：在线状态下发 bootstrap→workspace-list→
            // bridge-open→订阅序列，响应全量进 logcat。
            ACTION_AGENT_PROBE -> {
                Log.i(LOG_TAG, "debug agent probe start")
                container.debugAgentProbe()
            }
            else -> Log.w(LOG_TAG, "未知调试动作 ${intent?.action}")
        }
    }

    companion object {
        /** 投送当前 Icon Set 到背屏（后台实验用；等价于调试页「投送到背屏」按钮）。 */
        const val ACTION_PROJECT_REAR = "com.rearcue.poc.action.PROJECT_REAR"

        /** 结束在屏 Dashboard（等价于调试页「退出背屏」按钮）。 */
        const val ACTION_EXIT_REAR = "com.rearcue.poc.action.EXIT_REAR"

        /** 把当前状态打进 logcat（实验取证用）。 */
        const val ACTION_STATE = "com.rearcue.poc.action.STATE"

        /** 发本应用的测试通知（等价于调试页「发测试通知」按钮）。 */
        const val ACTION_POST_TEST = "com.rearcue.poc.action.POST_TEST"

        /** 撤本应用的测试通知（等价于调试页「清除测试通知」按钮）。 */
        const val ACTION_CANCEL_TEST = "com.rearcue.poc.action.CANCEL_TEST"

        /** 撤销 `--es pkg <包名>` 的全部 Active Notification（PC 脚本清场用）。 */
        const val ACTION_CANCEL_PACKAGE = "com.rearcue.poc.action.CANCEL_PACKAGE"

        /** [ACTION_CANCEL_PACKAGE] 的目标包名（`am broadcast --es pkg <pkg>`）。 */
        const val EXTRA_PACKAGE = "pkg"

        /** 加一块最小覆盖窗口到背屏（票 #10 / E9 探针；`--ei displayId <id>` 可指定别的屏）。 */
        const val ACTION_OVERLAY_ADD = "com.rearcue.poc.action.OVERLAY_ADD"

        /** 撤掉 [ACTION_OVERLAY_ADD] 加的覆盖窗口（票 #10 / E9 探针）。 */
        const val ACTION_OVERLAY_REMOVE = "com.rearcue.poc.action.OVERLAY_REMOVE"

        /** [ACTION_OVERLAY_ADD] 的目标屏（`am broadcast --ei displayId <id>`；缺省 = 运行时识别的背屏）。 */
        const val EXTRA_DISPLAY_ID = "displayId"

        /** 调 Wake Keep-alive 注入间隔（票 #21；`am broadcast --el ms <间隔毫秒>`，缺省只回读）。 */
        const val ACTION_WAKE_INTERVAL = "com.rearcue.poc.action.WAKE_INTERVAL"

        /** [ACTION_WAKE_INTERVAL] 的间隔毫秒（`am broadcast --el ms <ms>`）。 */
        const val EXTRA_MS = "ms"

        /** 弹 Shizuku 运行时授权申请框（等价于调试页「投送到背屏」按钮的授权分支）。 */
        const val ACTION_SHIZUKU_REQUEST = "com.rearcue.poc.action.SHIZUKU_REQUEST"

        /** 充电动画总开关（票 #67 验收链；`am broadcast --ez enabled <bool>`）。 */
        const val ACTION_CHARGING_ENABLED = "com.rearcue.poc.action.CHARGING_ENABLED"

        /** [ACTION_CHARGING_ENABLED] 的目标档位（`--ez enabled <bool>`）。 */
        const val EXTRA_ENABLED = "enabled"

        /** Agent Mirror 伪状态注入（spec 0010 票 #84；`--es status working|waiting|idle` 等）。 */
        const val ACTION_AGENT_STATE = "com.rearcue.poc.action.AGENT_STATE"

        /** [ACTION_AGENT_STATE] 的会话状态（working|waiting|idle）。 */
        const val EXTRA_STATUS = "status"

        /** [ACTION_AGENT_STATE] 的当前动作摘要（`--es action <文本>`，可缺省）。 */
        const val EXTRA_ACTION = "action"

        /** [ACTION_AGENT_STATE] 的最新回复原文（`--es reply <文本>`，可缺省）。 */
        const val EXTRA_REPLY = "reply"

        /** [ACTION_AGENT_STATE] 的工作区名（`--es workspace <文本>`，可缺省）。 */
        const val EXTRA_WORKSPACE = "workspace"

        /** [ACTION_AGENT_STATE] 的链路开关（`--ez connected <bool>`；断连回落演示用，可缺省）。 */
        const val EXTRA_CONNECTED = "connected"

        /** Agent 配对（spec 0010 票 #86；`--es link <二维码链接>`，等价设置页粘贴）。 */
        const val ACTION_AGENT_PAIR = "com.rearcue.poc.action.AGENT_PAIR"

        /** [ACTION_AGENT_PAIR] 的配对链接。 */
        const val EXTRA_LINK = "link"

        /** 姿态注入（自动化验收；`--ez faceDown <bool>`）。 */
        const val ACTION_POSTURE = "com.rearcue.poc.action.POSTURE"

        /** [ACTION_POSTURE] 的目标姿态（true = 倒扣）。 */
        const val EXTRA_FACE_DOWN = "faceDown"

        /** 控制面探针（票 #86 phase B；响应进 logcat）。 */
        const val ACTION_AGENT_PROBE = "com.rearcue.poc.action.AGENT_PROBE"

        /** Agent Mirror 总开关（spec 0010 / 票 #88 验收链；`--ez enabled <bool>`）。 */
        const val ACTION_AGENT_ENABLED = "com.rearcue.poc.action.AGENT_ENABLED"
    }
}
