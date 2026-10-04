package com.rearcue.poc

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.rearcue.poc.core.MirrorTextSize
import com.rearcue.poc.agent.SessionActionKind
import com.rearcue.poc.agent.SessionActionRequest
import com.rearcue.poc.notification.ActiveNotification
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
            // 撤销任意应用的通知（含 `cmd notification post` 的 shell 通知）：
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
            // 姿态门控开关（票 #100 验收链）：`--ez enabled <bool>` 等价于设置页姿态区的
            // 开关拨动——走 [AppContainer.setPostureGateEnabled] 同一事件入口
            // （PostureGateEnabled 事件进 core + 写盘），PC 脚本免去设置页 UI 自动化的拨动竞态；
            // 缺省 true（开）——E2E 门控脚本默认开档测门，验收用完拨回 false（出厂默认关）。
            ACTION_POSTURE_GATE -> {
                val enabled = intent.getBooleanExtra(EXTRA_ENABLED, true)
                Log.i(LOG_TAG, "debug posture-gate set enabled=$enabled")
                container.setPostureGateEnabled(enabled)
            }
            // Agent Mirror 伪状态注入（spec 0010 / 票 #84 验收链）：无电脑 ZCode 会话也能
            // 演示/验收各状态。`--es status working|waiting|idle`（必填）、`--es action <摘要>`、
            // `--es reply <原文>`、`--es workspace <名>`、`--ez connected <bool>`（断连回落演示）。
            // spec 0018-1 起另支持 `--es source zcode|codex|claude|dsh`——第四来源（DSH）的
            // 显示链（来源标记/进册）不必真开 DSH 也能跑验收。
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
                    "working", "waiting", "idle", "error" -> {
                        val action = intent.getStringExtra(EXTRA_ACTION)
                        val reply = intent.getStringExtra(EXTRA_REPLY)
                        val workspace = intent.getStringExtra(EXTRA_WORKSPACE)
                        val turns = intent.getStringExtra(EXTRA_TURNS)
                        val source = intent.getStringExtra(EXTRA_SOURCE)
                        val title = intent.getStringExtra(EXTRA_TITLE)
                        Log.i(
                            LOG_TAG,
                            "debug agent state status=$status action=${action?.length ?: 0}B " +
                                "reply=${reply?.length ?: 0}B turns=${turns?.length ?: 0}B " +
                                "source=${source ?: "-"} title=${title ?: "-"}",
                        )
                        container.debugInjectAgentState(
                            status, workspace, action, reply, turns, source, title,
                            debugSessionId = intent.getStringExtra(EXTRA_SESSION_ID),
                        )
                    }
                    null -> if (connected == null) {
                        Log.w(LOG_TAG, "调试动作 $ACTION_AGENT_STATE 缺 --es $EXTRA_STATUS 或 --ez $EXTRA_CONNECTED")
                    }
                    else -> Log.w(LOG_TAG, "debug agent state 未知 status=$status（working|waiting|idle|error）")
                }
            }
            // Agent Alert 伪提醒注入（spec 0018-3 / 票 #173 验收链）：`--es kind waiting|done|error`
            // 直接走 [AppContainer.debugInjectAgentAlert] 的同一发放口（不经状态跃迁），
            // 可选 `--es summary <一句话>` 验摘要/退化；总开关与提醒开关的门照常生效。
            ACTION_AGENT_ALERT -> {
                val kind = intent.getStringExtra(EXTRA_KIND)
                if (kind.isNullOrBlank()) {
                    Log.w(LOG_TAG, "调试动作 $ACTION_AGENT_ALERT 缺 --es $EXTRA_KIND")
                } else {
                    Log.i(LOG_TAG, "debug agent alert kind=$kind")
                    container.debugInjectAgentAlert(kind, intent.getStringExtra(EXTRA_SUMMARY))
                }
            }
            // Remote Approval 验收链（spec 0018-4 / 票 #174）：`--es action approve|reject|select`
            // （select 带 `--es option <选项id>`，会话键缺省 = 调试伪会话）——走
            // [AppContainer.sendAgentAction] 同一条会话动作链（与通知栏按钮/主屏批准区同源），
            // 「等确认 → 三处批准入口 → 动作 → 状态推进/失败提示」全链可离线跑。
            ACTION_AGENT_APPROVE -> {
                val action = SessionActionKind.fromWire(intent.getStringExtra(EXTRA_APPROVE_ACTION))
                val sessionId = intent.getStringExtra(EXTRA_SESSION_ID) ?: DEBUG_APPROVE_SESSION
                if (action == null) {
                    Log.w(LOG_TAG, "调试动作 $ACTION_AGENT_APPROVE 缺/错 --es $EXTRA_APPROVE_ACTION（approve|reject|select）")
                } else {
                    Log.i(LOG_TAG, "debug agent approve action=${action.wire()} session=$sessionId")
                    container.sendAgentAction(
                        SessionActionRequest.of(sessionId, action, intent.getStringExtra(EXTRA_APPROVE_OPTION)),
                    )
                }
            }
            // Agent 配对（spec 0010 / 票 #86 验收链）：`--es link <二维码链接>` 等价于设置页
            // Agent Mirror 总开关（spec 0010 / 票 #88 验收链）：`--ez enabled <bool>` 等价设置页
            // Agent 区开关拨动——走 [AppContainer.setAgentMirrorEnabled] 同一入口（起/停链路＋写盘），
            // 无 UI 自动化竞态；验收用完可原样拨回。
            ACTION_AGENT_ENABLED -> {
                val enabled = intent.getBooleanExtra(EXTRA_ENABLED, true)
                Log.i(LOG_TAG, "debug agent enabled set=$enabled")
                container.setAgentMirrorEnabled(enabled)
            }
            // PC 桥地址（ADR 0006 / 票 #116 验收链）：`--es url <隧道URL>` 配置并即时起链路；
            // 不带或空串 = 清除（链路停机）。等价后续设置页桥区入口（同一 setBridgeUrl 收口）。
            ACTION_BRIDGE_URL -> {
                val url = intent.getStringExtra(EXTRA_URL)
                Log.i(LOG_TAG, "debug bridge url has=${!url.isNullOrEmpty()}")
                container.setBridgeUrl(url?.takeIf { it.isNotEmpty() })
            }
            // 正文档位（spec 0017 / 票 #169 验收链）：`--es size small|medium|large` 等价首页
            // Agent 卡片的三档单选——走 [AppContainer.setMirrorTextSize] 同一入口（进 core + 写盘），
            // 免去点 UI 的竞态；未知档记日志忽略。
            ACTION_MIRROR_TEXT_SIZE -> {
                val size = intent.getStringExtra(EXTRA_SIZE)
                if (size.isNullOrBlank()) {
                    Log.w(LOG_TAG, "调试动作 $ACTION_MIRROR_TEXT_SIZE 缺 --es $EXTRA_SIZE")
                } else {
                    val parsed = MirrorTextSize.fromName(size.uppercase())
                    Log.i(LOG_TAG, "debug mirror text size=${parsed.name}")
                    container.setMirrorTextSize(parsed)
                }
            }
            // 姿态注入（自动化验收）：`--ez faceDown <bool>` 等价于接近传感器的防抖提交；
            // 真实传感器提交仍会覆盖（手机翻正即回真实读数）。
            ACTION_POSTURE -> {
                val faceDown = intent.getBooleanExtra(EXTRA_FACE_DOWN, true)
                Log.i(LOG_TAG, "debug posture set faceDown=$faceDown")
                container.debugInjectPosture(faceDown)
            }
            // Session Lock 注入（票 #103 验收链）：`--es sessionId <会话键>` 锁定该会话，
            // 缺省/`auto` = 自动档——与设置区同一条写入口（SessionLock 事件进 core + 写盘 +
            // V4Bridge 订阅跟随），PC 脚本免去主屏列表 UI 的点选竞态；与 ACTION_AGENT_STATE
            // 的伪会话（sessionId=debug）组合即可演示锁定/插队/清锁全链。
            ACTION_SESSION_LOCK -> {
                val sessionId = intent.getStringExtra(EXTRA_SESSION_ID)
                Log.i(LOG_TAG, "debug session lock set sessionId=${sessionId ?: "auto"}")
                container.debugInjectSessionLock(sessionId)
            }
            // 图标动效验收 fixture（spec 0015 / 票 #150）：按包名注入/移除通知事件，走
            // [AppContainer.debugInjectFixturePosted]/[AppContainer.debugInjectFixtureRemoved] 的
            // gate 入口（等价于 NLS 回调 → 可见性路由 → 仓库 → core 的同一段），让本机构造不出的
            // 多应用图标档位（4~7 枚）能在真机上跑出。`--es mode post|remove`、`--es pkgs a,b,c`
            // （逗号分隔）、可选 `--es key <后缀>`（同 pkg 多 key 造角标）、`--es title/--es text`。
            ACTION_FIXTURE_NOTIF -> {
                val mode = intent.getStringExtra(EXTRA_MODE)
                val pkgs = intent.getStringExtra(EXTRA_PACKAGES)
                    ?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() }.orEmpty()
                val suffix = intent.getStringExtra(EXTRA_KEY)?.takeIf { it.isNotEmpty() } ?: "0"
                when {
                    pkgs.isEmpty() -> Log.w(LOG_TAG, "调试动作 $ACTION_FIXTURE_NOTIF 缺 --es $EXTRA_PACKAGES")
                    mode != "post" && mode != "remove" ->
                        Log.w(LOG_TAG, "调试动作 $ACTION_FIXTURE_NOTIF 未知 mode=$mode（post|remove）")
                    else -> {
                        val title = intent.getStringExtra(EXTRA_TITLE).orEmpty()
                        val text = intent.getStringExtra(EXTRA_TEXT).orEmpty()
                        pkgs.forEach { pkg ->
                            val notification = ActiveNotification(
                                pkg = pkg,
                                key = fixtureKey(pkg, suffix),
                                title = title,
                                text = text,
                            )
                            if (mode == "post") {
                                container.debugInjectFixturePosted(notification)
                            } else {
                                container.debugInjectFixtureRemoved(notification)
                            }
                        }
                        Log.i(LOG_TAG, "debug fixture $mode pkgs=${pkgs.joinToString(",")} key=$suffix")
                    }
                }
            }
            else -> Log.w(LOG_TAG, "未知调试动作 ${intent?.action}")
        }
    }

    /** fixture 用的伪 NLS key：`<user>|<pkg>|<id>|<tag>|<uid>` 形状，同 pkg 换后缀即多 key。 */
    private fun fixtureKey(pkg: String, suffix: String): String = "0|$pkg|1|$suffix|0"

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

        /**
         * 姿态门控开关（票 #100 验收链；`am broadcast --ez enabled <bool>`，缺省 true=开）——
         * 与 [ACTION_CHARGING_ENABLED] 同形：E2E 脚本开档测门，验收用完拨回默认关。
         */
        const val ACTION_POSTURE_GATE = "com.rearcue.poc.action.POSTURE_GATE"

        /** [ACTION_CHARGING_ENABLED] 的目标档位（`--ez enabled <bool>`）。 */
        const val EXTRA_ENABLED = "enabled"

        /** Agent Mirror 伪状态注入（spec 0010 票 #84；`--es status working|waiting|idle` 等）。 */
        const val ACTION_AGENT_STATE = "com.rearcue.poc.action.AGENT_STATE"

        /** [ACTION_AGENT_STATE] 的会话状态（working|waiting|idle|error）。 */
        const val EXTRA_STATUS = "status"

        /** [ACTION_AGENT_STATE] 的当前动作摘要（`--es action <文本>`，可缺省）。 */
        const val EXTRA_ACTION = "action"

        /** [ACTION_AGENT_STATE] 的最新回复原文（`--es reply <文本>`，可缺省）。 */
        const val EXTRA_REPLY = "reply"

        /** [ACTION_AGENT_STATE] 的工作区名（`--es workspace <文本>`，可缺省）。 */
        const val EXTRA_WORKSPACE = "workspace"

        /**
         * 问答流注入（spec 0017 / 票 #169 验收链）：`--es turns "u|提问;a|回答;a|再一段"`。
         * 形态见 `RearCueApp.debugInjectAgentState` 的解析器（逐段容错）。
         */
        const val EXTRA_TURNS = "turns"

        /**
         * [ACTION_AGENT_STATE] 的来源标记（spec 0018-1；`--es source zcode|codex|claude|dsh`，可缺省）。
         * 缺省/未知值 ＝ 不带来源标记（旧验收链行为不变）。
         */
        const val EXTRA_SOURCE = "source"

        /** [ACTION_AGENT_STATE] 的链路开关（`--ez connected <bool>`；断连回落演示用，可缺省）。 */
        const val EXTRA_CONNECTED = "connected"

        /** 姿态注入（自动化验收；`--ez faceDown <bool>`）。 */
        const val ACTION_POSTURE = "com.rearcue.poc.action.POSTURE"

        /** [ACTION_POSTURE] 的目标姿态（true = 倒扣）。 */
        const val EXTRA_FACE_DOWN = "faceDown"

        /**
         * 图标动效验收 fixture（spec 0015 / 票 #150）：按包名注入/移除通知事件（可见性路由入口）。
         * `--es mode post|remove --es pkgs a,b,c [--es key <后缀> --es title <标题> --es text <正文>]`。
         */
        const val ACTION_FIXTURE_NOTIF = "com.rearcue.poc.action.FIXTURE_NOTIF"

        /** [ACTION_FIXTURE_NOTIF] 的模式（post = 投报 / remove = 移除）。 */
        const val EXTRA_MODE = "mode"

        /** [ACTION_FIXTURE_NOTIF] 的目标包名列表（逗号分隔）。 */
        const val EXTRA_PACKAGES = "pkgs"

        /** [ACTION_FIXTURE_NOTIF] 的 key 后缀（缺省 "0"；同 pkg 多 key 造角标计数）。 */
        const val EXTRA_KEY = "key"

        /** [ACTION_FIXTURE_NOTIF] 的可选通知标题。 */
        const val EXTRA_TITLE = "title"

        /** [ACTION_FIXTURE_NOTIF] 的可选通知正文。 */
        const val EXTRA_TEXT = "text"
        /** Agent Mirror 总开关（spec 0010 / 票 #88 验收链；`--ez enabled <bool>`）。 */
        const val ACTION_AGENT_ENABLED = "com.rearcue.poc.action.AGENT_ENABLED"

        /** Session Lock 注入（票 #103 验收链；`--es sessionId <会话键>`，缺省或 "auto" = 自动档）。 */
        const val ACTION_SESSION_LOCK = "com.rearcue.poc.action.SESSION_LOCK"

        /** [ACTION_SESSION_LOCK] 的目标会话键（`--es sessionId <id>`；缺省/"auto" = 自动档）。 */
        const val EXTRA_SESSION_ID = "sessionId"

        /** PC 桥 URL（ADR 0006 / 票 #116；`--es url <隧道URL>`，空/缺省 = 清除）。 */
        const val ACTION_BRIDGE_URL = "com.rearcue.poc.action.BRIDGE_URL"
        /** [ACTION_BRIDGE_URL] 的隧道 URL。 */
        const val EXTRA_URL = "url"

        /** 正文档位注入（spec 0017 / 票 #169 验收链；`--es size small|medium|large`）。 */
        const val ACTION_MIRROR_TEXT_SIZE = "com.rearcue.poc.action.MIRROR_TEXT_SIZE"
        /** [ACTION_MIRROR_TEXT_SIZE] 的档位名（大小写不敏感；未知值退默认中档）。 */
        const val EXTRA_SIZE = "size"

        /** Agent Alert 伪提醒注入（spec 0018-3 票 #173；`--es kind waiting|done|error`）。 */
        const val ACTION_AGENT_ALERT = "com.rearcue.poc.action.AGENT_ALERT"
        /** [ACTION_AGENT_ALERT] 的提醒种类（waiting|done|error）。 */
        const val EXTRA_KIND = "kind"
        /** [ACTION_AGENT_ALERT] 的一句摘要（可缺省；缺省验「退化为会话名＋事件类型」）。 */
        const val EXTRA_SUMMARY = "summary"

        /** Remote Approval 动作注入（spec 0018-4 验收链）：`--es action approve|reject|select`。 */
        const val ACTION_AGENT_APPROVE = "com.rearcue.poc.action.AGENT_APPROVE"
        /** [ACTION_AGENT_APPROVE] 的动作词（approve|reject|select；wire 键名沿桥契约 "action"）。 */
        const val EXTRA_APPROVE_ACTION = "action"
        /** [ACTION_AGENT_APPROVE] 的选项 id（select 必带；其余动作忽略）。 */
        const val EXTRA_APPROVE_OPTION = "optionId"
        /** [ACTION_AGENT_APPROVE] 的缺省会话键：调试伪会话（与 AgentApprovePolicy.DEBUG_SESSION_ID 同源）。 */
        const val DEBUG_APPROVE_SESSION = com.rearcue.poc.agentmirror.AgentApprovePolicy.DEBUG_SESSION_ID
    }
}
