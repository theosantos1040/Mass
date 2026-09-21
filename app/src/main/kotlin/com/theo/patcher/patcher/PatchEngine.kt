// patcher/PatchEngine.kt — orchestrates all patch strategies for a target APK
package com.theo.patcher.patcher

import android.content.Context
import com.theo.patcher.model.AppInfo
import com.theo.patcher.model.PatchResult
import com.theo.patcher.scanner.IAPDetector
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.zip.ZipFile

object PatchEngine {

    suspend fun patch(
        context: Context,
        app: AppInfo,
        onLog: (String) -> Unit = {}
    ): PatchResult = withContext(Dispatchers.IO) {
        val log = StringBuilder()
        val appliedStrategies = mutableListOf<String>()

        fun emit(msg: String) { log.appendLine(msg); onLog(msg) }

        emit("=== Theo Patcher — starting patch ===")
        emit("Target: ${app.appName} (${app.packageName})")
        emit("APK: ${app.apkPath}")

        try {
            val sourceApk = File(app.apkPath)
            val workDir = File(context.cacheDir, "theo_work_${app.packageName}").also {
                it.deleteRecursively(); it.mkdirs()
            }

            emit("\n[1/5] Detecting IAP patterns...")
            val purchases = if (app.detectedPurchases.isNotEmpty()) app.detectedPurchases
                            else IAPDetector.detect(sourceApk)
            emit("Found ${purchases.size} IAP pattern(s):")
            purchases.forEach { p -> emit("  • ${p.methodName} in ${p.className} [${p.validationMethod}]") }

            emit("\n[2/5] Patching DEX binaries...")
            val patchedEntries = mutableListOf<ApkBuilder.PatchedEntry>()

            ZipFile(sourceApk).use { zip ->
                zip.entries().asSequence()
                    .filter { it.name.matches(Regex("classes\\d*\\.dex")) }
                    .forEach { entry ->
                        val original = zip.getInputStream(entry).readBytes()
                        val (patched, report) = DexPatcher.patch(original, purchases)
                        if (report.bytesModified > 0) {
                            patchedEntries += ApkBuilder.PatchedEntry(entry.name, patched)
                            emit("  ✓ ${entry.name}: patched ${report.patchedMethods.size} method(s)")
                            report.patchedMethods.forEach { m -> emit("    - $m") }
                            appliedStrategies += "DEX:${entry.name}"
                        } else {
                            emit("  ~ ${entry.name}: no patchable patterns found")
                        }
                    }

                emit("\n[3/5] Patching AndroidManifest.xml...")
                val manifestEntry = zip.getEntry("AndroidManifest.xml")
                if (manifestEntry != null) {
                    val original = zip.getInputStream(manifestEntry).readBytes()
                    val patched = ManifestPatcher.patch(original)
                    patchedEntries += ApkBuilder.PatchedEntry("AndroidManifest.xml", patched)
                    emit("  ✓ Manifest: billing/license permissions removed")
                    appliedStrategies += "Manifest"
                }
            }

            emit("\n[4/5] Rebuilding APK...")
            val rebuiltApk = File(workDir, "rebuilt.apk")
            ApkBuilder.rebuild(sourceApk, rebuiltApk, patchedEntries)
            emit("  ✓ APK rebuilt: ${rebuiltApk.length() / 1024} KB")
            appliedStrategies += "Rebuild"

            emit("\n[5/5] Signing APK...")
            val signedApk = try {
                val s = ApkSigner.sign(rebuiltApk, context)
                emit("  ✓ Signed: ${s.name}")
                appliedStrategies += "Sign"
                s
            } catch (e: Exception) {
                emit("  ! Signing failed: ${e.message} — using unsigned APK")
                rebuiltApk
            }

            val outputDir = File(context.getExternalFilesDir(null), "theo_patched")
            outputDir.mkdirs()
            val outputApk = File(outputDir, "${app.packageName}_patched.apk")
            signedApk.copyTo(outputApk, overwrite = true)

            emit("\n=== DONE ===")
            emit("Output: ${outputApk.absolutePath}")

            PatchResult(true, outputApk, appliedStrategies, log.toString())

        } catch (e: Exception) {
            emit("\n[ERROR] ${e.javaClass.simpleName}: ${e.message}")
            PatchResult(false, null, appliedStrategies, log.toString(), e.message)
        }
    }
}
