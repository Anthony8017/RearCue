package com.rearcue.poc.rear

import android.app.Service
import android.content.Intent
import android.os.IBinder
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
 * 命令形状与超时都由调用方决定（见 [RearProjectionCommands]）；这里只负责执行与采集输出。
 * 注意：本 Service 的进程不是应用进程，拿不到应用私有目录，所以不在这里落盘证据。
 */
class RearShellService : Service() {

    private val binder = object : IRearShell.Stub() {
        override fun run(command: String): String = execute(command)
    }

    override fun onBind(intent: Intent?): IBinder = binder

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
