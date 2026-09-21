package com.theo.patcher.patcher

import android.content.Context
import com.theo.patcher.model.AppInfo
import com.theo.patcher.model.PatchResult
import com.theo.patcher.scanner.AdDetector
import com.theo.patcher.scanner.IAPDetector
import com.theo.patcher.scanner.LicenseDetector
import com.theo.patcher.scanner.ProtectionDetector
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.zip.ZipFile

object PatchEngine {

    data class PatchOptions(
        val patchIAP: Boolean = true,
        val patchAds: Boolean = true,
        val patchLicense: Boolean = true,
        val patchProtection: Boolean = true,
        val patchBilling: Boolean = true
    )

    suspend fun patch(
        context: Context,
        app: AppInfo,
        options: PatchOptions = PatchOptions(),
        onLog: (String) -> Unit = {}
    ): PatchResult = withContext(Dispatchers.IO) {
        val log = StringBuilder()
        val appliedStrategies = mutableListOf<String>()

        fun emit(msg: String) { log.appendLine(msg); onLog(msg) }

        emit("======================================")
        emit("  THEO PATCHER v0.2 — Full Bypass")
        emit("======================================")
        emit("Target: ${app.appName} (${app.packageName})")
        emit("APK: ${app.apkPath}")
        emit("Opcoes: IAP=${options.patchIAP} Ads=${options.patchAds} License=${options.patchLicense} Protection=${options.patchProtection} Billing=${options.patchBilling}")

        try {
            val sourceApk = File(app.apkPath)
            val workDir = File(context.cacheDir, "theo_work_${app.packageName}").also {
                it.deleteRecursively(); it.mkdirs()
            }

            emit("\n[1/8] Escaneando padroes...")
            val purchases = if (app.detectedPurchases.isNotEmpty()) app.detectedPurchases else IAPDetector.detect(sourceApk)
            val ads = if (app.detectedAds.isNotEmpty()) app.detectedAds else AdDetector.detect(sourceApk)
            val license = app.detectedLicense ?: LicenseDetector.detect(sourceApk)
            val protections = if (app.detectedProtections.isNotEmpty()) app.detectedProtections else ProtectionDetector.detect(sourceApk)

            emit("  IAP: ${purchases.size} padrao(oes)")
            emit("  Ads: ${ads.size} SDK(s) detectado(s)")
            if (ads.isNotEmpty()) ads.forEach { emit("    - ${it.sdkName}") }
            emit("  Licenca: ${if (license != null) license.type.name else "Nenhuma"}")
            emit("  Protecoes: ${protections.size} tipo(s)")
            if (protections.isNotEmpty()) protections.forEach { emit("    - ${it.type.name}: ${it.detail}") }

            val patchedEntries = mutableListOf<ApkBuilder.PatchedEntry>()

            ZipFile(sourceApk).use { zip ->
                val dexEntries = zip.entries().asSequence()
                    .filter { it.name.matches(Regex("classes\\d*\\.dex")) }
                    .toList()

                for (entry in dexEntries) {
                    var dexBytes = zip.getInputStream(entry).readBytes()
                    var totalPatched = 0

                    if (options.patchIAP) {
                        emit("\n[2/8] Patching DEX - IAP bypass (${entry.name})...")
                        val (patched, report) = DexPatcher.patch(dexBytes, purchases)
                        if (report.bytesModified > 0) {
                            dexBytes = patched
                            totalPatched += report.bytesModified
                            emit("  + ${entry.name} IAP: ${report.patchedMethods.size} metodo(s)")
                            report.patchedMethods.forEach { m -> emit("    - $m") }
                            appliedStrategies += "IAP:${entry.name}"
                        } else {
                            emit("  ~ ${entry.name}: sem padroes IAP")
                        }
                    }

                    if (options.patchBilling) {
                        emit("\n[3/8] Patching DEX - billing bypass (${entry.name})...")
                        val (patched, report) = BillingPatcher.patch(dexBytes)
                        if (report.bytesModified > 0) {
                            dexBytes = patched
                            totalPatched += report.bytesModified
                            emit("  + ${entry.name} Billing: ${report.patchedMethods.size} metodo(s)")
                            report.patchedMethods.forEach { m -> emit("    - $m") }
                            appliedStrategies += "Billing:${entry.name}"
                        } else {
                            emit("  ~ ${entry.name}: sem padroes Billing")
                        }
                    }

                    if (options.patchAds && ads.isNotEmpty()) {
                        emit("\n[4/8] Patching DEX - remocao de anuncios (${entry.name})...")
                        val (patched, report) = AdPatcher.patch(dexBytes)
                        if (report.bytesModified > 0) {
                            dexBytes = patched
                            totalPatched += report.bytesModified
                            emit("  + ${entry.name} Ads: ${report.patchedMethods.size} metodo(s)")
                            report.patchedMethods.forEach { m -> emit("    - $m") }
                            appliedStrategies += "Ads:${entry.name}"
                        } else {
                            emit("  ~ ${entry.name}: sem metodos de ad patchaveis")
                        }
                    }

                    if (options.patchLicense && license != null) {
                        emit("\n[5/8] Patching DEX - bypass de licenca (${entry.name})...")
                        val (patched, report) = LicensePatcher.patch(dexBytes)
                        if (report.bytesModified > 0) {
                            dexBytes = patched
                            totalPatched += report.bytesModified
                            emit("  + ${entry.name} License: ${report.patchedMethods.size} metodo(s)")
                            report.patchedMethods.forEach { m -> emit("    - $m") }
                            appliedStrategies += "License:${entry.name}"
                        } else {
                            emit("  ~ ${entry.name}: sem padroes de licenca")
                        }
                    }

                    if (options.patchProtection && protections.isNotEmpty()) {
                        emit("\n[6/8] Patching DEX - bypass de protecao (${entry.name})...")
                        val (patched, report) = ProtectionPatcher.patch(dexBytes)
                        if (report.bytesModified > 0) {
                            dexBytes = patched
                            totalPatched += report.bytesModified
                            emit("  + ${entry.name} Protection: ${report.patchedMethods.size} metodo(s)")
                            report.patchedMethods.forEach { m -> emit("    - $m") }
                            appliedStrategies += "Protection:${entry.name}"
                        } else {
                            emit("  ~ ${entry.name}: sem protecoes patchaveis")
                        }
                    }

                    if (totalPatched > 0) {
                        patchedEntries += ApkBuilder.PatchedEntry(entry.name, dexBytes)
                    }
                }

                emit("\n[7/8] Patching AndroidManifest.xml...")
                val manifestEntry = zip.getEntry("AndroidManifest.xml")
                if (manifestEntry != null) {
                    val original = zip.getInputStream(manifestEntry).readBytes()
                    val patched = ManifestPatcher.patch(
                        original,
                        removeAds = options.patchAds,
                        removeLicense = options.patchLicense
                    )
                    patchedEntries += ApkBuilder.PatchedEntry("AndroidManifest.xml", patched)
                    emit("  + Manifest patchado")
                    appliedStrategies += "Manifest"
                }
            }

            emit("\n[8/8] Rebuild + Assinatura (v1+v2)...")
            val rebuiltApk = File(workDir, "rebuilt.apk")
            ApkBuilder.rebuild(sourceApk, rebuiltApk, patchedEntries)
            emit("  + APK rebuilt: ${rebuiltApk.length() / 1024} KB")
            appliedStrategies += "Rebuild"

            val signedApk = try {
                val s = ApkSigner.sign(rebuiltApk, context)
                emit("  + Assinado v1+v2: ${s.name}")
                appliedStrategies += "Sign:v1+v2"
                s
            } catch (e: Exception) {
                emit("  ! Assinatura falhou: ${e.message}")
                rebuiltApk
            }

            val outputDir = File(context.getExternalFilesDir(null), "theo_patched")
            outputDir.mkdirs()
            val outputApk = File(outputDir, "${app.packageName}_patched.apk")
            signedApk.copyTo(outputApk, overwrite = true)

            emit("\n======================================")
            emit("  PATCH CONCLUIDO COM SUCESSO!")
            emit("======================================")
            emit("Output: ${outputApk.absolutePath}")
            emit("Estrategias: ${appliedStrategies.joinToString(", ")}")

            PatchResult(true, outputApk, appliedStrategies, log.toString())

        } catch (e: Exception) {
            emit("\n[ERRO] ${e.javaClass.simpleName}: ${e.message}")
            PatchResult(false, null, appliedStrategies, log.toString(), e.message)
        }
    }
}
