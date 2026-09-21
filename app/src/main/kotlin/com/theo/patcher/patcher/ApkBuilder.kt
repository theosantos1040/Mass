package com.theo.patcher.patcher

import java.io.File
import java.io.FilterOutputStream
import java.io.OutputStream
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

object ApkBuilder {

    data class PatchedEntry(val name: String, val data: ByteArray)

    private class CountingOutputStream(out: OutputStream) : FilterOutputStream(out) {
        var bytesWritten = 0L; private set
        override fun write(b: Int) { super.write(b); bytesWritten++ }
        override fun write(b: ByteArray, off: Int, len: Int) { out.write(b, off, len); bytesWritten += len }
    }

    fun rebuild(
        sourceApk: File,
        outputApk: File,
        patchedEntries: List<PatchedEntry>
    ) {
        val patchMap = patchedEntries.associateBy { it.name }
        outputApk.parentFile?.mkdirs()

        val cos = CountingOutputStream(outputApk.outputStream().buffered())
        ZipOutputStream(cos).use { zout ->
            ZipFile(sourceApk).use { zin ->
                zin.entries().asSequence()
                    .filter { !it.name.startsWith("META-INF/") }
                    .forEach { entry ->
                        val data = patchMap[entry.name]?.data
                            ?: zin.getInputStream(entry).readBytes()

                        val stored = entry.method == ZipEntry.STORED

                        val outEntry = ZipEntry(entry.name)

                        if (stored) {
                            outEntry.method = ZipEntry.STORED
                            outEntry.size = data.size.toLong()
                            outEntry.compressedSize = data.size.toLong()
                            outEntry.crc = CRC32().also { it.update(data) }.value

                            // 4-byte align STORED entries (zipalign equivalent)
                            val nameLen = entry.name.toByteArray(Charsets.UTF_8).size
                            val unalignedDataStart = cos.bytesWritten + 30 + nameLen
                            val padding = ((4 - (unalignedDataStart % 4).toInt()) % 4)
                            if (padding > 0) outEntry.extra = ByteArray(padding)
                        } else {
                            outEntry.method = ZipEntry.DEFLATED
                        }

                        zout.putNextEntry(outEntry)
                        zout.write(data)
                        zout.closeEntry()
                    }
            }
        }
    }
}
