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
}

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
     * 注册授权结果回调：授权成功时先 [bind]（UserService 需要权限才能拉起）再执行 [action]。
     *
     * 权限弹窗由 Shizuku 应用呈现，普通应用无法用 ActivityResult 接结果，只能用它的监听器。
     */
    fun onPermissionGranted(action: () -> Unit) {
        if (permissionListener != null) return
        val listener = Shizuku.OnRequestPermissionResultListener { _, grantResult ->
            Log.i(TAG, "Shizuku 授权结果=$grantResult")
            if (grantResult == PackageManager.PERMISSION_GRANTED) {
                bind()
                action()
            }
        }
        permissionListener = listener
        runCatching { Shizuku.addRequestPermissionResultListener(listener) }
            .onFailure { Log.w(TAG, "注册授权监听失败", it) }
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

    override fun run(command: String): ShellResult {
        val shell = service
        if (shell == null) {
            bind()
            Log.w(TAG, "UserService 未就绪，跳过：$command")
            return ShellResult(exitCode = -1, output = "user service unavailable")
        }
        return try {
            val reply = ShellReply.parse(shell.run(command))
            Log.i(TAG, "sh [$command] exit=${reply.exitCode} out=${reply.output}")
            ShellResult(exitCode = reply.exitCode, output = reply.output)
        } catch (e: Exception) {
            Log.w(TAG, "sh 执行失败：$command", e)
            ShellResult(exitCode = -1, output = e.toString())
        }
    }

    companion object {
        private const val TAG = "RearCue"

        /** 与 `moe.shizuku.api.USER_SERVICE_VERSION` meta-data 保持一致。 */
        const val USER_SERVICE_VERSION = 1

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
