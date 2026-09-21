package com.arduinobin.termux

import android.content.Context
import com.arduinobin.data.THIRD_PARTY_INDEX_URLS
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.util.zip.ZipFile

/**
 * 管理自带的 Termux/bootstrap 环境：
 *  - 首次运行把打包进 APK 的 bootstrap-aarch64.zip 解压到应用私有目录；
 *  - 处理 SYMLINKS.txt、修正可执行权限；
 *  - 提供在 bootstrap 环境里运行命令所需的环境变量与 bash 入口。
 *
 * 仅支持 arm64 设备（bootstrap 采用 Termux 官方 `bootstrap-aarch64.zip`，使用 bionic libc）。
 */
object TermuxEnv {

    const val ASSET_ZIP = "termux/bootstrap-aarch64.zip"

    // 兜底下载地址（当 APK 内未携带 bootstrap 时使用），tag 形如 bootstrap-YYYY.MM.DD。
    const val BOOTSTRAP_FALLBACK_URL =
        "https://github.com/termux/termux-packages/releases/download/bootstrap-2026.09.20-r1%2Bapt.android-7/bootstrap-aarch64.zip"

    lateinit var prefixDir: File
        private set
    lateinit var homeDir: File
        private set
    lateinit var workspaceDir: File
        private set
    lateinit var dataDir: File
        private set
    private lateinit var downloadsDir: File
    private lateinit var appContext: Context

    val bashPath: File get() = File(prefixDir, "bin/bash")
    val arduinoCliPath: File get() = File(prefixDir, "bin/arduino-cli")

    fun setup(context: Context) {
        appContext = context.applicationContext
        val files = appContext.filesDir
        val base = File(files, "termux")
        prefixDir = File(base, "usr")
        homeDir = File(base, "home")
        workspaceDir = File(files, "Arduino")
        dataDir = File(files, "arduino15")
        downloadsDir = File(dataDir, "staging")
    }

    fun isBootstrapReady(): Boolean = bashPath.exists()
    fun isArduinoCliReady(): Boolean = arduinoCliPath.exists()

    private fun ensureDirs() {
        prefixDir.mkdirs()
        homeDir.mkdirs()
        workspaceDir.mkdirs()
        dataDir.mkdirs()
        downloadsDir.mkdirs()
    }

    /** 解压/初始化 bootstrap。返回是否成功。 */
    fun prepareBootstrap(onLine: (String) -> Unit): Boolean {
        if (isBootstrapReady()) return true
        ensureDirs()
        val zipFile = locateBootstrapZip(onLine) ?: return false
        return try {
            onLine("解压 Termux bootstrap ...")
            extractBootstrap(zipFile)
            onLine("重建符号链接 ...")
            createSymlinks()
            chmodExecutables()
            onLine("Termux 环境初始化完成。")
            true
        } catch (e: Exception) {
            onLine("Termux 初始化失败: ${e.message}")
            false
        }
    }

    /** 优先取 APK 内置 bootstrap；缺失则尝试下载。 */
    private fun locateBootstrapZip(onLine: (String) -> Unit): File? {
        val builtin = File(File(appContext.filesDir, "termux"), "bootstrap.zip")
        runCatching {
            appContext.assets.open(ASSET_ZIP).use { input ->
                FileOutputStream(builtin).use { out -> input.copyTo(out) }
            }
        }
        if (builtin.exists() && builtin.length() > 0) {
            onLine("使用 APK 内置 bootstrap (${builtin.length() / 1024 / 1024} MB)。")
            return builtin
        }
        onLine("APK 未内置 bootstrap，尝试从网络下载 ...")
        val fetched = runCatching {
            distDownload("bootstrap") { output ->
                val url = java.net.URL(BOOTSTRAP_FALLBACK_URL).openConnection() as java.net.HttpURLConnection
                url.connectTimeout = 20000
                url.readTimeout = 120000
                url.instanceFollowRedirects = true
                url.inputStream.use { it.copyTo(output) }
            }
        }.getOrNull()
        return fetched
    }

