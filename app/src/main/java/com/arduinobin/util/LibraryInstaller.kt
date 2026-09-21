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

    /** 一个已安装库的元信息（来自 library.properties）。 */
    data class LibraryInfo(
        val dirName: String,
        val name: String,
        val version: String,
        val author: String,
        val sentence: String,
    )

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
        return readProps(props)["name"]
    }

    /** 读取 library.properties 为键值对。 */
    private fun readProps(props: File): Map<String, String> {
        val map = mutableMapOf<String, String>()
        props.forEachLine { line ->
            val idx = line.indexOf('=')
            if (idx > 0) {
                val key = line.substring(0, idx).trim()
                val value = line.substring(idx + 1).trim()
                if (key.isNotEmpty()) map[key] = value
            }
        }
        return map
    }

    /** 列出库目录下所有已安装的库。 */
    fun listInstalled(librariesDir: File): List<LibraryInfo> {
        if (!librariesDir.exists()) return emptyList()
        return librariesDir.listFiles { f -> f.isDirectory }
            .orEmpty()
            .mapNotNull { dir ->
                val props = File(dir, "library.properties")
                if (!props.exists()) return@mapNotNull null
                val p = readProps(props)
                LibraryInfo(
                    dirName = dir.name,
                    name = p["name"] ?: dir.name,
                    version = p["version"] ?: "",
                    author = p["author"] ?: "",
                    sentence = p["sentence"] ?: p["paragraph"] ?: "",
                )
            }
            .sortedBy { it.name.lowercase() }
    }

    /** 按目录名删除一个已安装的库。 */
    fun delete(dirName: String, librariesDir: File): Boolean {
        val dir = File(librariesDir, dirName)
        return dir.exists() && dir.deleteRecursively()
    }
}