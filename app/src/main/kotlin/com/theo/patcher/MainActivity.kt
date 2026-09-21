package com.theo.patcher

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.theo.patcher.adapter.AppListAdapter
import com.theo.patcher.model.AppInfo
import com.theo.patcher.scanner.AppScanner
import kotlinx.coroutines.launch
import java.io.File

class MainActivity : AppCompatActivity() {

    companion object {
        private const val REQ_PICK_APK = 1001
    }

    private lateinit var recycler: RecyclerView
    private lateinit var searchBar: EditText
    private lateinit var progressBar: ProgressBar
    private lateinit var tvStatus: TextView
    private lateinit var loadingLayout: LinearLayout
    private lateinit var tvProgress: TextView
    private lateinit var btnPickApk: Button

    private val adapter = AppListAdapter { app -> openPatchScreen(app) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        recycler      = findViewById(R.id.rvAppList)
        searchBar     = findViewById(R.id.etSearch)
        progressBar   = findViewById(R.id.progressBar)
        tvStatus      = findViewById(R.id.tvStatus)
        loadingLayout = findViewById(R.id.layoutLoading)
        tvProgress    = findViewById(R.id.tvProgress)
        btnPickApk    = findViewById(R.id.btnPickApk)

        recycler.layoutManager = LinearLayoutManager(this)
        recycler.adapter = adapter

        searchBar.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                adapter.filter(s?.toString() ?: "")
            }
            override fun afterTextChanged(s: Editable?) {}
        })

        btnPickApk.setOnClickListener { openFilePicker() }

        loadApps()
    }

    private fun openFilePicker() {
        val intent = Intent(Intent.ACTION_GET_CONTENT).apply {
            type = "application/vnd.android.package-archive"
            addCategory(Intent.CATEGORY_OPENABLE)
        }
        startActivityForResult(Intent.createChooser(intent, "Selecionar APK"), REQ_PICK_APK)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_PICK_APK && resultCode == Activity.RESULT_OK) {
            val uri = data?.data ?: return
            handlePickedApk(uri)
        }
    }

    private fun handlePickedApk(uri: Uri) {
        lifecycleScope.launch {
            val fileName = getFileName(uri) ?: "app.apk"
            val cacheFile = File(cacheDir, "picked_${System.currentTimeMillis()}.apk")
            contentResolver.openInputStream(uri)?.use { input ->
                cacheFile.outputStream().use { output -> input.copyTo(output) }
            }
            val app = AppInfo(
                packageName = fileName.removeSuffix(".apk"),
                appName = fileName.removeSuffix(".apk"),
                icon = null,
                apkPath = cacheFile.absolutePath,
                versionName = "?",
                apkSize = cacheFile.length(),
                iapStatus = AppInfo.IAPStatus.UNKNOWN
            )
            openPatchScreen(app)
        }
    }

    private fun getFileName(uri: Uri): String? {
        contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (cursor.moveToFirst() && idx >= 0) return cursor.getString(idx)
        }
        return uri.lastPathSegment
    }

    private fun loadApps() {
        loadingLayout.visibility = View.VISIBLE
        recycler.visibility      = View.GONE
        searchBar.visibility     = View.GONE

        lifecycleScope.launch {
            val apps = AppScanner.scanInstalledApps(this@MainActivity) { done, total ->
                runOnUiThread {
                    tvProgress.text = "Escaneando apps: $done/$total"
                    progressBar.max = total
                    progressBar.progress = done
                }
            }

            val iapCount = apps.count { it.iapStatus == AppInfo.IAPStatus.HAS_IAP }

            runOnUiThread {
                loadingLayout.visibility = View.GONE
                recycler.visibility      = View.VISIBLE
                searchBar.visibility     = View.VISIBLE
                tvStatus.text = "${apps.size} apps · $iapCount com IAP"
                adapter.submitFullList(apps)
            }
        }
    }

    private fun openPatchScreen(app: AppInfo) {
        val intent = Intent(this, PatchActivity::class.java).apply {
            putExtra(PatchActivity.EXTRA_PACKAGE, app.packageName)
            putExtra(PatchActivity.EXTRA_APP_NAME, app.appName)
            putExtra(PatchActivity.EXTRA_APK_PATH, app.apkPath)
        }
        startActivity(intent)
    }
}
