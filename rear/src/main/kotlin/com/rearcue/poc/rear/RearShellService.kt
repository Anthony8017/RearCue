package com.rearcue.poc.rear

import java.util.concurrent.TimeUnit

/** [IRearShell.run] 的返回解析结果。 */
data class ShellReply(val exitCode: Int, val output: String) {
    companion object {
        private const val EXIT_PREFIX = "exit="

        fun parse(raw: String?): ShellReply {
            val text = raw.orEmpty()
            val firstLine = text.lineSequence().firstOrNull().orEmpty()
            val code = firstLine.removePrefix(EXIT_PREFIX).trim().toIntOrNull() ?: -1
            val body = text.lineSequence().drop(1).joinToString("\n").trim()
            return ShellReply(exitCode = code, output = body)
        }

        fun of(exitCode: Int, output: String): String =
            "$EXIT_PREFIX$exitCode\n$output"
    }
}

/**
 * 跑在 Shizuku 提供的 shell uid 进程里，替主进程执行 shell 命令。
 *
 * Shizuku 的 UserService **必须是 IBinder**（[IRearShell.Stub]），不是 Android Service：server 用
 * `app_process` 自己把这个类 new 出来（Shizuku-API README「UserService」），写成 `Service` 会在启动
 * 瞬间 `ClassCastException: ... cannot be cast to android.os.IBinder` 然后 `System.exit(1)`——
 * 症状是授权、pingBinder 都正常，但 `userService=false` 永远绑不上（票 #8 实测）。
 *
 * 命令形状与超时都由调用方决定（见 [RearProjectionCommands]）；这里只负责执行与采集输出。
 * 注意：本进程不是应用进程，拿不到应用私有目录，所以不在这里落盘证据。
 */
class RearShellService : IRearShell.Stub() {

    override fun run(command: String): String = execute(command)

    /** 保留事务：Shizuku server 解绑/应用退出时要求本进程自尽（否则会留下 shell uid 的僵尸进程）。 */
    override fun destroy() = System.exit(0)

    private fun execute(command: String): String = try {
        val process = ProcessBuilder("sh", "-c", command).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().readText()
        val finished = process.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS)
        when {
            !finished -> {
                process.destroyForcibly()
                ShellReply.of(-1, "timeout after ${TIMEOUT_SECONDS}s")
            }

            else -> ShellReply.of(process.exitValue(), output.trim())
        }
    } catch (e: Exception) {
        ShellReply.of(-1, e.toString())
    }

    companion object {
        private const val TIMEOUT_SECONDS = 10L
    }
}
