package com.arduinobin.termux

import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.zip.GZIPInputStream

/**
 * 极简 tar.gz 解压器：仅用于把 arduino-cli 的 Linux ARM64 发布包（内部为单个
 * arduino-cli 二进制 + LICENSE）提取出来，避免依赖设备上是否存在 GNU tar。
 */
object TarGz {

    /** 解压 gz 内的所有文件到 [dest]，返回解压出的文件数量。 */
    fun extract(gzFile: File, dest: File): Int {
        dest.mkdirs()
        var count = 0
        GZIPInputStream(FileInputStream(gzFile)).use { gis ->
            val buf = ByteArray(8192)
            while (true) {
                val header = readFully(gis, 512) ?: break
                if (header.all { it == 0.toByte() }) break
                val name = parseString(header, 0, 100)
                val size = parseOctal(header, 124, 12)
                val type = header[156].toInt().toChar()
                when {
                    size < 0 -> {}
                    type == 'L' -> {
                        // GNU long name：下一块是文件名
                        val nameBytes = readFully(gis, 512) ?: break
                        val longName = parseString(nameBytes, 0, 512)
                        // 之后紧跟真正的 header，简化处理：假定长名后是普通文件
                        val realHeader = readFully(gis, 512) ?: break
                        if (realHeader.all { it == 0.toByte() }) break
                        val realSize = parseOctal(realHeader, 124, 12)
                        val realType = realHeader[156].toInt().toChar()
                        writeEntry(gis, dest, longName, realSize, realType)
                        count++
                    }
                    type == '5' || name.endsWith("/") -> {
                        // 目录：跳过数据
                        skipData(gis, size)
                    }
                    type == '0' || type == '\u0000' -> {
                        writeEntry(gis, dest, name, size, type)
                        count++
                    }
                    else -> skipData(gis, size)
                }
            }
        }
        return count
    }

    private fun writeEntry(gis: GZIPInputStream, dest: File, name: String, size: Int, type: Char) {
        val clean = name.removePrefix("./").trim()
        if (clean.isEmpty()) { skipData(gis, size); return }
        val out = File(dest, clean)
        out.parentFile?.mkdirs()
        FileOutputStream(out).use { fos ->
            streamFixed(gis, fos, size)
        }
        if (type != '5') out.setExecutable(true, false)
    }

    private fun skipData(gis: GZIPInputStream, size: Int) {
        if (size <= 0) return
        val padding = (512 - (size % 512)) % 512
        streamFixed(gis, null, size)
        streamFixed(gis, null, padding)
    }

    private fun streamFixed(gis: GZIPInputStream, os: FileOutputStream?, size: Int) {
        val buf = ByteArray(8192)
        var remaining = size
        while (remaining > 0) {
            val n = gis.read(buf, 0, minOf(buf.size, remaining))
            if (n < 0) break
            os?.write(buf, 0, n)
            remaining -= n
        }
    }

    private fun readFully(input: java.io.InputStream, len: Int): ByteArray? {
        val out = ByteArray(len)
        var off = 0
        while (off < len) {
            val n = input.read(out, off, len - off)
            if (n < 0) return if (off == 0) null else out.copyOf(off)
            off += n
        }
        return out
    }

    private fun parseString(bytes: ByteArray, off: Int, len: Int): String {
        var end = off
        while (end < off + len && bytes[end] != 0.toByte()) end++
        return String(bytes, off, end - off, Charsets.UTF_8)
    }

    private fun parseOctal(bytes: ByteArray, off: Int, len: Int): Int {
        var end = off
        while (end < off + len && (bytes[end] == 0.toByte() || bytes[end] == ' '.code.toByte())) end++
        val s = String(bytes, off, end - off, Charsets.US_ASCII).trim()
        if (s.isEmpty()) return 0
        return s.toLongOrNull(8)?.toInt() ?: 0
    }
}