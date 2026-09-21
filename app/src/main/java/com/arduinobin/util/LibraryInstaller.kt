package com.arduinobin.util

import android.content.Context
import android.net.Uri
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipFile

/**
 * 从用户选择的 zip 安装一个第三方/私有 Arduino 库到 arduino-cli 的用户库目录
 * （`<directories.user>/libraries/<库名>`），构建时 arduino-cli 会自动解析并链接其中的库。
 */
object LibraryInstaller {

    data class Result(val libraryName: String, val message: String)

    fun install(context: Context, uri: Uri, librariesDir: File): Result {
        librariesDir.mkdirs()
        return try {
            val zipFile = copyToCache(context, uri)
            val extractRoot = File(context.cacheDir, "lib_extract_${System.currentTimeMillis()}")
            extractRoot.mkdirs()
            unzip(zipFile, extractRoot)

            val libRoot = locateLibraryRoot(extractRoot)
                ?: return Result("", "所选 zip 不是有效的 Arduino 库（未找到 library.properties）")

            val name = ProjectExtractor.sanitize(
                readLibraryName(libRoot) ?: libRoot.name
            )

            val dest = File(librariesDir, name)
            if (dest.exists()) dest.deleteRecursively()
            libRoot.copyRecursively(dest)

            extractRoot.deleteRecursively()
            zipFile.delete()
            Result(name, "已安装库「$name」")
        } catch (e: Exception) {
            Result("", "库安装失败：${e.message}")
        }
    }

    private fun copyToCache(context: Context, uri: Uri): File {
        val dest = File(context.cacheDir, "lib_upload_${System.currentTimeMillis()}.zip")
        context.contentResolver.openInputStream(uri)?.use { input ->
            FileOutputStream(dest).use { out -> input.copyTo(out) }
        } ?: throw IllegalStateException("无法读取所选文件")
        return dest
    }

    /** 解压 zip 到 dest，带 zip-slip 防护。 */
    private fun unzip(zip: File, dest: File) {
        val destCanonical = dest.canonicalPath
        ZipFile(zip).use { zf ->
            val entries = zf.entries()
            while (entries.hasMoreElements()) {
                val entry = entries.nextElement()
                val target = File(dest, entry.name).canonicalFile
                if (!target.path.startsWith(destCanonical + File.separator)) continue
                if (entry.isDirectory) {
                    target.mkdirs()
                    continue
                }
                target.parentFile?.mkdirs()
                zf.getInputStream(entry).use { input ->
                    FileOutputStream(target).use { out -> input.copyTo(out) }
                }
            }
        }
    }

    /** 在解压结果中定位包含 library.properties 的库根目录（BFS 取最浅一层的）。 */
    private fun locateLibraryRoot(root: File): File? {
        if (File(root, "library.properties").exists()) return root
        val queue = ArrayDeque<File>()
        queue.add(root)
        while (queue.isNotEmpty()) {
            val dir = queue.removeFirst()
            if (File(dir, "library.properties").exists()) return dir
            dir.listFiles()?.filter { it.isDirectory }?.forEach { queue.addLast(it) }
        }
        return null
    }

    private fun readLibraryName(dir: File): String? {
        val props = File(dir, "library.properties")
        if (!props.exists()) return null
        return props.readLines()
            .firstOrNull { it.startsWith("name=") }
            ?.substringAfter("name=")
            ?.trim()
            ?.ifBlank { null }
    }
}