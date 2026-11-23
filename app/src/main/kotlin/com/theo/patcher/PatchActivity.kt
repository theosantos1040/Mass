package com.theo.patcher

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.CheckBox
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import com.theo.patcher.model.AppInfo
import com.theo.patcher.patcher.PatchEngine
import com.theo.patcher.scanner.AdDetector
import com.theo.patcher.scanner.IAPDetector
import com.theo.patcher.scanner.LicenseDetector
import com.theo.patcher.scanner.ProtectionDetector
import android.content.pm.PackageInstaller
import com.theo.patcher.util.InstallReceiver
import com.theo.patcher.util.RootUtil
import com.theo.patcher.util.SessionInstaller
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class PatchActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_PACKAGE     = "pkg"
        const val EXTRA_APP_NAME    = "name"
        const val EXTRA_APK_PATH    = "apk"
        const val EXTRA_SPLIT_PATHS = "splits"
    }

    private lateinit var tvAppName: TextView
    private lateinit var tvScanResult: TextView
    private lateinit var cbIAP: CheckBox
    private lateinit var cbAds: CheckBox
    private lateinit var cbLicense: CheckBox
    private lateinit var cbProtection: CheckBox
    private lateinit var btnPatch: Button
    private lateinit var btnInstall: Button
    private lateinit var btnUninstall: Button
    private lateinit var tvLog: TextView
    private lateinit var scrollLog: ScrollView
    private lateinit var progressBar: android.widget.ProgressBar

    private var outputApk: File? = null
    private var splitApks: List<File> = emptyList()
    private lateinit var appInfo: AppInfo
    private var pkg = ""
    private var pendingInstall = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_patch)

        tvAppName    = findViewById(R.id.tvAppName)
        tvScanResult = findViewById(R.id.tvScanResult)
        cbIAP        = findViewById(R.id.cbIAP)
        cbAds        = findViewById(R.id.cbAds)
        cbLicense    = findViewById(R.id.cbLicense)
        cbProtection = findViewById(R.id.cbProtection)
        btnPatch     = findViewById(R.id.btnPatch)
        btnInstall   = findViewById(R.id.btnInstall)
        btnUninstall = findViewById(R.id.btnUninstall)
        tvLog        = findViewById(R.id.tvLog)
        scrollLog    = findViewById(R.id.scrollLog)
        progressBar  = findViewById(R.id.progressBar)

        tvLog.setTextIsSelectable(true)

        // Surface the raw PackageInstaller result on screen so the exact
        // INSTALL_FAILED_* reason is visible (and copyable) for diagnosis.
        InstallReceiver.onResult = { status, message ->
            runOnUiThread {
                appendLog("\n========== RESULTADO DO INSTALL ==========")
                when (status) {
                    PackageInstaller.STATUS_SUCCESS ->
                        appendLog("✓ INSTALADO COM SUCESSO!")
                    PackageInstaller.STATUS_PENDING_USER_ACTION ->
                        appendLog("Aguardando você confirmar no instalador do sistema...")
                    else -> {
                        appendLog("✗ FALHOU — status=$status")
                        appendLog("MOTIVO: $message")
                        appendLog("(segure o texto pra copiar e me mandar)")
                    }
                }
                appendLog("==========================================")
            }
        }

        pkg = intent.getStringExtra(EXTRA_PACKAGE) ?: return finish()
        val name    = intent.getStringExtra(EXTRA_APP_NAME) ?: pkg
        val apkPath = intent.getStringExtra(EXTRA_APK_PATH) ?: return finish()
        val splitPaths = intent.getStringArrayListExtra(EXTRA_SPLIT_PATHS) ?: arrayListOf()

        tvAppName.text = name
        btnInstall.visibility = View.GONE
        btnUninstall.visibility = if (isPackageInstalled(pkg)) View.VISIBLE else View.GONE

        appInfo = AppInfo(
            packageName = pkg,
            appName = name,
            icon = null,
            apkPath = apkPath,
            splitApkPaths = splitPaths,
            versionName = "?",
            apkSize = File(apkPath).length()
        )

        findViewById<TextView>(R.id.btnBack).setOnClickListener { finish() }

        lifecycleScope.launch {
            tvScanResult.text = "Escaneando..."
            progressBar.visibility = View.VISIBLE

            withContext(Dispatchers.IO) {
                val apkFile = File(apkPath)
                val purchases = IAPDetector.detect(apkFile)
                val ads = AdDetector.detect(apkFile)
                val license = LicenseDetector.detect(apkFile)
                val protections = ProtectionDetector.detect(apkFile)

                appInfo = appInfo.copy(
                    detectedPurchases = purchases,
                    detectedAds = ads,
                    detectedLicense = license,
                    detectedProtections = protections,
                    iapStatus = if (purchases.isEmpty()) AppInfo.IAPStatus.NO_IAP else AppInfo.IAPStatus.HAS_IAP,
                    adStatus = if (ads.isEmpty()) AppInfo.AdStatus.NO_ADS else AppInfo.AdStatus.HAS_ADS,
                    licenseStatus = if (license == null) AppInfo.LicenseStatus.NO_LICENSE else AppInfo.LicenseStatus.HAS_LICENSE,
                    protectionStatus = if (protections.isEmpty()) AppInfo.ProtectionStatus.NO_PROTECTION else AppInfo.ProtectionStatus.HAS_PROTECTION,
                    scanned = true
                )
            }

            progressBar.visibility = View.GONE
            updateScanUI(appInfo)
        }

        btnPatch.setOnClickListener { startPatch() }
        btnInstall.setOnClickListener { installOutput() }
        btnUninstall.setOnClickListener { uninstallOriginal() }
    }

    override fun onDestroy() {
        super.onDestroy()
        InstallReceiver.onResult = null
    }

    override fun onResume() {
        super.onResume()
        if (pendingInstall && !isPackageInstalled(pkg)) {
            pendingInstall = false
            appendLog("✓ Original desinstalado.")
            val apk = outputApk ?: return
            appendLog("Instalando patcheado...")
            installBest(apk)
        }
        btnUninstall.visibility = if (isPackageInstalled(pkg)) View.VISIBLE else View.GONE
    }

    private fun updateScanUI(app: AppInfo) {
        val sb = StringBuilder()
        sb.appendLine("=== RESULTADO DO SCAN ===\n")

        if (app.detectedPurchases.isNotEmpty()) {
            sb.appendLine("> IAP: ${app.detectedPurchases.size} padrao(oes)")
            app.detectedPurchases.forEach { p ->
                sb.appendLine("  - ${p.methodName} [${p.validationMethod.name}]")
            }
            cbIAP.isChecked = true
        } else {
            sb.appendLine("> IAP: Nenhum detectado")
            cbIAP.isChecked = false
        }

        if (app.detectedAds.isNotEmpty()) {
            sb.appendLine("\n> Anuncios: ${app.detectedAds.size} SDK(s)")
            app.detectedAds.forEach { ad ->
                sb.appendLine("  - ${ad.sdkName}")
            }
            cbAds.isChecked = true
        } else {
            sb.appendLine("\n> Anuncios: Nenhum detectado")
            cbAds.isChecked = false
        }

        if (app.detectedLicense != null) {
            sb.appendLine("\n> Licenca: ${app.detectedLicense!!.type.name}")
            sb.appendLine("  ${app.detectedLicense!!.methodName} em ${app.detectedLicense!!.className}")
            cbLicense.isChecked = true
        } else {
            sb.appendLine("\n> Licenca: Nenhuma detectada")
            cbLicense.isChecked = false
        }

        if (app.detectedProtections.isNotEmpty()) {
            sb.appendLine("\n> Protecoes: ${app.detectedProtections.size} tipo(s)")
            app.detectedProtections.forEach { p ->
                sb.appendLine("  - ${p.type.name}: ${p.detail}")
            }
            cbProtection.isChecked = true
        } else {
            sb.appendLine("\n> Protecoes: Nenhuma detectada")
            cbProtection.isChecked = false
        }

        tvScanResult.text = sb.toString().trimEnd()
    }

    private fun startPatch() {
        btnPatch.isEnabled = false
        btnInstall.visibility = View.GONE
        progressBar.visibility = View.VISIBLE
        tvLog.text = ""

        val options = PatchEngine.PatchOptions(
            patchIAP = cbIAP.isChecked,
            patchAds = cbAds.isChecked,
            patchLicense = cbLicense.isChecked,
            // Signature/tamper bypass always runs so re-signed apps can launch.
            patchProtection = true
        )

        lifecycleScope.launch {
            val result = PatchEngine.patch(
                context = this@PatchActivity,
                app = appInfo,
                options = options,
                onLog = { line -> runOnUiThread { appendLog(line) } }
            )

            progressBar.visibility = View.GONE
            btnPatch.isEnabled = true

            if (result.success && result.outputApk != null) {
                outputApk = result.outputApk
                splitApks = result.splitApks
                appendLog("\nPatch concluido! Instalando...")
                autoInstall()
            } else {
                appendLog("\nFalha: ${result.error}")
            }
        }
    }

    private fun autoInstall() {
        val apk = outputApk ?: return

        lifecycleScope.launch {
            val rooted = withContext(Dispatchers.IO) { RootUtil.isRooted() }

            if (rooted) {
                appendLog("\n[ROOT] Desinstalando original...")
                val uninstall = withContext(Dispatchers.IO) {
                    RootUtil.exec("pm uninstall $pkg")
                }
                appendLog(if (uninstall.success) "  ✓ Desinstalado" else "  ~ Nao estava instalado")

                appendLog("[ROOT] Instalando patcheado...")
                val install = withContext(Dispatchers.IO) {
                    RootUtil.exec("pm install -r -d \"${apk.absolutePath}\"")
                }
                if (install.success) {
                    appendLog("✓ Instalado com sucesso!")
                    btnInstall.visibility = View.GONE
                    btnUninstall.visibility = View.GONE
                    return@launch
                }
                appendLog("Root install falhou: ${install.output}")
                appendLog("Tentando via intent...")
            }

            if (isPackageInstalled(pkg)) {
                appendLog("\n⚠ Desinstale o app original primeiro")
                pendingInstall = true
                btnUninstall.visibility = View.VISIBLE
                btnInstall.visibility = View.VISIBLE
                uninstallOriginal()
            } else {
                installBest(apk)
            }
        }
    }

    private fun installBest(apk: File) {
        // Always use session install — it returns the exact INSTALL_FAILED_* reason
        // via InstallReceiver, and it is the only correct path for split apps.
        val count = splitApks.size + 1
        appendLog("\nInstalando via session ($count apk${if (count > 1) "s" else ""})...")
        try {
            SessionInstaller.install(this, apk, splitApks) { line -> appendLog(line) }
        } catch (e: Exception) {
            appendLog("Session install falhou: ${e.javaClass.simpleName}: ${e.message}")
            appendLog("Fallback para intent install...")
            installViaIntent(apk)
        }
    }

    private fun appendLog(line: String) {
        tvLog.append(line + "\n")
        scrollLog.post { scrollLog.fullScroll(View.FOCUS_DOWN) }
    }

    private fun installOutput() {
        val apk = outputApk ?: return
        if (isPackageInstalled(pkg)) {
            appendLog("⚠ Desinstale o original antes!")
            pendingInstall = true
            uninstallOriginal()
            return
        }
        installBest(apk)
    }

    private fun installViaIntent(apk: File) {
        try {
            val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", apk)
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
            }
            startActivity(intent)
        } catch (e: Exception) {
            appendLog("Erro ao instalar: ${e.message}")
            appendLog("APK salvo em: ${apk.absolutePath}")
        }
    }

    private fun uninstallOriginal() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                startActivity(Intent(Intent.ACTION_DELETE).apply {
                    data = Uri.parse("package:$pkg")
                })
            } else {
                startActivity(Intent(Intent.ACTION_UNINSTALL_PACKAGE).apply {
                    data = Uri.parse("package:$pkg")
                })
            }
        } catch (e: Exception) {
            appendLog("Erro ao desinstalar: ${e.message}")
        }
    }

    private fun isPackageInstalled(pkg: String): Boolean = try {
        packageManager.getPackageInfo(pkg, 0); true
    } catch (_: Exception) { false }
}
