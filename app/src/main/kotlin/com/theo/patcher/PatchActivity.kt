package com.theo.patcher

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import com.theo.patcher.model.AppInfo
import com.theo.patcher.model.PurchaseInfo
import com.theo.patcher.patcher.PatchEngine
import com.theo.patcher.scanner.IAPDetector
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class PatchActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_PACKAGE  = "pkg"
        const val EXTRA_APP_NAME = "name"
        const val EXTRA_APK_PATH = "apk"
    }

    private lateinit var tvAppName: TextView
    private lateinit var tvIapInfo: TextView
    private lateinit var btnPatch: Button
    private lateinit var btnInstall: Button
    private lateinit var btnUninstall: Button
    private lateinit var tvLog: TextView
    private lateinit var scrollLog: ScrollView
    private lateinit var progressBar: android.widget.ProgressBar

    private var outputApk: File? = null
    private lateinit var appInfo: AppInfo

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_patch)

        tvAppName    = findViewById(R.id.tvAppName)
        tvIapInfo    = findViewById(R.id.tvIapInfo)
        btnPatch     = findViewById(R.id.btnPatch)
        btnInstall   = findViewById(R.id.btnInstall)
        btnUninstall = findViewById(R.id.btnUninstall)
        tvLog        = findViewById(R.id.tvLog)
        scrollLog    = findViewById(R.id.scrollLog)
        progressBar  = findViewById(R.id.progressBar)

        val pkg     = intent.getStringExtra(EXTRA_PACKAGE) ?: return finish()
        val name    = intent.getStringExtra(EXTRA_APP_NAME) ?: pkg
        val apkPath = intent.getStringExtra(EXTRA_APK_PATH) ?: return finish()

        tvAppName.text = name
        btnInstall.visibility   = View.GONE
        btnUninstall.visibility = View.GONE

        lifecycleScope.launch {
            val purchases = withContext(Dispatchers.IO) { IAPDetector.detect(File(apkPath)) }
            appInfo = AppInfo(
                packageName = pkg,
                appName = name,
                icon = null,
                apkPath = apkPath,
                versionName = "?",
                apkSize = File(apkPath).length(),
                iapStatus = if (purchases.isEmpty()) AppInfo.IAPStatus.NO_IAP else AppInfo.IAPStatus.HAS_IAP,
                detectedPurchases = purchases
            )
            tvIapInfo.text = buildIapSummary(purchases)

            // show uninstall only if app is actually installed
            val isInstalled = isPackageInstalled(pkg)
            if (isInstalled) btnUninstall.visibility = View.VISIBLE
        }

        btnPatch.setOnClickListener { startPatch() }
        btnInstall.setOnClickListener { installOutput() }
        btnUninstall.setOnClickListener { uninstallOriginal(pkg) }
    }

    private fun isPackageInstalled(pkg: String): Boolean = try {
        packageManager.getPackageInfo(pkg, 0)
        true
    } catch (e: Exception) { false }

    private fun buildIapSummary(list: List<PurchaseInfo>): String {
        if (list.isEmpty()) return "Nenhum IAP detectado — app pode ainda ter validação oculta"
        return buildString {
            appendLine("${list.size} padrão(ões) IAP detectado(s):\n")
            list.forEach { p ->
                appendLine("• ${p.methodName}")
                appendLine("  Tipo: ${p.type.name.replace('_', ' ')}")
                appendLine("  Validação: ${p.validationMethod.name.replace('_', ' ')}")
                if (p.productId.isNotBlank()) appendLine("  ID: ${p.productId}")
                appendLine()
            }
        }.trimEnd()
    }

    private fun startPatch() {
        btnPatch.isEnabled = false
        btnInstall.visibility   = View.GONE
        progressBar.visibility  = View.VISIBLE
        tvLog.text = ""
        appendLog("Iniciando Theo Patcher...\n")

        lifecycleScope.launch {
            val result = PatchEngine.patch(
                context = this@PatchActivity,
                app = appInfo,
                onLog = { line -> runOnUiThread { appendLog(line) } }
            )

            progressBar.visibility = View.GONE
            btnPatch.isEnabled = true

            if (result.success && result.outputApk != null) {
                outputApk = result.outputApk
                btnInstall.visibility = View.VISIBLE
                appendLog("\n✅ Patch concluído! Instale abaixo.")
            } else {
                appendLog("\n❌ Falha: ${result.error}")
            }
        }
    }

    private fun appendLog(line: String) {
        tvLog.append(line + "\n")
        scrollLog.post { scrollLog.fullScroll(View.FOCUS_DOWN) }
    }

    private fun installOutput() {
        val apk = outputApk ?: return
        val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", apk)
        startActivity(Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        })
    }

    private fun uninstallOriginal(pkg: String) {
        startActivity(Intent(Intent.ACTION_DELETE).apply {
            data = Uri.parse("package:$pkg")
        })
    }
}
