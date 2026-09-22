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

    /**
     * Copy an installed-app APK into our cache. Direct File access to
     * /data/app is blocked on modern Android for third-party apps, so we try
     * several strategies and report exactly what fails.
     */
    private fun copyToCache(src: File, dest: File, emit: (String) -> Unit): File? {
        emit("    existe=${runCatching { src.exists() }.getOrDefault(false)} " +
             "le=${runCatching { src.canRead() }.getOrDefault(false)} " +
             "tam=${runCatching { src.length() }.getOrDefault(0L)}")

        // Strategy 1: plain stream copy (works when the file is world-readable)
        try {
            src.inputStream().use { input ->
                dest.outputStream().use { output -> input.copyTo(output) }
            }
            if (dest.length() > 0) {
                emit("    → copiado (stream): ${dest.length() / 1024} KB")
                return dest
            }
        } catch (e: Exception) {
            emit("    ! stream falhou: ${e.javaClass.simpleName}: ${e.message}")
        }

        // Strategy 2: NIO copy (different syscall path, sometimes succeeds)
        try {
            java.nio.file.Files.copy(
                src.toPath(), dest.toPath(),
                java.nio.file.StandardCopyOption.REPLACE_EXISTING
            )
            if (dest.length() > 0) {
                emit("    → copiado (nio): ${dest.length() / 1024} KB")
                return dest
            }
        } catch (e: Exception) {
            emit("    ! nio falhou: ${e.javaClass.simpleName}: ${e.message}")
        }

        return null
    }

    data class PatchOptions(
        val patchIAP: Boolean = false,
        val patchAds: Boolean = false,
        val patchLicense: Boolean = false,
        val patchProtection: Boolean = false,
        // Byte-search DEX patching corrupts the classes.dex and crashes the app
        // on launch. Off by default until replaced with a real DEX parser.
        val patchBilling: Boolean = false
    ) {
        val anyPatch: Boolean get() = patchIAP || patchAds || patchLicense || patchProtection || patchBilling
    }

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
        emit("  THEO PATCHER v1.1 — resign limpo")
        emit("======================================")
        emit("Target: ${app.appName} (${app.packageName})")
        emit("APK: ${app.apkPath}")
        emit("Opcoes: IAP=${options.patchIAP} Ads=${options.patchAds} License=${options.patchLicense} Protection=${options.patchProtection} Billing=${options.patchBilling}")

        try {
            val workDir = File(context.cacheDir, "theo_work_${app.packageName}").also {
                it.deleteRecursively(); it.mkdirs()
            }

            // We CANNOT operate on /data/app/.../base.apk directly — on Android 13+
            // (and hardened OEMs like Samsung) the sandbox/SELinux blocks reading
            // another package's APK by path. Resolve fresh from PackageManager and
            // copy everything into our own cache, where we have full access.
            emit("\n[0/8] Preparando fonte (copiando pra cache)...")
            val pm = context.packageManager
            val ai = runCatching { pm.getApplicationInfo(app.packageName, 0) }.getOrNull()
            val basePath = ai?.publicSourceDir ?: ai?.sourceDir ?: app.apkPath
            val splitPaths = (ai?.splitSourceDirs?.toList()
                ?: app.splitApkPaths).filter { it.isNotEmpty() }

            emit("  base: $basePath")
            val sourceApk = copyToCache(File(basePath), File(workDir, "base.apk"), ::emit)
                ?: throw RuntimeException(
                    "Nao consigo ler o APK instalado (bloqueio do Android/Samsung). " +
                    "Baixe o APK do app (ex: APKPure/APKMirror) e use 'Escolher APK' em vez da lista de apps."
                )

            val splits = splitPaths.mapIndexedNotNull { i, p ->
                emit("  split[$i]: $p")
                copyToCache(File(p), File(workDir, "split_$i.apk"), ::emit)
            }

            if (splits.isNotEmpty()) {
                emit("⚠ Split APK: ${splits.size} splits copiados")
            } else {
                emit("APK único (sem splits)")
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
                    val originalDex = zip.getInputStream(entry).readBytes()
                    var dexBytes = originalDex
                    var totalPatched = 0

                    fun tryPatch(label: String, doPatch: (ByteArray) -> Pair<ByteArray, Int>?) {
                        val result = doPatch(dexBytes) ?: return
                        val (patched, bytesModified) = result
                        if (bytesModified <= 0) return
                        if (!DexUtil.looksValid(patched)) {
                            emit("  ! $label: patch corromperia DEX, descartado")
                            return
                        }
                        dexBytes = patched
                        totalPatched += bytesModified
                        appliedStrategies += "$label:${entry.name}"
                    }

                    if (options.patchIAP) {
                        emit("\n[2/8] Patching DEX - IAP bypass (${entry.name})...")
                        tryPatch("IAP") {
                            val (p, r) = DexPatcher.patch(it, purchases)
                            if (r.bytesModified > 0) {
                                emit("  + ${entry.name} IAP: ${r.patchedMethods.size} metodo(s)")
                                p to r.bytesModified
                            } else { emit("  ~ ${entry.name}: sem padroes IAP"); null }
                        }
                    }

                    if (options.patchBilling) {
                        emit("\n[3/8] Patching DEX - billing bypass (${entry.name})...")
                        tryPatch("Billing") {
                            val (p, r) = BillingPatcher.patch(it)
                            if (r.bytesModified > 0) {
                                emit("  + ${entry.name} Billing: ${r.patchedMethods.size} metodo(s)")
                                p to r.bytesModified
                            } else { emit("  ~ ${entry.name}: sem padroes Billing"); null }
                        }
                    }

                    if (options.patchAds && ads.isNotEmpty()) {
                        emit("\n[4/8] Patching DEX - remocao de anuncios (${entry.name})...")
                        tryPatch("Ads") {
                            val (p, r) = AdPatcher.patch(it)
                            if (r.bytesModified > 0) {
                                emit("  + ${entry.name} Ads: ${r.patchedMethods.size} metodo(s)")
                                p to r.bytesModified
                            } else { emit("  ~ ${entry.name}: sem metodos de ad patchaveis"); null }
                        }
                    }

                    if (options.patchLicense && license != null) {
                        emit("\n[5/8] Patching DEX - bypass de licenca (${entry.name})...")
                        tryPatch("License") {
                            val (p, r) = LicensePatcher.patch(it)
                            if (r.bytesModified > 0) {
                                emit("  + ${entry.name} License: ${r.patchedMethods.size} metodo(s)")
                                p to r.bytesModified
                            } else { emit("  ~ ${entry.name}: sem padroes de licenca"); null }
                        }
                    }

                    if (options.patchProtection && protections.isNotEmpty()) {
                        emit("\n[6/8] Patching DEX - bypass de protecao (${entry.name})...")
                        tryPatch("Protection") {
                            val (p, r) = ProtectionPatcher.patch(it)
                            if (r.bytesModified > 0) {
                                emit("  + ${entry.name} Protection: ${r.patchedMethods.size} metodo(s)")
                                p to r.bytesModified
                            } else { emit("  ~ ${entry.name}: sem protecoes patchaveis"); null }
                        }
                    }

                    if (totalPatched > 0) {
                        patchedEntries += ApkBuilder.PatchedEntry(entry.name, dexBytes)
                    }
                }

                if (options.patchAds || options.patchLicense) {
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
                } else {
                    emit("\n[7/8] Manifest: skip (todos os patches desativados)")
                }
            }

            val rebuiltApk: File
            if (patchedEntries.isEmpty()) {
                emit("\n[8/8] Sem patches — pulando rebuild, apenas re-assinando")
                rebuiltApk = sourceApk
                appliedStrategies += "PureResign"
            } else {
                emit("\n[8/8] Rebuild + Assinatura (v1+v2)...")
                rebuiltApk = File(workDir, "rebuilt.apk")
                ApkBuilder.rebuild(sourceApk, rebuiltApk, patchedEntries)
                emit("  + APK rebuilt: ${rebuiltApk.length() / 1024} KB")
                appliedStrategies += "Rebuild"
            }

            val signedApk = try {
                val s = ApkSigner.sign(rebuiltApk, context) { line -> emit(line) }
                emit("  + Assinado: ${s.name}")
                appliedStrategies += "Sign"
                s
            } catch (e: Exception) {
                // NEVER install unsigned — that produces INSTALL_PARSE_FAILED_NO_CERTIFICATES.
                emit("  ! ASSINATURA FALHOU (abortando): ${e.javaClass.simpleName}: ${e.message}")
                e.stackTrace.take(6).forEach { emit("    at $it") }
                throw e
            }

            val outputDir = File(context.getExternalFilesDir(null), "theo_patched")
            outputDir.mkdirs()
            val outputApk = File(outputDir, "${app.packageName}_patched.apk")
            signedApk.copyTo(outputApk, overwrite = true)

            // Re-sign each split with the SAME key as base — session install requires
            // matching signatures across all APKs in the session.
            val signedSplits = mutableListOf<File>()
            if (splits.isNotEmpty()) {
                emit("\nRe-assinando ${splits.size} splits com a mesma chave...")
                for (split in splits) {
                    try {
                        val signed = ApkSigner.sign(split, context) { }
                        val out = File(outputDir, "${app.packageName}_${split.name}")
                        signed.copyTo(out, overwrite = true)
                        signedSplits += out
                        emit("  + ${split.name}: ${out.length() / 1024} KB")
                    } catch (e: Exception) {
                        emit("  ! ${split.name} falhou: ${e.message}")
                    }
                }
            }

            emit("\n======================================")
            emit("  PATCH CONCLUIDO COM SUCESSO!")
            emit("======================================")
            emit("Output: ${outputApk.absolutePath}")
            if (signedSplits.isNotEmpty()) emit("Splits: ${signedSplits.size} re-assinados")
            emit("Estrategias: ${appliedStrategies.joinToString(", ")}")

            PatchResult(true, outputApk, appliedStrategies, log.toString(), splitApks = signedSplits)

        } catch (e: Exception) {
            emit("\n[ERRO] ${e.javaClass.simpleName}: ${e.message}")
            PatchResult(false, null, appliedStrategies, log.toString(), e.message)
        }
    }
}