    private fun distDownload(name: String, writer: (FileOutputStream) -> Unit): File? {
        val f = File(File(appContext.filesDir, "termux"), "$name.zip")
        FileOutputStream(f).use { writer(it) }
        return f.takeIf { it.exists() && it.length() > 0 }
    }

    private fun extractBootstrap(zip: File) {
        ZipFile(zip).use { zf ->
            val entries = zf.entries()
            while (entries.hasMoreElements()) {
                val entry = entries.nextElement()
                val target = File(prefixDir, entry.name)
                if (entry.isDirectory) {
                    target.mkdirs()
                    continue
                }
                target.parentFile?.mkdirs()
                if (target.exists()) target.delete()
                zf.getInputStream(entry).use { input ->
                    FileOutputStream(target).use { out -> input.copyTo(out) }
                }
            }
        }
    }

    /** 依据 bootstrap 内的 SYMLINKS.txt 创建相对符号链接（target←link）。 */
    private fun createSymlinks() {
        val map = File(prefixDir, "SYMLINKS.txt")
        if (!map.exists()) return
        map.forEachLine { raw ->
            val line = raw.trim()
            if (line.isEmpty()) return@forEachLine
            val parts = line.split("←")
            if (parts.size != 2) return@forEachLine
            val target = parts[0].trim()
            val linkFile = File(prefixDir, parts[1].trim())
            runCatching {
                if (linkFile.exists() || Files.isSymbolicLink(linkFile.toPath())) {
                    linkFile.delete()
                }
                linkFile.parentFile?.mkdirs()
                Files.createSymbolicLink(linkFile.toPath(), java.nio.file.Paths.get(target))
            }
        }
    }

    private fun chmodExecutables() {
        val dirs = listOf(File(prefixDir, "bin"), File(prefixDir, "usr/bin"), File(prefixDir, "lib/apt/methods"))
        dirs.forEach { dir ->
            dir.listFiles()?.forEach { if (it.isFile) it.setExecutable(true, false) }
        }
        prefixDir.walkTopDown()
            .filter { it.isFile && (it.name.endsWith(".so") || it.name.endsWith(".so.0")) }
            .forEach { it.setExecutable(true, false) }
    }

    /** 供在 bootstrap 里执行命令的基础环境变量。 */
    fun baseEnv(): Map<String, String> = mapOf(
        "PREFIX" to prefixDir.absolutePath,
        "HOME" to homeDir.absolutePath,
        "TMPDIR" to File(File(appContext.filesDir, "termux"), "tmp").also { it.mkdirs() }.absolutePath,
        "ANDROID_ROOT" to "/system",
        "ANDROID_DATA" to "/data",
        "PATH" to "${prefixDir}/bin:${prefixDir}/bin/applets:/system/bin:/system/xbin",
        "LD_LIBRARY_PATH" to "${prefixDir}/lib",
        "LANG" to "en_US.UTF-8",
        "TERM" to "xterm-256color",
    )

    /** 用 bootstrap 的 bash 执行一段 shell 脚本。 */
    fun bash(script: String, extraEnv: Map<String, String> = emptyMap()): List<String> =
        listOf(bashPath.absolutePath, "-lc", script)

    /** 生成 arduino-cli 使用的 arduino-cli.yaml 配置文件内容。 */
    fun writeArduinoCliConfig() {
        val yaml = buildString {
            appendLine("board_manager:")
            appendLine("  additional_urls:")
            THIRD_PARTY_INDEX_URLS.forEach { appendLine("    - $it") }
            appendLine("directories:")
            appendLine("  data: ${dataDir.absolutePath}")
            appendLine("  downloads: ${downloadsDir.absolutePath}")
            appendLine("  user: ${workspaceDir.absolutePath}")
            appendLine("updater:")
            appendLine("  enable_notification: false")
        }
        File(homeDir, ".arduino15").mkdirs()
        File(homeDir, "arduino-cli.yaml").writeText(yaml)
        // arduino-cli 在 HOME 下寻找 arduino-cli.yaml
        dataDir.mkdirs()
        File(dataDir, "arduino-cli.yaml").writeText(yaml)
    }
}