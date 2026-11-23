package com.theo.patcher.patcher

import java.io.File
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

object ApkBuilder {

    data class PatchedEntry(val name: String, val data: ByteArray)

    fun rebuild(
        sourceApk: File,
        outputApk: File,
        patchedEntries: List<PatchedEntry>
    ) {
        val patchMap = patchedEntries.associateBy { it.name }
        outputApk.parentFile?.mkdirs()

        ZipOutputStream(outputApk.outputStream().buffered()).use { zout ->
            ZipFile(sourceApk).use { zin ->
                zin.entries().asSequence()
                    .filter { !it.name.startsWith("META-INF/") }
                    .forEach { entry ->
                        val outEntry = ZipEntry(entry.name).apply {
                            method = if (entry.name.endsWith(".arsc") || entry.name.endsWith(".png"))
                                ZipEntry.STORED else ZipEntry.DEFLATED
                        }

                        val data = patchMap[entry.name]?.data
                            ?: zin.getInputStream(entry).readBytes()

                        if (outEntry.method == ZipEntry.STORED) {
                            outEntry.size = data.size.toLong()
                            outEntry.compressedSize = data.size.toLong()
                            val crc = CRC32().also { it.update(data) }.value
                            outEntry.crc = crc
                        }

                        zout.putNextEntry(outEntry)
                        zout.write(data)
                        zout.closeEntry()
                    }
            }
        }
    }
}
