package com.mass.patcher.service

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.apache.commons.io.IOUtils
import java.io.File
import java.util.jar.JarEntry
import java.util.jar.JarFile
import java.util.jar.JarOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipFile

object ApkPatcherService {

    suspend fun patchApk(inputFile: File, patchName: String): File = withContext(Dispatchers.Default) {
        val outputDir = File(inputFile.parentFile, "patched").apply { mkdirs() }
        val outputFile = File(outputDir, "patched_${System.currentTimeMillis()}.apk")

        try {
            modifyApk(inputFile, outputFile, patchName)
            outputFile
        } catch (e: Exception) {
            outputFile.delete()
            throw Exception("Erro ao patchar APK: ${e.message}")
        }
    }

    private fun modifyApk(inputFile: File, outputFile: File, patchName: String) {
        ZipFile(inputFile).use { zipFile ->
            JarOutputStream(outputFile.outputStream()).use { jarOut ->
                val entries = zipFile.entries().toList()

                entries.forEach { entry ->
                    val inputStream = zipFile.getInputStream(entry)
                    val newEntry = JarEntry(entry.name)
                    newEntry.time = entry.time

                    jarOut.putNextEntry(newEntry)

                    when {
                        entry.name.endsWith("resources.arsc") -> {
                            val data = inputStream.readBytes()
                            val modifiedData = modifyResources(data, patchName)
                            jarOut.write(modifiedData)
                        }
                        entry.name.endsWith("AndroidManifest.xml") -> {
                            val data = inputStream.readBytes()
                            val modifiedData = modifyManifest(data, patchName)
                            jarOut.write(modifiedData)
                        }
                        else -> {
                            IOUtils.copy(inputStream, jarOut)
                        }
                    }

                    jarOut.closeEntry()
                    inputStream.close()
                }
            }
        }
    }

    private fun modifyResources(data: ByteArray, patchName: String): ByteArray {
        // Adiciona metadata do patch aos resources
        val metadata = "[PATCHED_BY:$patchName]".toByteArray()
        return data + metadata
    }

    private fun modifyManifest(data: ByteArray, patchName: String): ByteArray {
        // Modifica o manifesto com comentário do patch
        val comment = "<!-- Patched by $patchName -->".toByteArray()
        return data + comment
    }
}
