package com.arduinobin.cli

import com.arduinobin.data.THIRD_PARTY_INDEX_URLS
import com.arduinobin.termux.CommandExecutor
import com.arduinobin.termux.ProcessKiller
import com.arduinobin.termux.TarGz
import com.arduinobin.termux.TermuxEnv
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * 对 arduino-cli 的封装。所有命令都在 Termux bootstrap 的 bash 环境里执行，
 * 这样编译阶段 spawn 出的交叉编译工具链（avr-gcc 等）能正确解析 bionic 库与 PATH。
 */
object ArduinoCli {

    const val CLI_DOWNLOAD_URL =
        "https://downloads.arduino.cc/arduino-cli/arduino-cli_latest_Linux_ARM64.tar.gz"

    /** 确保 arduino-cli 二进制就位。 */
    suspend fun ensureInstalled(onLine: (String) -> Unit): Boolean {
        if (TermuxEnv.isArduinoCliReady()) return true
        if (!TermuxEnv.prepareBootstrap(onLine)) return false
        if (!TermuxEnv.isBootstrapReady()) return false
        onLine("下载 arduino-cli (Linux ARM64) ...")
        val tmp = File(File(TermuxEnv.prefixDir, "../downloads").canonicalFile, "arduino-cli.tar.gz")
        tmp.parentFile?.mkdirs()
        val ok = withContext(Dispatchers.IO) {
            runCatching { download(tmp) }.getOrDefault(false)
        }
        if (!ok) {
            onLine("arduino-cli 下载失败。")
            return false
        }
        val extracted = File(File(TermuxEnv.prefixDir, "../cli-extract").canonicalFile, ".")
        extracted.mkdirs()
        val count = TarGz.extract(tmp, extracted)
        val binary = File(extracted, "arduino-cli")
        if (!binary.exists() || count == 0) {
            onLine("arduino-cli 解压失败。")
            return false
        }
        binary.copyTo(TermuxEnv.arduinoCliPath, overwrite = true)
        TermuxEnv.arduinoCliPath.setExecutable(true, false)
        TermuxEnv.writeArduinoCliConfig()
        onLine("arduino-cli 就绪。")
        return true
    }

    private fun download(dest: File): Boolean {
        val conn = URL(CLI_DOWNLOAD_URL).openConnection() as HttpURLConnection
        conn.connectTimeout = 20000
        conn.readTimeout = 180000
        conn.instanceFollowRedirects = true
        conn.connect()
        if (conn.responseCode !in 200..299) return false
        conn.inputStream.use { input ->
            FileOutputStream(dest).use { out -> input.copyTo(out) }
        }
        return dest.exists() && dest.length() > 0
    }

    /** 合并 arduino-cli 需要的目录/索引环境变量。 */
    fun cliEnv(): Map<String, String> = mapOf(
        "ARDUINO_DIRECTORIES_DATA" to TermuxEnv.dataDir.absolutePath,
        "ARDUINO_DIRECTORIES_DOWNLOADS" to File(TermuxEnv.dataDir, "staging").absolutePath,
        "ARDUINO_DIRECTORIES_USER" to TermuxEnv.workspaceDir.absolutePath,
        "ARDUINO_BOARD_MANAGER_ADDITIONAL_URLS" to THIRD_PARTY_INDEX_URLS.joinToString(" "),
        "ARDUINO_UPDATER_ENABLE_NOTIFICATION" to "false",
    )

    private fun fullEnv(): Map<String, String> = TermuxEnv.baseEnv() + cliEnv()

    private fun shQuote(s: String): String = "'" + s.replace("'", "'\\''") + "'"

    /** 基础执行入口：在 bootstrap bash 中运行 `arduino-cli <args>`。 */
    suspend fun run(
        args: List<String>,
        cwd: File? = null,
        killer: ProcessKiller? = null,
        onLine: (String) -> Unit,
    ): Int {
        val script = (listOf("arduino-cli") + args).joinToString(" ") { shQuote(it) }
        return CommandExecutor.run(
            command = TermuxEnv.bash(script),
            workingDir = cwd ?: TermuxEnv.workspaceDir,
            env = fullEnv(),
            killer = killer,
            onLine = onLine,
        )
    }

    suspend fun version(onLine: (String) -> Unit) = run(listOf("version"), onLine = onLine)

    suspend fun updateIndex(onLine: (String) -> Unit) =
        run(listOf("core", "update-index"), onLine = onLine)

    suspend fun installCore(platform: String, onLine: (String) -> Unit) =
        run(listOf("core", "install", platform), onLine = onLine)

    suspend fun installLibrary(name: String, version: String? = null, onLine: (String) -> Unit) {
        val args = buildList {
            add("lib"); add("install"); add(name)
            if (version != null) { add(version) }
        }
        run(args, onLine = onLine)
    }

    suspend fun searchLibraries(query: String, onLine: (String) -> Unit) =
        run(listOf("lib", "search", query), onLine = onLine)

    /** 编译指定 sketch；产物写入 outputDir。成功返回退出码。 */
    suspend fun compile(
        fqbn: String,
        sketchDir: File,
        outputDir: File,
        killer: ProcessKiller? = null,
        onLine: (String) -> Unit,
    ): Int {
        outputDir.mkdirs()
        return run(
            listOf(
                "compile",
                "--fqbn", fqbn,
                "--output-dir", outputDir.absolutePath,
                sketchDir.absolutePath,
            ),
            cwd = TermuxEnv.workspaceDir,
            killer = killer,
            onLine = onLine,
        )
    }
}