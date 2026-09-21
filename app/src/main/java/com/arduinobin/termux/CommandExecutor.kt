package com.arduinobin.termux

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 在设备上执行本地 shell 兼容的二进制（Termux bootstrap 提供的 bash + arduino-cli）。
 * 输出按行流式回调，供实时日志展示使用。
 */
object CommandExecutor {

    /**
     * 执行命令，返回退出码。stdout 与 stderr 合并后逐行回调 [onLine]。
     * 支持通过 [killer] 外部取消（process.destroy）。
     */
    suspend fun run(
        command: List<String>,
        workingDir: File? = null,
        env: Map<String, String> = emptyMap(),
        killer: ProcessKiller? = null,
        onLine: (String) -> Unit,
    ): Int = withContext(Dispatchers.IO) {
        val pb = ProcessBuilder(command)
        workingDir?.let { pb.directory(it) }
        pb.environment().putAll(env)
        pb.redirectErrorStream(true)

        val process = try {
            pb.start()
        } catch (e: Exception) {
            onLine("无法启动进程: ${command.firstOrNull()} -> ${e.message}")
            return@withContext -1
        }
        killer?.set(process)

        process.inputStream.bufferedReader().forEachLine { line ->
            onLine(line)
        }
        process.waitFor()
    }
}

/** 用于在外部取消正在运行的进程。 */
class ProcessKiller {
    @Volatile
    private var process: Process? = null

    fun set(p: Process) { process = p }

    fun kill() {
        process?.let {
            runCatching { it.destroy() }
            runCatching { it.destroyForcibly() }
        }
    }
}