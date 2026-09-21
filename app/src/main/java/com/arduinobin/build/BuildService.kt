package com.arduinobin.build

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.arduinobin.MainActivity
import com.arduinobin.cli.ArduinoCli
import com.arduinobin.data.BoardPreset
import com.arduinobin.data.BuildSession
import com.arduinobin.data.BuildStatus
import com.arduinobin.data.boardById
import com.arduinobin.termux.TermuxEnv
import com.arduinobin.util.ProjectExtractor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileOutputStream

/**
 * 前台服务：在后台完成「解压项目 → 装依赖 → 编译」全流程，切到后台也不中断，
 * 通过通知栏展示进度，通过 [BuildSession] 向 UI 推送实时日志。
 */
class BuildService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var notificationManager: NotificationManager? = null

    companion object {
        const val CHANNEL_ID = "build_progress"
        const val EXTRA_ZIP_URI = "zip_uri"
        const val EXTRA_BOARD_ID = "board_id"
        const val EXTRA_PROJECT_NAME = "project_name"
        const val EXTRA_LIBRARIES = "libraries"
        const val NOTIF_ID = 0x5270

        fun start(context: Context, zipUri: Uri, boardId: String, projectName: String, libraries: List<String>) {
            val intent = Intent(context, BuildService::class.java).apply {
                putExtra(EXTRA_ZIP_URI, zipUri.toString())
                putExtra(EXTRA_BOARD_ID, boardId)
                putExtra(EXTRA_PROJECT_NAME, projectName)
                putStringArrayListExtra(EXTRA_LIBRARIES, ArrayList(libraries))
            }
            context.startForegroundService(intent)
        }
    }

    override fun onCreate() {
        super.onCreate()
        notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        createChannel()
        startForeground(NOTIF_ID, buildNotification("准备构建 ...", "初始化环境"))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        TermuxEnv.setup(this)
        val zipUri = intent?.getStringExtra(EXTRA_ZIP_URI)?.let(Uri::parse) ?: return stopSelfAndReturn()
        val boardId = intent.getStringExtra(EXTRA_BOARD_ID)
            ?: return stopSelfAndReturn()
        val projectName = intent.getStringExtra(EXTRA_PROJECT_NAME) ?: "sketch"
        val libraries = intent.getStringArrayListExtra(EXTRA_LIBRARIES) ?: arrayListOf()

        scope.launch {
            runBuild(zipUri, boardId, projectName, libraries)
        }
        return START_NOT_STICKY
    }

    private suspend fun runBuild(zipUri: Uri, boardId: String, projectName: String, libraries: List<String>) {
        BuildSession.reset()
        val board: BoardPreset = try {
            boardById(boardId)
        } catch (e: Exception) {
            BuildSession.log("未知的开发板: $boardId")
            finishFailed()
            return
        }
        BuildSession.setProject(projectName, board)
        val log: (String) -> Unit = { line ->
            BuildSession.log(line)
            notificationManager?.notify(NOTIF_ID, buildNotification(line, BuildSession.state.value.progressLabel))
        }

        try {
            BuildSession.setStatus(BuildStatus.PREPARING, "初始化 Termux 环境")
            log("开始构建项目「$projectName」目标板「${board.name}」.")

            if (!TermuxEnv.prepareBootstrap(log)) {
                log("Termux 环境初始化失败，无法继续。")
                finishFailed()
                return
            }

            BuildSession.setStatus(BuildStatus.DOWNLOADING, "准备 arduino-cli")
            if (!ArduinoCli.ensureInstalled(log)) {
                finishFailed()
                return
            }

            // 解压上传的项目
            val zipFile = copyUploadToCache(zipUri)
            log("解析项目源码 ...")
            val sketch = ProjectExtractor.extract(zipFile, TermuxEnv.workspaceDir, projectName)
            log("主 sketch 定位: ${sketch.mainIno.name}")

            // 更新索引 + 安装对应 core
            BuildSession.setStatus(BuildStatus.DOWNLOADING, "更新 board 索引")
            log("更新 board 索引 ...")
            ArduinoCli.updateIndex(log)

            BuildSession.setStatus(BuildStatus.DOWNLOADING, "安装平台 ${board.corePlatform}")
            log("安装平台 ${board.corePlatform}（含编译工具链，首次较慢）...")
            ArduinoCli.installCore(board.corePlatform, log)

            // 安装用户指定的依赖库
            libraries.filter { it.isNotBlank() }.forEach { lib ->
                BuildSession.setStatus(BuildStatus.DOWNLOADING, "安装库 $lib")
                log("安装依赖库: $lib")
                ArduinoCli.installLibrary(lib, onLine = log)
            }

            // 编译
            BuildSession.setStatus(BuildStatus.COMPILING, "编译 ${board.name}")
            log("开始编译 (FQBN: ${board.fqbn}) ...")
            val outputDir = File(TermuxEnv.workspaceDir, "build_output")
            val buildStart = System.currentTimeMillis()
            val code = ArduinoCli.compile(board.fqbn, sketch.sketchDir, outputDir, onLine = log)

            if (code != 0) {
                log("编译失败，退出码 $code。请查看上方完整错误信息。")
                finishFailed()
                return
            }

            val artifacts = collectArtifacts(outputDir, buildStart)
            if (artifacts.isEmpty()) {
                log("编译成功，但未找到 .bin/.hex 产物。")
            } else {
                log("编译成功，产出 ${artifacts.size} 个二进制文件:")
                artifacts.forEach { log("  - ${File(it).name}") }
            }
            BuildSession.setOutput(outputDir.absolutePath, artifacts)
            BuildSession.setStatus(BuildStatus.SUCCESS, "编译成功")
            notificationManager?.notify(NOTIF_ID, buildNotification("编译成功", "产物已就绪，可导出到指定位置"))
        } catch (t: Throwable) {
            log("构建异常: ${t.message}")
            finishFailed()
            return
        } finally {
            stopForegroundSafely()
            stopSelf()
        }
    }

    private fun copyUploadToCache(uri: Uri): File {
        val dest = File(cacheDir, "upload.zip")
        contentResolver.openInputStream(uri)?.use { input ->
            FileOutputStream(dest).use { out -> input.copyTo(out) }
        }
        return dest
    }

    /**
     * 收集编译产物。多数平台会把 .bin/.hex/.elf 写到 [outputDir]（--output-dir）；
     * 但 Realtek AmebaD 等平台把最终 .bin（如 km0_km4_image2.bin）写入芯片工具链目录
     * （arduino15/packages/.../tools/...），此时回退扫描 packages 下本次新生成的固件文件。
     */
    private fun collectArtifacts(outputDir: File, buildStart: Long): List<String> {
        val firmwareExts = setOf("bin", "hex", "elf", "img", "uf2", "axf")
        val result = linkedSetOf<String>()
        outputDir.walkTopDown()
            .filter { it.isFile && it.extension.lowercase() in firmwareExts }
            .forEach { result += it.absolutePath }

        if (result.isEmpty()) {
            val packagesDir = File(TermuxEnv.dataDir, "packages")
            if (packagesDir.exists()) {
                val since = buildStart - 60_000L
                packagesDir.walkTopDown()
                    .filter { it.isFile && it.extension.lowercase() in firmwareExts }
                    .filter { it.lastModified() >= since }
                    .forEach { result += it.absolutePath }
            }
        }
        return result.sorted()
    }

    private fun finishFailed() {
        BuildSession.setStatus(BuildStatus.FAILED, "构建失败")
        notificationManager?.notify(NOTIF_ID, buildNotification("构建失败", "请回看日志中的错误信息"))
    }

    private fun stopForegroundSafely() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
    }

    private fun stopSelfAndReturn(): Int {
        stopSelf()
        return START_NOT_STICKY
    }

    private fun createChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "构建进度",
            NotificationManager.IMPORTANCE_LOW,
        ).apply { description = "Arduino 项目构建进度" }
        notificationManager?.createNotificationChannel(channel)
    }

    private fun buildNotification(title: String, text: String): android.app.Notification {
        val pendingIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(text)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(pendingIntent)
            .build()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }
}