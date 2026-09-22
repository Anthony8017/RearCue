package com.rearcue.poc.autostart

import android.app.AppOpsManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.util.Log
import com.rearcue.poc.LOG_TAG
import com.rearcue.poc.core.AppOpMode
import com.rearcue.poc.core.AutostartJudge
import com.rearcue.poc.core.AutostartState
import java.lang.reflect.Method

/**
 * MIUI 自启动胶水（票 #28）：系统读数/跳转入口的零决策搬运。
 *
 * 检测口径见 docs/poc-findings.md「票 #27 验收」：`MIUIOP(10008)` + `MIUIOP(10053)` 双 allow
 * ⇔ 白名单在册（判定在 [AutostartJudge]，纯 Kotlin 单测）；跳转入口 = action
 * `miui.intent.action.OP_AUTO_START` + category DEFAULT（实测 JUMP-PASS），显式 component 兜底。
 *
 * 读数通道如实记：数值 appop 的 int 形参入口 `checkOpNoThrow(int, int, String)` 在 compileSdk 36
 * 的 SDK stubs 里已被移除（只剩 String op 变体，而 MIUI 数值 op 无 op 名：`AUTO_START` =
 * Unknown operation string），运行时框架类仍带该方法，故经反射调用；拿不到/异常按「状态存疑」
 * 降级（findings 降级判定①），绝不报健康。
 */

/** MIUI 自启动语义的两个数值 appop（named op 不存在）。 */
private const val OP_MIUI_10008 = 10008
private const val OP_MIUI_10053 = 10053

/** 实测 JUMP-PASS 的跳转入口（findings 票 #27 Q2）。 */
private const val AUTOSTART_ACTION = "miui.intent.action.OP_AUTO_START"
private const val SECURITY_CENTER_PKG = "com.miui.securitycenter"
private const val AUTOSTART_ACTIVITY = "com.miui.permcenter.autostart.AutoStartManagementActivity"

/** 运行时框架里的数值 op 查询入口（SDK stubs 已移除，见文件头注释）；null = 读不到 ⇒ 降级。 */
private val checkOpInt: Method? by lazy {
    runCatching {
        AppOpsManager::class.java.getMethod(
            "checkOpNoThrow",
            Int::class.javaPrimitiveType,
            Int::class.javaPrimitiveType,
            String::class.java,
        )
    }.getOrNull()
}

/**
 * 读自启动状态：AppOpsManager 读 10008/10053（与 shell `appops get` 同一 AppOpsService），
 * 折算成 [AppOpMode] 后交 [AutostartJudge] 判定。读不到/异常按「状态存疑」降级，绝不报健康。
 */
fun readAutostartState(context: Context): AutostartState {
    val ops = context.getSystemService(AppOpsManager::class.java)
    val uid = context.applicationInfo.uid
    val pkg = context.packageName
    return AutostartJudge.judge(readOp(ops, OP_MIUI_10008, uid, pkg), readOp(ops, OP_MIUI_10053, uid, pkg))
}

/** 单 op 读数折算：null = 入口/服务不可得或调用异常（降级判定①）；allow/ignore 之外 = 第三态。 */
private fun readOp(ops: AppOpsManager?, op: Int, uid: Int, pkg: String): AppOpMode? {
    val method = checkOpInt ?: run {
        Log.w(LOG_TAG, "appop 数值入口不可得（checkOpNoThrow(int,...)），按状态存疑降级")
        return null
    }
    return try {
        val mode = method.invoke(ops, op, uid, pkg) as Int
        when (mode) {
            AppOpsManager.MODE_ALLOWED -> AppOpMode.ALLOWED
            AppOpsManager.MODE_IGNORED -> AppOpMode.IGNORED
            else -> {
                Log.w(LOG_TAG, "appop 第三态 op=$op mode=$mode，按状态存疑降级")
                AppOpMode.OTHER
            }
        }
    } catch (t: Throwable) {
        Log.w(LOG_TAG, "appop 读数失败 op=$op，按状态存疑降级", t)
        null
    }
}

/**
 * 一键跳 MIUI 自启动设置页（跳转不依赖检测可用性）：action 变体为主（实测 JUMP-PASS）、
 * 显式 component 兜底；失败词表同 findings（起没起看是否抛异常，不看回执词）。
 */
fun openAutostartSettings(context: Context): Boolean {
    val candidates = listOf(
        Intent(AUTOSTART_ACTION).addCategory(Intent.CATEGORY_DEFAULT),
        Intent().setComponent(ComponentName(SECURITY_CENTER_PKG, AUTOSTART_ACTIVITY)),
    )
    candidates.forEach { intent ->
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (runCatching {
                context.startActivity(intent)
                true
            }.getOrDefault(false)
        ) {
            Log.i(LOG_TAG, "自启动设置页跳转已发出 target=${intent.component ?: "$AUTOSTART_ACTION+DEFAULT"}")
            return true
        }
    }
    Log.w(LOG_TAG, "自启动设置页跳转失败：action 与 component 均未起（JUMP-NO-TASK）")
    return false
}
