package com.theo.patcher.util

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller

class InstallReceiver : BroadcastReceiver() {

    companion object {
        // Set by PatchActivity so the raw installer result reaches the UI log.
        // Invoked on the main thread by the system.
        @Volatile
        var onResult: ((status: Int, message: String?) -> Unit)? = null
    }

    override fun onReceive(context: Context, intent: Intent) {
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, Int.MIN_VALUE)
        val message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
        val pkg = intent.getStringExtra(PackageInstaller.EXTRA_PACKAGE_NAME)

        if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
            @Suppress("DEPRECATION")
            val confirm = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)
            confirm?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (confirm != null) {
                try { context.startActivity(confirm) } catch (_: Exception) {}
            }
        }

        onResult?.invoke(status, message ?: "pkg=$pkg")
    }
}
