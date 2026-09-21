package com.arduinobin.util

import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipFile

/**
 * 解压用户上传的 Arduino 项目 zip，并定位可被 arduino-cli 编译的 sketch 目录
 * （arduino-cli 要求目录名与主 .ino 文件名一致）。
 */
object ProjectExtractor {

    data class Sketch(val sketchDir: File, val mainIno: File, val projectName: String)

    fun sanitize(name: String): String {
        val cleaned = name.trim()
            .replace(Regex("[^A-Za-z0-9._-]"), "_")
            .trim('_')
        return cleaned.ifEmpty { "sketch" }
    }

    /** 解压 zip 到 parentDir/项目名，返回定位到的 sketch。 */
    fun extract(zip: File, parentDir: File, projectName: String): Sketch {
        val base = File(parentDir, sanitize(projectName))
        base.mkdirs()

        ZipFile(zip).use { zf ->
            val entries = zf.entries()
            while (entries.hasMoreElements()) {
                val entry = entries.nextElement()
                // 防止 zip-slip
                val entryFile = File(base, entry.name).canonicalFile
                if (!entryFile.path.startsWith(base.canonicalPath + File.separator)) continue
                if (entry.isDirectory) {
                    entryFile.mkdirs()
                    continue
                }
                entryFile.parentFile?.mkdirs()
                if (entryFile.exists()) entryFile.delete()
                zf.getInputStream(entry).use { input ->
                    FileOutputStream(entryFile).use { out -> input.copyTo(out) }
                }
            }
        }

        val allIno = base.walkTopDown()
            .filter { it.isFile && it.extension.equals("ino", ignoreCase = true) }
            .toList()

        if (allIno.isEmpty()) {
            throw IllegalArgumentException("zip 中未找到任何 .ino 文件，请确认这是 Arduino 源码项目。")
        }

        // 优先选择与所在目录同名的 .ino 作为主文件
        val main = allIno.firstOrNull {
            it.parentFile?.name?.equals(it.nameWithoutExtension, ignoreCase = true) == true
        } ?: allIno.first()

        return Sketch(sketchDir = main.parentFile, mainIno = main, projectName = projectName)
    }
}