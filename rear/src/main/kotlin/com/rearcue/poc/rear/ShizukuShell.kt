package com.rearcue.poc.rear

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.IBinder
import android.util.Log
import java.io.File
import rikka.shizuku.Shizuku

/** 一次 shell 调用的结果；[output] 合并 stdout 与 stderr，用于落盘证据。 */
data class ShellResult(
    val exitCode: Int,
    val output: String,
) {
    val ok: Boolean get() = exitCode == 0
}

/** shell 执行面：真实实现是 [ShizukuShell]，测试用假实现记录命令。 */
interface Shell {
    /** 执行通道当前是否可用。 */
    val available: Boolean

    /** 是否还需要向用户申请 Shizuku 权限。 */
    val permissionRequired: Boolean

    /** 诊断用一行摘要（server / 授权 / UserService 三者状态），进日志与调试页。 */
    val diagnostic: String

    fun run(command: String): ShellResult

    /** 同 [run]，但不把完整 stdout 写进 logcat；高频只读探测走这里。 */
    fun runQuiet(command: String): ShellResult = run(command)
}

/**
 * shell 调用的 logcat 行（票 #129 安静执行面）：[quiet] 只报输出长度、不落完整 stdout；
 * 普通调试命令保持「完整 stdout 进 logcat」的原语义（既有验收链按词形读，不改）。
 */
fun shellLogLine(command: String, exitCode: Int, output: String, quiet: Boolean): String =
    if (quiet) "sh [$command] exit=$exitCode out=<${output.length} chars>"
    else "sh [$command] exit=$exitCode out=$output"

/**
 * 经 Shizuku UserService 执行 shell 命令（ADR-0001 Route A）。
 *
 * 公开 API 只有 `bindUserService`/`requestPermission`（无 `newProcess`），所以投送命令
 * 由跑在 shell uid 里的 [RearShellService] 执行。绑定是异步的：[bind] 之后 [available]
 * 才可能为真；未就绪时 [run] 返回失败而不抛异常，上层据此进入 Degrade（票 #6 接恢复重投）。
 *
 * 命令拼装在 [RearProjectionCommands]（纯 Kotlin，可单测）；本类只做通道与生命周期。
 */
class ShizukuShell(context: Context) : Shell {

    private val appContext = context.applicationContext

    private var service: IRearShell? = null

    private var permissionListener: Shizuku.OnRequestPermissionResultListener? = null

