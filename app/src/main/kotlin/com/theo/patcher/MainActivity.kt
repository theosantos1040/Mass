// MainActivity.kt — Theo Patcher main screen: app list + search + IAP badges
package com.theo.patcher

import android.content.Intent
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.View
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

class MainActivity : AppCompatActivity() {

    private lateinit var recycler: RecyclerView
    private lateinit var searchBar: EditText
    private lateinit var progressBar: ProgressBar
    private lateinit var tvStatus: TextView
    private lateinit var loadingLayout: LinearLayout
    private lateinit var tvProgress: TextView

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

        recycler.layoutManager = LinearLayoutManager(this)
        recycler.adapter = adapter

        searchBar.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                adapter.filter(s?.toString() ?: "")
            }
            override fun afterTextChanged(s: Editable?) {}
        })

        loadApps()
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
                tvStatus.text = "${apps.size} apps · $iapCount com IAP detectado"
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
