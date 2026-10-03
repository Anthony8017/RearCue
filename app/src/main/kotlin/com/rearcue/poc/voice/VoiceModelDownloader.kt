package com.rearcue.poc.voice

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream
import java.io.BufferedInputStream
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * Kokoro 中文/英文离线模型的运行时下载与完整性检查（ADR 0015）。
 *
 * 模型只在 Voice Broadcast 总开关开启且仍选默认离线引擎时下载；下载完成后完全离线。
 * 下载文件是 sherpa-onnx 官方 tts-models 发布物，解包后只读使用，不在应用间共享。
 */
object VoiceModelDownloader {
    const val MODEL_NAME = "kokoro-multi-lang-v1_0"
    const val MODEL_URL =
        "https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/kokoro-multi-lang-v1_0.tar.bz2"

    private val REQUIRED_FILES = listOf(
        "model.onnx",
        "voices.bin",
        "tokens.txt",
        "lexicon-us-en.txt",
        "lexicon-zh.txt",
        "phone-zh.fst",
        "date-zh.fst",
        "number-zh.fst",
        "espeak-ng-data",
    )

    fun modelDir(context: Context): File =
        File(File(context.filesDir, "voice-models"), MODEL_NAME)

    fun isReady(context: Context): Boolean {
        val root = modelDir(context)
        return REQUIRED_FILES.all { File(root, it).exists() } &&
            File(root, "model.onnx").length() > 100_000_000L
    }

    /** 返回下载完成后的模型目录；失败时抛出，调用方负责展示降级提示。 */
    suspend fun download(context: Context): File = withContext(Dispatchers.IO) {
        val target = modelDir(context)
        if (isReady(context)) return@withContext target

        val stagingParent = File(context.filesDir, "voice-models")
        stagingParent.mkdirs()
        val archive = File(stagingParent, "$MODEL_NAME.tar.bz2.part")
        val unpacked = File(stagingParent, "$MODEL_NAME.unpack")
        try {
            if (unpacked.exists()) unpacked.deleteRecursively()
            unpacked.mkdirs()
            downloadArchive(archive)
            unpackArchive(archive, unpacked)
            val extracted = File(unpacked, MODEL_NAME)
            check(extracted.isDirectory) { "语音模型包缺少 $MODEL_NAME 目录" }
            checkRequiredFiles(extracted)
            if (target.exists()) target.deleteRecursively()
            check(extracted.renameTo(target)) { "语音模型目录无法落盘" }
            target
        } finally {
            archive.delete()
            unpacked.deleteRecursively()
        }
    }

    private fun downloadArchive(destination: File) {
        val connection = URL(MODEL_URL).openConnection() as HttpURLConnection
        connection.instanceFollowRedirects = true
        connection.connectTimeout = 30_000
        connection.readTimeout = 60_000
        try {
            check(connection.responseCode in 200..299) {
                "语音模型下载失败（HTTP ${connection.responseCode}）"
            }
            connection.inputStream.use { input ->
                FileOutputStream(destination).use { output ->
                    val buffer = ByteArray(256 * 1024)
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        output.write(buffer, 0, read)
                    }
                }
            }
        } finally {
            connection.disconnect()
        }
    }

    private fun unpackArchive(archive: File, destination: File) {
        val destinationRoot = destination.canonicalFile
        TarArchiveInputStream(
            BZip2CompressorInputStream(BufferedInputStream(archive.inputStream()), true),
        ).use { tar ->
            while (true) {
                val entry: TarArchiveEntry = tar.nextTarEntry ?: break
                val output = File(destinationRoot, entry.name).canonicalFile
                if (!output.path.startsWith(destinationRoot.path + File.separator)) {
                    throw IllegalStateException("语音模型包含非法路径")
                }
                if (entry.isDirectory) {
                    output.mkdirs()
                    continue
                }
                output.parentFile?.mkdirs()
                FileOutputStream(output).use { output -> tar.copyTo(output) }
            }
        }
    }

    private fun checkRequiredFiles(root: File) {
        val missing = REQUIRED_FILES.filterNot { File(root, it).exists() }
        check(missing.isEmpty()) { "语音模型不完整，缺少 ${missing.joinToString()}" }
    }
}