    /** 兜底通道可用性回调（授权成功、server 上线/掉线都走它）。 */
    private val availabilityListeners = mutableListOf<(Boolean) -> Unit>()

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            service = IRearShell.Stub.asInterface(binder)
            Log.i(TAG, "UserService 已连接：$name")
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            service = null
            Log.w(TAG, "UserService 断开：$name")
        }
    }

    /** UserService 声明：进程名带 `shizuku` 后缀，跑在 Shizuku 的 shell uid 下。 */
    private val args = Shizuku.UserServiceArgs(
        ComponentName(appContext.packageName, RearShellService::class.java.name),
    )
        .daemon(false)
        .processNameSuffix("shizuku")
        .debuggable((appContext.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0)
        .version(USER_SERVICE_VERSION)

    // 监听必须在 args 之后注册：sticky 版 binder 监听在 server 已在线时**同步**回调，
    // 那时 bind() 会读 args——注册早了会拿到未初始化的字段（票 #6 review 抓到的顺序问题）。
    init {
        registerPermissionListener()
        registerServerListeners()
    }

    override val available: Boolean get() = service != null

    override val diagnostic: String
        get() = "server=${serverAvailable} granted=${permissionGranted} userService=${service != null}"

    override val permissionRequired: Boolean get() = serverAvailable && !permissionGranted

    private val serverAvailable: Boolean
        get() = try {
            Shizuku.pingBinder()
        } catch (e: Exception) {
            Log.w(TAG, "pingBinder 失败", e)
            false
        }

    private val permissionGranted: Boolean
        get() = try {
            serverAvailable && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
        } catch (e: Exception) {
            Log.w(TAG, "权限检查失败", e)
            false
        }

    /** 申请 Shizuku 权限（弹 Shizuku 的授权对话框）。 */
    fun requestPermission() {
        if (!serverAvailable) return
        runCatching { Shizuku.requestPermission(REQUEST_CODE) }
            .onFailure { Log.w(TAG, "requestPermission 失败", it) }
    }

    /**
     * 注册授权结果回调：授权成功时先 [bind]（UserService 需要权限才能拉起）再通知上层。
     *
     * 权限弹窗由 Shizuku 应用呈现，普通应用无法用 ActivityResult 接结果，只能用它的监听器。
     */
    private fun registerPermissionListener() {
        if (permissionListener != null) return
        val listener = Shizuku.OnRequestPermissionResultListener { _, grantResult ->
            Log.i(TAG, "Shizuku 授权结果=$grantResult")
            if (grantResult == PackageManager.PERMISSION_GRANTED) {
                bind()
                notifyAvailability(true)
            }
        }
        permissionListener = listener
        runCatching { Shizuku.addRequestPermissionResultListener(listener) }
            .onFailure { Log.w(TAG, "注册授权监听失败", it) }
    }

    /**
     * 注册 Shizuku server 生命周期监听（票 #6 / E8）：server 被杀或重启都不该让应用崩溃，
     * 也不能改变「投送通道」判据（应用内投送不需要它），只影响兜底命令通道。
     *
     * sticky 版在 server 已在运行时立刻回调一次，所以应用启动时就能把 UserService 绑上。
     */
    private fun registerServerListeners() {
        runCatching {
            Shizuku.addBinderReceivedListenerSticky {
                Log.i(TAG, "Shizuku server 上线 $diagnostic")
                bind()
                notifyAvailability(true)
            }
            Shizuku.addBinderDeadListener {
                Log.w(TAG, "Shizuku server 掉线 $diagnostic")
                service = null
                notifyAvailability(false)
            }
        }.onFailure { Log.w(TAG, "注册 Shizuku 生命周期监听失败", it) }
    }

    /**
     * 兜底通道可用性变化（授权成功 / server 上线 / 掉线）。
     *
     * 注册时 server 已经在线就补发一次 true：sticky 监听的上线事件发生在 [ShizukuShell] 构造期，
     * 那时上层还没来得及注册回调，不补发就会漏掉「启动前 Shizuku 已就绪」这一路（票 #6 review）。
     */
    fun onAvailabilityChanged(action: (Boolean) -> Unit) {
        availabilityListeners += action
        if (permissionGranted) action(true)
    }

    private fun notifyAvailability(available: Boolean) {
        Log.i(TAG, "兜底通道${if (available) "可用" else "不可用"} $diagnostic")
        availabilityListeners.toList().forEach { it(available) }
    }

    /** 绑定 UserService；已绑定或未授权时是空操作。 */
    fun bind() {
        if (service != null || !permissionGranted) return
        runCatching { Shizuku.bindUserService(args, connection) }
            .onFailure { Log.w(TAG, "bindUserService 失败", it) }
    }

    fun unbind() {
        service = null
        runCatching { Shizuku.unbindUserService(args, connection, true) }
            .onFailure { Log.w(TAG, "unbindUserService 失败", it) }
    }

    override fun run(command: String): ShellResult = execute(command, logOutput = true)

    override fun runQuiet(command: String): ShellResult = execute(command, logOutput = false)

    private fun execute(command: String, logOutput: Boolean): ShellResult {
        val shell = service
        if (shell == null) {
            bind()
            Log.w(TAG, "UserService 未就绪，跳过：$command")
            return ShellResult(exitCode = -1, output = "user service unavailable")
        }
        return try {
            val reply = ShellReply.parse(shell.run(command))
            Log.i(TAG, shellLogLine(command, reply.exitCode, reply.output, quiet = !logOutput))
            ShellResult(exitCode = reply.exitCode, output = reply.output)
        } catch (e: Exception) {
            Log.w(TAG, "sh 执行失败：$command", e)
            ShellResult(exitCode = -1, output = e.toString())
        }
    }

    companion object {
        private const val TAG = "RearCue"

        /**
         * UserService 版本：Shizuku 用它决定「复用已跑着的进程还是换新的」（同一个 tag 版本不符
         * 才会重启服务）。改了 [RearShellService] 的接口语义就要 +1，否则会复用旧进程的旧代码。
         * v2：UserService 从 Android Service 改成 `IRearShell.Stub` 并补上保留事务 destroy()（票 #8）。
         */
        const val USER_SERVICE_VERSION = 2

        /** Shizuku 授权请求码（`Shizuku.requestPermission`）。 */
        const val REQUEST_CODE = 4001
    }
}

/** 把命令与输出落盘，供 `adb pull` 收进 findings（票 #7 的实验脚本会消费）。 */
class ShellTranscript(private val directory: File) {

    private val lines = mutableListOf<String>()

    fun record(command: String, result: ShellResult) {
        lines += "$ $command"
        lines += "exit=${result.exitCode}"
        lines += result.output
    }

    fun flush(name: String) {
        runCatching {
            directory.mkdirs()
            File(directory, name).writeText(lines.joinToString("\n"))
        }.onFailure { Log.w("RearCue", "证据落盘失败", it) }
    }
}
